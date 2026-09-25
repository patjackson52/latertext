package com.patjackson.latertext.platform.android.execution

import com.patjackson.latertext.data.api.OccurrenceEventRecord
import com.patjackson.latertext.data.api.AttemptRepository
import com.patjackson.latertext.data.api.OccurrenceRecord
import com.patjackson.latertext.data.api.OccurrenceRepository
import com.patjackson.latertext.data.api.OccurrenceState
import com.patjackson.latertext.data.api.ScheduleRepository
import com.patjackson.latertext.data.api.SettingsRepository
import com.patjackson.latertext.platform.api.AlarmPrecision
import com.patjackson.latertext.platform.api.AppClock
import com.patjackson.latertext.platform.api.AppNotification
import com.patjackson.latertext.platform.api.NotificationKind
import com.patjackson.latertext.platform.api.NotificationPublisher
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ExecutionRecoveryResult(
    val cause: ReconciliationCause,
    val reclaimedOccurrences: Int,
    val expiredAssistedOccurrences: Int,
    val timedOutSentCallbacks: Int,
    val timedOutDeliveryReports: Int,
    val providerConfirmedSends: Int,
    val providerDeliveryUpdates: Int,
    val providerConfirmedFailures: Int,
    val alarmResult: AlarmCoordinationResult,
)

/**
 * Reconciles durable execution state after process death and Android system changes. The public
 * cause-specific methods are intended to be called by manifest receivers and a WorkManager
 * watchdog; they all converge on the same serialized, idempotent pass.
 */
