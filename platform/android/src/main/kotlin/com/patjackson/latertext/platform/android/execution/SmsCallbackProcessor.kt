package com.patjackson.latertext.platform.android.execution

import android.app.Activity
import android.telephony.SmsManager
import com.patjackson.latertext.core.domain.reducer.AttemptEvent
import com.patjackson.latertext.core.domain.reducer.AttemptReducer
import com.patjackson.latertext.core.domain.reducer.OccurrenceEvent
import com.patjackson.latertext.core.domain.reducer.OccurrenceReducer
import com.patjackson.latertext.core.domain.retry.RetryPolicy
import com.patjackson.latertext.core.domain.retry.SmsFailureClassifier
import com.patjackson.latertext.core.model.AttemptId
import com.patjackson.latertext.core.model.AttemptSnapshot
import com.patjackson.latertext.core.model.AttemptState
import com.patjackson.latertext.core.model.DeliveryOutcome as DomainDeliveryOutcome
import com.patjackson.latertext.core.model.DeliveryPartOutcome
import com.patjackson.latertext.core.model.OccurrenceState as DomainOccurrenceState
import com.patjackson.latertext.core.model.PartSubmissionOutcome
import com.patjackson.latertext.core.model.RetryDisposition
import com.patjackson.latertext.core.model.SmsFailureCode
import com.patjackson.latertext.data.api.AttemptBundle
import com.patjackson.latertext.data.api.AttemptPartRecord
import com.patjackson.latertext.data.api.AttemptRepository
import com.patjackson.latertext.data.api.CallbackKind
import com.patjackson.latertext.data.api.DeliveryOutcome
import com.patjackson.latertext.data.api.OccurrenceEventRecord
import com.patjackson.latertext.data.api.OccurrenceExecutionRepository
import com.patjackson.latertext.data.api.OccurrenceRepository
import com.patjackson.latertext.data.api.OccurrenceState
import com.patjackson.latertext.data.api.PartOutcome
import com.patjackson.latertext.data.api.SendOutcome
import com.patjackson.latertext.data.api.SettingsRepository
import com.patjackson.latertext.platform.api.AppClock
import com.patjackson.latertext.platform.api.AppNotification
import com.patjackson.latertext.platform.api.NotificationKind
import com.patjackson.latertext.platform.api.NotificationPublisher
import java.time.Duration
import java.time.Instant

sealed interface SmsCallbackProcessResult {
    data object DuplicateOrExpired : SmsCallbackProcessResult
    data class AwaitingMoreCallbacks(val attemptId: String) : SmsCallbackProcessResult
    data class CarrierAccepted(val attemptId: String) : SmsCallbackProcessResult
    data class DeliveryCompleted(val attemptId: String, val delivered: Boolean) : SmsCallbackProcessResult
    data class RetryScheduled(val attemptId: String, val retryAt: Instant) : SmsCallbackProcessResult
    data class Terminal(val attemptId: String, val occurrenceState: OccurrenceState) : SmsCallbackProcessResult
    data class Ignored(val reason: String) : SmsCallbackProcessResult
}

/**
 * Reduces sent and delivery PendingIntent callbacks from durable part records.
 * Token consumption plus the individual part write is atomic in AttemptRepository;
 * occurrence projection is guarded by the active attempt ID.
 */