class ExecutionRecoveryCoordinator(
    private val schedules: ScheduleRepository,
    private val occurrences: OccurrenceRepository,
    private val attempts: AttemptRepository,
    private val dueProcessor: DueOccurrenceProcessor,
    private val callbackProcessor: SmsCallbackProcessor,
    private val alarmCoordinator: AlarmCoordinator,
    private val settings: SettingsRepository,
    private val notifications: NotificationPublisher,
    private val clock: AppClock,
    private val materialization: OccurrenceMaterializationCoordinator,
    private val providerReconciler: SmsProviderReconciler,
    private val attemptWorkScheduler: SmsAttemptWorkScheduler,
    private val ids: ExecutionIdFactory = UuidExecutionIdFactory(),
    private val queryLimit: Int = 500,
) {
    private val mutex = Mutex()

    init {
        require(queryLimit > 0)
    }

    suspend fun onAppStart(): ExecutionRecoveryResult = reconcile(ReconciliationCause.APP_START)

    suspend fun onBootCompleted(): ExecutionRecoveryResult =
        reconcile(ReconciliationCause.BOOT_COMPLETED)

    suspend fun onPackageReplaced(): ExecutionRecoveryResult =
        reconcile(ReconciliationCause.PACKAGE_REPLACED)

    suspend fun onWallClockChanged(): ExecutionRecoveryResult =
        reconcile(ReconciliationCause.WALL_CLOCK_CHANGED)

    suspend fun onTimeZoneChanged(): ExecutionRecoveryResult =
        reconcile(ReconciliationCause.TIME_ZONE_CHANGED)

    suspend fun onExactAlarmAccessChanged(): ExecutionRecoveryResult =
        reconcile(ReconciliationCause.EXACT_ALARM_ACCESS_CHANGED)

    suspend fun onWatchdog(): ExecutionRecoveryResult = reconcile(ReconciliationCause.WATCHDOG)

    suspend fun reconcile(cause: ReconciliationCause): ExecutionRecoveryResult = mutex.withLock {
        materialization.replenishAll()
        val now = clock.now()
        // Recovery also needs carrier-accepted and terminal rows that no longer appear in Upcoming.
        val durableOccurrences = schedules.listAll(queryLimit)
            .asSequence()
            .flatMap { it.occurrences.asSequence() }
            .distinctBy { it.id }
            .toList()
        val providerSummary = providerReconciler.reconcileAttempts(
            durableOccurrences.asSequence()
                .filter { it.state in PROVIDER_RECONCILABLE_STATES }
                .mapNotNull(OccurrenceRecord::activeAttemptId)
                .toList(),
        )

        var reclaimed = 0
        var expiredAssisted = 0
        var sentTimeouts = 0
        var deliveryTimeouts = 0
        durableOccurrences.forEach { staleOccurrence ->
            val occurrence = occurrences.get(staleOccurrence.id) ?: staleOccurrence
            when (occurrence.state) {
                OccurrenceState.CLAIMED -> {
                    if ((occurrence.claimUntilEpochMillis ?: Long.MIN_VALUE) <= now.toEpochMilli()) {
                        when (dueProcessor.process(occurrence.id, occurrence.alarmGeneration)) {
                            is DueProcessResult.Ignored -> Unit
                            else -> reclaimed += 1
                        }
                    }
                }
                OccurrenceState.SENDING -> {
                    val attemptId = occurrence.activeAttemptId ?: return@forEach
                    attempts.get(attemptId)?.attempt?.let { attempt ->
                        attemptWorkScheduler.repair(
                            attemptId,
                            attempt.startedAtEpochMillis,
                            attempt.deliveryDeadlineAtEpochMillis,
                            occurrence.state,
                        )
                    }
                    if (callbackProcessor.processSentCallbackTimeout(attemptId) is SmsCallbackProcessResult.Terminal) {
                        sentTimeouts += 1
                    }
                }
                OccurrenceState.SENT_TO_CARRIER -> {
                    val attemptId = occurrence.activeAttemptId ?: return@forEach
                    attempts.get(attemptId)?.attempt?.let { attempt ->
                        attemptWorkScheduler.repair(
                            attemptId,
                            attempt.startedAtEpochMillis,
                            attempt.deliveryDeadlineAtEpochMillis,
                            occurrence.state,
                        )
                    }
                    if (callbackProcessor.processDeliveryTimeout(attemptId) is SmsCallbackProcessResult.Terminal) {
                        deliveryTimeouts += 1
                    }
                }
                OccurrenceState.READY_FOR_USER,
                OccurrenceState.OPENED_IN_LATER_TEXT,
                -> if (occurrence.deadlineAtEpochMillis < now.toEpochMilli()) {
                    if (expireAssisted(occurrence, now)) expiredAssisted += 1
                }
                else -> Unit
            }
        }

        val alarmResult = alarmCoordinator.reconcile(cause)
        if (
            cause == ReconciliationCause.EXACT_ALARM_ACCESS_CHANGED &&
            alarmResult is AlarmCoordinationResult.Armed &&
            alarmResult.precision == AlarmPrecision.INEXACT
        ) publishTimingDegradedIfEnabled(alarmResult.occurrenceId)

        ExecutionRecoveryResult(
            cause = cause,
            reclaimedOccurrences = reclaimed,
            expiredAssistedOccurrences = expiredAssisted,
            timedOutSentCallbacks = sentTimeouts,
            timedOutDeliveryReports = deliveryTimeouts,
            providerConfirmedSends = providerSummary.confirmedSent,
            providerDeliveryUpdates = providerSummary.deliveryUpdates,
            providerConfirmedFailures = providerSummary.confirmedFailed,
            alarmResult = alarmResult,
        )
    }

    private suspend fun expireAssisted(occurrence: OccurrenceRecord, now: Instant): Boolean {
        val applied = occurrences.compareAndSetState(
            occurrenceId = occurrence.id,
            expectedStates = setOf(
                OccurrenceState.READY_FOR_USER,
                OccurrenceState.OPENED_IN_LATER_TEXT,
            ),
            newState = OccurrenceState.EXPIRED,
            updatedAtEpochMillis = now.toEpochMilli(),
        )
        if (!applied) return false
        notifications.cancel(notificationId(occurrence.id, "action"))
        occurrences.appendEvent(
            OccurrenceEventRecord(
                id = ids.newId(),
                occurrenceId = occurrence.id,
                attemptId = null,
                type = "ASSISTED_REVIEW_EXPIRED",
                detailJson = null,
                happenedAtEpochMillis = now.toEpochMilli(),
                createdAtEpochMillis = now.toEpochMilli(),
            ),
        )
        return true
    }

    private suspend fun publishTimingDegradedIfEnabled(occurrenceId: String) {
        if (!settings.get().notificationsEnabled) return
        notifications.publish(
            AppNotification(
                id = notificationId("global", "timing_degraded"),
                kind = NotificationKind.TIMING_DEGRADED,
                title = "Exact timing unavailable",
                body = "LaterText will use Android's best-effort timing until exact alarm access is restored.",
                occurrenceId = occurrenceId,
            ),
        )
    }

    companion object {
        private val PROVIDER_RECONCILABLE_STATES = setOf(
            OccurrenceState.SENDING,
            OccurrenceState.SENT_TO_CARRIER,
            OccurrenceState.PARTIAL_AMBIGUOUS,
            OccurrenceState.DELIVERY_UNAVAILABLE,
        )
    }
}