class SmsCallbackProcessor(
    private val attempts: AttemptRepository,
    private val occurrences: OccurrenceRepository,
    private val executions: OccurrenceExecutionRepository,
    private val settings: SettingsRepository,
    private val notifications: NotificationPublisher,
    private val alarmCoordinator: AlarmCoordinator,
    private val clock: AppClock,
    private val ids: ExecutionIdFactory = UuidExecutionIdFactory(),
    private val attemptReducer: AttemptReducer = AttemptReducer(),
    private val occurrenceReducer: OccurrenceReducer = OccurrenceReducer(),
    private val failureClassifier: SmsFailureClassifier = SmsFailureClassifier(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val sentCallbackTimeout: Duration = Duration.ofMinutes(15),
) {
    init {
        require(!sentCallbackTimeout.isNegative && !sentCallbackTimeout.isZero)
    }

    suspend fun process(
        attemptId: String,
        partIndex: Int,
        kind: CallbackKind,
        androidResultCode: Int,
    ): SmsCallbackProcessResult {
        require(attemptId.isNotBlank())
        require(partIndex >= 0)
        val now = clock.now()
        val token = SmsCallbackToken.create(attemptId, partIndex, kind)
        val partOutcome = when (kind) {
            CallbackKind.SENT -> AndroidSmsResultMapper.sentPartOutcome(androidResultCode)
            CallbackKind.DELIVERED -> AndroidSmsResultMapper.deliveryPartOutcome(androidResultCode)
        }
        val bundle = attempts.applyCallback(
            token = token,
            outcome = partOutcome,
            platformResultCode = androidResultCode,
            receivedAtEpochMillis = now.toEpochMilli(),
        ) ?: return SmsCallbackProcessResult.DuplicateOrExpired
        if (bundle.attempt.id != attemptId) return SmsCallbackProcessResult.Ignored("callback_attempt_mismatch")

        val occurrence = occurrences.get(bundle.attempt.occurrenceId)
            ?: return SmsCallbackProcessResult.Ignored("occurrence_missing")
        val reduced = runCatching { reduceAttempt(bundle) }.getOrElse {
            return markAmbiguous(bundle, occurrence, now, "inconsistent_callback_sequence")
        }

        if (reduced.state == AttemptState.AWAITING_SENT_CALLBACKS) {
            return SmsCallbackProcessResult.AwaitingMoreCallbacks(attemptId)
        }

        val result = when (reduced.state) {
            AttemptState.ACCEPTED -> applyAccepted(bundle, reduced, occurrence, now)
            AttemptState.RETRYABLE_FAILURE -> applyRetryableFailure(bundle, reduced, occurrence, now)
            AttemptState.AMBIGUOUS_FAILURE,
            AttemptState.PARTIAL_AMBIGUOUS,
            -> markAmbiguous(bundle, occurrence, now, reduced.state.name)
            AttemptState.TERMINAL_FAILURE -> markTerminalFailure(bundle, occurrence, now, reduced.state.name)
            AttemptState.CREATED,
            AttemptState.AWAITING_SENT_CALLBACKS,
            -> SmsCallbackProcessResult.AwaitingMoreCallbacks(attemptId)
        }
        if (kind == CallbackKind.SENT || result is SmsCallbackProcessResult.RetryScheduled) {
            alarmCoordinator.reconcile(ReconciliationCause.CALLBACK_APPLIED)
        }
        return result
    }

    /**
     * Closes an attempt whose platform accepted the enqueue call but never returned every sent
     * callback. Retrying this state could duplicate one or more SMS parts, so it is always
     * persisted as partial/ambiguous.
     */
    suspend fun processSentCallbackTimeout(attemptId: String): SmsCallbackProcessResult {
        require(attemptId.isNotBlank())
        val now = clock.now()
        val bundle = attempts.get(attemptId)
            ?: return SmsCallbackProcessResult.Ignored("attempt_missing")
        val occurrence = occurrences.get(bundle.attempt.occurrenceId)
            ?: return SmsCallbackProcessResult.Ignored("occurrence_missing")
        if (occurrence.activeAttemptId != attemptId || occurrence.state != OccurrenceState.SENDING) {
            return SmsCallbackProcessResult.DuplicateOrExpired
        }
        val timeoutAt = Instant.ofEpochMilli(bundle.attempt.startedAtEpochMillis).plus(sentCallbackTimeout)
        if (now.isBefore(timeoutAt)) return SmsCallbackProcessResult.Ignored("sent_callback_timeout_not_due")
        val reduced = runCatching { reduceAttempt(bundle) }.getOrElse {
            return markAmbiguous(bundle, occurrence, now, "inconsistent_callback_sequence")
        }
        return when (reduced.state) {
            AttemptState.CREATED,
            AttemptState.AWAITING_SENT_CALLBACKS,
            -> markAmbiguous(bundle, occurrence, now, "sent_callback_timeout")
            else -> SmsCallbackProcessResult.DuplicateOrExpired
        }.also { alarmCoordinator.reconcile(ReconciliationCause.CALLBACK_APPLIED) }
    }

    /** Marks carrier delivery as unavailable without changing the independently successful send. */
    suspend fun processDeliveryTimeout(attemptId: String): SmsCallbackProcessResult {
        require(attemptId.isNotBlank())
        val now = clock.now()
        val bundle = attempts.get(attemptId)
            ?: return SmsCallbackProcessResult.Ignored("attempt_missing")
        val occurrence = occurrences.get(bundle.attempt.occurrenceId)
            ?: return SmsCallbackProcessResult.Ignored("occurrence_missing")
        if (
            occurrence.activeAttemptId != attemptId ||
            occurrence.state != OccurrenceState.SENT_TO_CARRIER ||
            occurrence.deliveryOutcome != DeliveryOutcome.PENDING
        ) return SmsCallbackProcessResult.DuplicateOrExpired
        val deadline = bundle.attempt.deliveryDeadlineAtEpochMillis?.let(Instant::ofEpochMilli)
            ?: return SmsCallbackProcessResult.Ignored("delivery_deadline_missing")
        if (now.isBefore(deadline)) return SmsCallbackProcessResult.Ignored("delivery_timeout_not_due")

        val reduced = runCatching {
            attemptReducer.reduce(reduceAttempt(bundle), AttemptEvent.DeliveryTimedOut(now))
        }.getOrElse {
            return SmsCallbackProcessResult.Ignored("attempt_not_awaiting_delivery")
        }
        attempts.updateAttempt(
            bundle.attempt.copy(deliveryOutcome = reduced.deliveryOutcome.toRecord()),
        )
        val eventAt = monotonicEventTime(occurrence, now)
        val next = occurrenceReducer.reduce(
            occurrence.toDomainSnapshot(executions.attemptCount(occurrence.id)),
            OccurrenceEvent.RecordDelivery(DomainDeliveryOutcome.UNAVAILABLE, eventAt),
        )
        if (!executions.applyAttemptProjection(
                occurrenceId = occurrence.id,
                expectedAttemptId = attemptId,
                newState = next.state.toRecord(),
                sendOutcome = next.sendOutcome.toRecord(),
                deliveryOutcome = next.deliveryOutcome.toRecord(),
                nowEpochMillis = eventAt.toEpochMilli(),
            )
        ) return SmsCallbackProcessResult.DuplicateOrExpired
        appendEvent(occurrence.id, attemptId, "SMS_DELIVERY_UNAVAILABLE", eventAt)
        publishDeliveryUnavailableIfEnabled(occurrence.id)
        return SmsCallbackProcessResult.Terminal(attemptId, OccurrenceState.DELIVERY_UNAVAILABLE)
    }

    private suspend fun applyAccepted(
        bundle: AttemptBundle,
        reduced: AttemptSnapshot,
        occurrence: com.patjackson.latertext.data.api.OccurrenceRecord,
        now: Instant,
    ): SmsCallbackProcessResult {
        val hasDeliveryTokens = bundle.callbackTokens.any { it.kind == CallbackKind.DELIVERED }
        val delivery = if (hasDeliveryTokens) reduced.deliveryOutcome else DomainDeliveryOutcome.NOT_REQUESTED
        attempts.updateAttempt(
            bundle.attempt.copy(
                sendOutcome = SendOutcome.SENT_TO_CARRIER,
                deliveryOutcome = delivery.toRecord(),
                failureCode = null,
                failureDetail = null,
                finishedAtEpochMillis = reduced.completedAt?.toEpochMilli() ?: now.toEpochMilli(),
            ),
        )

        val occurrenceSnapshot = occurrence.toDomainSnapshot(executions.attemptCount(occurrence.id))
        val eventAt = monotonicEventTime(occurrence, now)
        var projected = if (occurrenceSnapshot.state == DomainOccurrenceState.SENDING) {
            occurrenceReducer.reduce(occurrenceSnapshot, OccurrenceEvent.CarrierAccepted(eventAt))
        } else {
            occurrenceSnapshot
        }
        if (
            projected.state == DomainOccurrenceState.SENT_TO_CARRIER &&
            delivery in setOf(
                DomainDeliveryOutcome.DELIVERED,
                DomainDeliveryOutcome.FAILED,
                DomainDeliveryOutcome.UNAVAILABLE,
            )
        ) {
            projected = occurrenceReducer.reduce(
                projected,
                OccurrenceEvent.RecordDelivery(delivery, eventAt),
            )
        }
        if (!executions.applyAttemptProjection(
                occurrenceId = occurrence.id,
                expectedAttemptId = bundle.attempt.id,
                newState = projected.state.toRecord(),
                sendOutcome = projected.sendOutcome.toRecord(),
                deliveryOutcome = delivery.toRecord(),
                nowEpochMillis = eventAt.toEpochMilli(),
            )
        ) return SmsCallbackProcessResult.Ignored("attempt_no_longer_active")

        appendEvent(occurrence.id, bundle.attempt.id, "SMS_SENT_TO_CARRIER", eventAt)
        val userSettings = settings.get()
        if (userSettings.notificationsEnabled && userSettings.sendResultNotificationsEnabled) {
            notifications.publish(
                AppNotification(
                    id = notificationId(occurrence.id, "sent"),
                    kind = NotificationKind.SEND_SUCCEEDED,
                    title = "Message sent",
                    body = "The carrier accepted the message.",
                    occurrenceId = occurrence.id,
                ),
            )
        }
        return when (delivery) {
            DomainDeliveryOutcome.DELIVERED -> {
                publishDeliveryIfEnabled(occurrence.id, true)
                SmsCallbackProcessResult.DeliveryCompleted(bundle.attempt.id, true)
            }
            DomainDeliveryOutcome.FAILED -> {
                publishDeliveryIfEnabled(occurrence.id, false)
                SmsCallbackProcessResult.DeliveryCompleted(bundle.attempt.id, false)
            }
            else -> SmsCallbackProcessResult.CarrierAccepted(bundle.attempt.id)
        }
    }

    private suspend fun applyRetryableFailure(
        bundle: AttemptBundle,
        reduced: AttemptSnapshot,
        occurrence: com.patjackson.latertext.data.api.OccurrenceRecord,
        now: Instant,
    ): SmsCallbackProcessResult {
        val decision = retryPolicy.decide(
            reduced,
            now,
            Instant.ofEpochMilli(occurrence.deadlineAtEpochMillis),
        )
        val failureCode = firstFailureCode(bundle)?.name ?: SmsFailureCode.UNKNOWN.name
        attempts.updateAttempt(
            bundle.attempt.copy(
                sendOutcome = SendOutcome.FAILED,
                deliveryOutcome = DeliveryOutcome.NOT_REQUESTED,
                failureCode = failureCode,
                failureDetail = decision.reason.name,
                finishedAtEpochMillis = reduced.completedAt?.toEpochMilli() ?: now.toEpochMilli(),
            ),
        )
        if (decision.disposition == RetryDisposition.RETRY_AT) {
            val retryAt = requireNotNull(decision.retryAt)
            val snapshot = occurrence.toDomainSnapshot(executions.attemptCount(occurrence.id))
            val next = occurrenceReducer.reduce(
                snapshot,
                OccurrenceEvent.ScheduleRetry(retryAt, monotonicEventTime(occurrence, now)),
            )
            if (!executions.applyAttemptProjection(
                occurrence.id,
                bundle.attempt.id,
                next.state.toRecord(),
                sendOutcome = next.sendOutcome.toRecord(),
                deliveryOutcome = next.deliveryOutcome.toRecord(),
                retryAtEpochMillis = retryAt.toEpochMilli(),
                nowEpochMillis = now.toEpochMilli(),
            )) return SmsCallbackProcessResult.Ignored("attempt_no_longer_active")
            appendEvent(occurrence.id, bundle.attempt.id, "SMS_RETRY_SCHEDULED", now)
            return SmsCallbackProcessResult.RetryScheduled(bundle.attempt.id, retryAt)
        }
        return markTerminalFailure(bundle, occurrence, now, decision.reason.name, attemptAlreadyUpdated = true)
    }

    private suspend fun markTerminalFailure(
        bundle: AttemptBundle,
        occurrence: com.patjackson.latertext.data.api.OccurrenceRecord,
        now: Instant,
        reason: String,
        attemptAlreadyUpdated: Boolean = false,
    ): SmsCallbackProcessResult {
        if (!attemptAlreadyUpdated) {
            attempts.updateAttempt(
                bundle.attempt.copy(
                    sendOutcome = SendOutcome.FAILED,
                    deliveryOutcome = DeliveryOutcome.NOT_REQUESTED,
                    failureCode = firstFailureCode(bundle)?.name ?: SmsFailureCode.UNKNOWN.name,
                    failureDetail = reason,
                    finishedAtEpochMillis = now.toEpochMilli(),
                ),
            )
        }
        val snapshot = occurrence.toDomainSnapshot(executions.attemptCount(occurrence.id))
        val next = occurrenceReducer.reduce(
            snapshot,
            OccurrenceEvent.TerminalFailure(monotonicEventTime(occurrence, now)),
        )
        if (!executions.applyAttemptProjection(
            occurrence.id,
            bundle.attempt.id,
            next.state.toRecord(),
            sendOutcome = next.sendOutcome.toRecord(),
            deliveryOutcome = next.deliveryOutcome.toRecord(),
            nowEpochMillis = now.toEpochMilli(),
        )) return SmsCallbackProcessResult.Ignored("attempt_no_longer_active")
        publishFailureIfEnabled(occurrence.id, false)
        appendEvent(occurrence.id, bundle.attempt.id, "SMS_FAILED_TERMINAL", now)
        return SmsCallbackProcessResult.Terminal(bundle.attempt.id, OccurrenceState.FAILED_TERMINAL)
    }

    private suspend fun markAmbiguous(
        bundle: AttemptBundle,
        occurrence: com.patjackson.latertext.data.api.OccurrenceRecord,
        now: Instant,
        reason: String,
    ): SmsCallbackProcessResult {
        attempts.updateAttempt(
            bundle.attempt.copy(
                sendOutcome = SendOutcome.PARTIAL_OR_AMBIGUOUS,
                deliveryOutcome = DeliveryOutcome.NOT_REQUESTED,
                failureCode = firstFailureCode(bundle)?.name ?: SmsFailureCode.UNKNOWN.name,
                failureDetail = reason,
                finishedAtEpochMillis = now.toEpochMilli(),
            ),
        )
        val snapshot = occurrence.toDomainSnapshot(executions.attemptCount(occurrence.id))
        val next = occurrenceReducer.reduce(
            snapshot,
            OccurrenceEvent.PartialOrAmbiguous(monotonicEventTime(occurrence, now)),
        )
        if (!executions.applyAttemptProjection(
            occurrence.id,
            bundle.attempt.id,
            next.state.toRecord(),
            sendOutcome = next.sendOutcome.toRecord(),
            deliveryOutcome = next.deliveryOutcome.toRecord(),
            nowEpochMillis = now.toEpochMilli(),
        )) return SmsCallbackProcessResult.Ignored("attempt_no_longer_active")
        publishFailureIfEnabled(occurrence.id, true)
        appendEvent(occurrence.id, bundle.attempt.id, "SMS_PARTIAL_OR_AMBIGUOUS", now)
        return SmsCallbackProcessResult.Terminal(bundle.attempt.id, OccurrenceState.PARTIAL_AMBIGUOUS)
    }

    private fun reduceAttempt(bundle: AttemptBundle): AttemptSnapshot {
        var snapshot = AttemptSnapshot(
            id = AttemptId(bundle.attempt.id),
            occurrenceId = com.patjackson.latertext.core.model.OccurrenceId(bundle.attempt.occurrenceId),
            attemptNumber = bundle.attempt.attemptNumber,
            totalParts = bundle.parts.size,
            startedAt = Instant.ofEpochMilli(bundle.attempt.startedAtEpochMillis),
        )
        snapshot = attemptReducer.reduce(snapshot, AttemptEvent.Submit(snapshot.startedAt))
        bundle.parts.asSequence()
            .filter { it.sendOutcome != PartOutcome.PENDING }
            .sortedWith(compareBy<AttemptPartRecord> { it.sentAtEpochMillis ?: Long.MAX_VALUE }.thenBy { it.partIndex })
            .forEach { part ->
                val code = if (part.sendOutcome == PartOutcome.ACCEPTED) null
                else AndroidSmsResultMapper.failureCode(part.sentResultCode)
                snapshot = attemptReducer.reduce(
                    snapshot,
                    AttemptEvent.SentPartCallback(
                        partIndex = part.partIndex,
                        outcome = if (part.sendOutcome == PartOutcome.ACCEPTED) {
                            PartSubmissionOutcome.ACCEPTED
                        } else {
                            failureClassifier.partOutcome(code ?: SmsFailureCode.UNKNOWN)
                        },
                        failureCode = code,
                        at = Instant.ofEpochMilli(part.sentAtEpochMillis ?: bundle.attempt.startedAtEpochMillis),
                    ),
                )
            }
        if (snapshot.state == AttemptState.ACCEPTED) {
            bundle.parts.asSequence()
                .filter { it.deliveryOutcome != PartOutcome.PENDING }
                .sortedWith(
                    compareBy<AttemptPartRecord> { it.deliveredAtEpochMillis ?: Long.MAX_VALUE }
                        .thenBy { it.partIndex },
                )
                .forEach { part ->
                    snapshot = attemptReducer.reduce(
                        snapshot,
                        AttemptEvent.DeliveryPartCallback(
                            partIndex = part.partIndex,
                            outcome = if (part.deliveryOutcome == PartOutcome.ACCEPTED) {
                                DeliveryPartOutcome.DELIVERED
                            } else {
                                DeliveryPartOutcome.FAILED
                            },
                            at = Instant.ofEpochMilli(
                                part.deliveredAtEpochMillis ?: bundle.attempt.startedAtEpochMillis,
                            ),
                        ),
                    )
                }
        }
        return snapshot
    }

    private fun firstFailureCode(bundle: AttemptBundle): SmsFailureCode? = bundle.parts
        .firstOrNull { it.sendOutcome != PartOutcome.PENDING && it.sendOutcome != PartOutcome.ACCEPTED }
        ?.let { AndroidSmsResultMapper.failureCode(it.sentResultCode) }

    private suspend fun publishFailureIfEnabled(occurrenceId: String, duplicateRisk: Boolean) {
        val userSettings = settings.get()
        if (!userSettings.notificationsEnabled || !userSettings.sendResultNotificationsEnabled) return
        notifications.publish(
            AppNotification(
                id = notificationId(occurrenceId, "failed"),
                kind = NotificationKind.SEND_FAILED,
                title = "Message not sent",
                body = if (duplicateRisk) {
                    "The result is uncertain. Review it before resending the entire message."
                } else {
                    "LaterText could not send the message."
                },
                occurrenceId = occurrenceId,
            ),
        )
    }

    private suspend fun publishDeliveryIfEnabled(occurrenceId: String, delivered: Boolean) {
        val userSettings = settings.get()
        if (!userSettings.notificationsEnabled || !userSettings.deliveryNotificationsEnabled) return
        notifications.publish(
            AppNotification(
                id = notificationId(occurrenceId, "delivery"),
                kind = if (delivered) NotificationKind.SEND_SUCCEEDED else NotificationKind.SEND_FAILED,
                title = if (delivered) "Message delivered" else "Delivery not confirmed",
                body = if (delivered) "The carrier reported delivery." else "The carrier reported a delivery failure.",
                occurrenceId = occurrenceId,
            ),
        )
    }

    private suspend fun publishDeliveryUnavailableIfEnabled(occurrenceId: String) {
        val userSettings = settings.get()
        if (!userSettings.notificationsEnabled || !userSettings.deliveryNotificationsEnabled) return
        notifications.publish(
            AppNotification(
                id = notificationId(occurrenceId, "delivery"),
                kind = NotificationKind.SEND_FAILED,
                title = "Delivery not confirmed",
                body = "The message was sent, but no delivery report arrived.",
                occurrenceId = occurrenceId,
            ),
        )
    }

    private suspend fun appendEvent(occurrenceId: String, attemptId: String, type: String, at: Instant) {
        occurrences.appendEvent(
            OccurrenceEventRecord(
                id = ids.newId(),
                occurrenceId = occurrenceId,
                attemptId = attemptId,
                type = type,
                detailJson = null,
                happenedAtEpochMillis = at.toEpochMilli(),
                createdAtEpochMillis = at.toEpochMilli(),
            ),
        )
    }
}

internal object AndroidSmsResultMapper {
    fun sentPartOutcome(resultCode: Int): PartOutcome =
        if (resultCode == Activity.RESULT_OK) PartOutcome.ACCEPTED else PartOutcome.FAILED

    fun deliveryPartOutcome(resultCode: Int): PartOutcome =
        if (resultCode == Activity.RESULT_OK) PartOutcome.ACCEPTED else PartOutcome.FAILED

    fun failureCode(resultCode: Int?): SmsFailureCode = when (resultCode) {
        SmsManager.RESULT_ERROR_RADIO_OFF,
        SmsManager.RESULT_RIL_RADIO_NOT_AVAILABLE,
        -> SmsFailureCode.RADIO_OFF
        SmsManager.RESULT_ERROR_NO_SERVICE,
        SmsManager.RESULT_RIL_NETWORK_NOT_READY,
        SmsManager.RESULT_RIL_NO_NETWORK_FOUND,
        -> SmsFailureCode.NO_SERVICE
        SmsManager.RESULT_RIL_NETWORK_ERR,
        SmsManager.RESULT_RIL_NETWORK_REJECT,
        -> SmsFailureCode.NETWORK_ERROR
        SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY,
        SmsManager.RESULT_RIL_SIM_BUSY,
        SmsManager.RESULT_RIL_BLOCKED_DUE_TO_CALL,
        -> SmsFailureCode.RETRYABLE_MODEM_ERROR
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED,
        SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED,
        -> SmsFailureCode.LIMIT_EXCEEDED
        SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> SmsFailureCode.FDN_BLOCKED
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED,
        SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED,
        -> SmsFailureCode.SHORT_CODE_BLOCKED
        SmsManager.RESULT_RIL_INVALID_ARGUMENTS,
        SmsManager.RESULT_RIL_INVALID_STATE,
        SmsManager.RESULT_RIL_INVALID_SMSC_ADDRESS,
        SmsManager.RESULT_RIL_INVALID_SMS_FORMAT,
        -> SmsFailureCode.INVALID_ARGUMENTS
        SmsManager.RESULT_RIL_SIM_ABSENT,
        SmsManager.RESULT_RIL_NO_SUBSCRIPTION,
        SmsManager.RESULT_RIL_SUBSCRIPTION_NOT_AVAILABLE,
        -> SmsFailureCode.SIM_UNAVAILABLE
        SmsManager.RESULT_RIL_ACCESS_BARRED,
        SmsManager.RESULT_RIL_OPERATION_NOT_ALLOWED,
        -> SmsFailureCode.PERMISSION_DENIED
        SmsManager.RESULT_ERROR_NULL_PDU -> SmsFailureCode.NULL_PDU
        SmsManager.RESULT_RIL_MODEM_ERR -> SmsFailureCode.MODEM_ERROR
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> SmsFailureCode.GENERIC_FAILURE
        else -> SmsFailureCode.UNKNOWN
    }
}
