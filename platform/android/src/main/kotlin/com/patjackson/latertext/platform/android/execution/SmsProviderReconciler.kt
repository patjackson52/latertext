package com.patjackson.latertext.platform.android.execution

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.BaseColumns
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.patjackson.latertext.data.api.AttemptBundle
import com.patjackson.latertext.data.api.AttemptRepository
import com.patjackson.latertext.data.api.CorroboratedAttemptProjection
import com.patjackson.latertext.data.api.DeliveryOutcome
import com.patjackson.latertext.data.api.OccurrenceEventRecord
import com.patjackson.latertext.data.api.OccurrenceExecutionRepository
import com.patjackson.latertext.data.api.OccurrenceRecord
import com.patjackson.latertext.data.api.OccurrenceRepository
import com.patjackson.latertext.data.api.OccurrenceState
import com.patjackson.latertext.data.api.PartOutcome
import com.patjackson.latertext.data.api.ScheduleRepository
import com.patjackson.latertext.data.api.SendOutcome
import com.patjackson.latertext.data.api.SettingsRepository
import com.patjackson.latertext.data.api.TransportMode
import com.patjackson.latertext.platform.api.AppClock
import com.patjackson.latertext.platform.api.AppNotification
import com.patjackson.latertext.platform.api.NotificationKind
import com.patjackson.latertext.platform.api.NotificationPublisher
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SmsProviderQuery(
    val creatorPackage: String,
    val recipientAddress: String,
    val body: String,
    val subscriptionId: Int?,
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val knownProviderMessageId: Long?,
)

data class SmsProviderMessage(
    val id: Long,
    val address: String,
    val body: String,
    val dateEpochMillis: Long,
    val dateSentEpochMillis: Long,
    val type: Int,
    val status: Int,
    val errorCode: Int,
    val subscriptionId: Int,
    val creatorPackage: String?,
)

sealed interface SmsProviderReadResult {
    data class Records(val values: List<SmsProviderMessage>) : SmsProviderReadResult
    data class Unavailable(val reason: String) : SmsProviderReadResult
}

interface SmsProviderReader {
    fun canRead(): Boolean
    fun read(query: SmsProviderQuery): SmsProviderReadResult
    fun readChangedUri(uri: Uri): SmsProviderReadResult
}

class AndroidSmsProviderReader(private val context: Context) : SmsProviderReader {
    override fun canRead(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_SMS,
    ) == PackageManager.PERMISSION_GRANTED

    override fun read(query: SmsProviderQuery): SmsProviderReadResult {
        if (!canRead()) return SmsProviderReadResult.Unavailable("read_sms_permission_missing")
        val selection: String
        val arguments: Array<String>
        if (query.knownProviderMessageId != null) {
            selection = "${BaseColumns._ID} = ?"
            arguments = arrayOf(query.knownProviderMessageId.toString())
        } else {
            selection = "${Telephony.Sms.BODY} = ? " +
                "AND (${Telephony.Sms.DATE} BETWEEN ? AND ? " +
                "OR ${Telephony.Sms.DATE_SENT} BETWEEN ? AND ?) " +
                "AND ${Telephony.Sms.TYPE} IN (?, ?, ?, ?)"
            arguments = arrayOf(
                query.body,
                query.fromEpochMillis.toString(),
                query.toEpochMillis.toString(),
                query.fromEpochMillis.toString(),
                query.toEpochMillis.toString(),
                Telephony.Sms.MESSAGE_TYPE_SENT.toString(),
                Telephony.Sms.MESSAGE_TYPE_FAILED.toString(),
                Telephony.Sms.MESSAGE_TYPE_OUTBOX.toString(),
                Telephony.Sms.MESSAGE_TYPE_QUEUED.toString(),
            )
        }
        return queryProvider(Telephony.Sms.CONTENT_URI, selection, arguments)
    }

    override fun readChangedUri(uri: Uri): SmsProviderReadResult {
        if (!canRead()) return SmsProviderReadResult.Unavailable("read_sms_permission_missing")
        if (
            uri.scheme != Telephony.Sms.CONTENT_URI.scheme ||
            uri.authority != Telephony.Sms.CONTENT_URI.authority ||
            uri.lastPathSegment?.toLongOrNull() == null
        ) return SmsProviderReadResult.Unavailable("unsupported_sms_change_uri")
        return queryProvider(uri, null, null)
    }

    private fun queryProvider(
        uri: Uri,
        selection: String?,
        arguments: Array<String>?,
    ): SmsProviderReadResult {
        return runCatching {
            val values = context.contentResolver.query(
                uri,
                PROJECTION,
                selection,
                arguments,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val dateSent = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
                val type = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val status = cursor.getColumnIndexOrThrow(Telephony.Sms.STATUS)
                val errorCode = cursor.getColumnIndexOrThrow(Telephony.Sms.ERROR_CODE)
                val subscription = cursor.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)
                val creator = cursor.getColumnIndexOrThrow(Telephony.Sms.CREATOR)
                buildList {
                    while (cursor.moveToNext() && size < MAX_CANDIDATES) {
                        add(
                            SmsProviderMessage(
                                id = cursor.getLong(id),
                                address = cursor.getString(address).orEmpty(),
                                body = cursor.getString(body).orEmpty(),
                                dateEpochMillis = cursor.getLong(date),
                                dateSentEpochMillis = cursor.getLong(dateSent),
                                type = cursor.getInt(type),
                                status = cursor.getInt(status),
                                errorCode = cursor.getInt(errorCode),
                                subscriptionId = cursor.getInt(subscription),
                                creatorPackage = cursor.getString(creator),
                            ),
                        )
                    }
                }
            }.orEmpty()
            SmsProviderReadResult.Records(values)
        }.getOrElse { error ->
            SmsProviderReadResult.Unavailable(error.javaClass.simpleName)
        }
    }

    companion object {
        private const val MAX_CANDIDATES = 20
        private val PROJECTION = arrayOf(
            BaseColumns._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.TYPE,
            Telephony.Sms.STATUS,
            Telephony.Sms.ERROR_CODE,
            Telephony.Sms.SUBSCRIPTION_ID,
            Telephony.Sms.CREATOR,
        )
    }
}

sealed interface SmsProviderMatch {
    data class Unique(val message: SmsProviderMessage) : SmsProviderMatch
    data object None : SmsProviderMatch
    data class Ambiguous(val count: Int) : SmsProviderMatch
}

internal object SmsProviderCandidateMatcher {
    fun select(query: SmsProviderQuery, values: List<SmsProviderMessage>): SmsProviderMatch {
        val matches = values.filter { value ->
            value.type in OUTGOING_TYPES &&
                value.body == query.body &&
                value.matchesTime(query) &&
                (query.subscriptionId == null || value.subscriptionId == query.subscriptionId) &&
                equivalentAddress(value.address, query.recipientAddress) &&
                (query.knownProviderMessageId == null || value.id == query.knownProviderMessageId)
        }
        val preferredCreatorMatches = matches.filter { it.creatorPackage == query.creatorPackage }
        val resolvedMatches = if (preferredCreatorMatches.size == 1) {
            preferredCreatorMatches
        } else {
            matches
        }
        return when (resolvedMatches.size) {
            0 -> SmsProviderMatch.None
            1 -> SmsProviderMatch.Unique(resolvedMatches.single())
            else -> SmsProviderMatch.Ambiguous(resolvedMatches.size)
        }
    }

    private fun SmsProviderMessage.matchesTime(query: SmsProviderQuery): Boolean =
        dateEpochMillis in query.fromEpochMillis..query.toEpochMillis ||
            dateSentEpochMillis > 0 &&
            dateSentEpochMillis in query.fromEpochMillis..query.toEpochMillis

    private fun equivalentAddress(first: String, second: String): Boolean {
        val firstDigits = first.filter(Char::isDigit)
        val secondDigits = second.filter(Char::isDigit)
        if (firstDigits == secondDigits) return true
        val shorter = minOf(firstDigits.length, secondDigits.length)
        return shorter >= MIN_COMPARABLE_DIGITS &&
            (firstDigits.endsWith(secondDigits) || secondDigits.endsWith(firstDigits))
    }

    private const val MIN_COMPARABLE_DIGITS = 7
    private val OUTGOING_TYPES = setOf(
        Telephony.Sms.MESSAGE_TYPE_SENT,
        Telephony.Sms.MESSAGE_TYPE_FAILED,
        Telephony.Sms.MESSAGE_TYPE_OUTBOX,
        Telephony.Sms.MESSAGE_TYPE_QUEUED,
    )
}

sealed interface SmsProviderReconcileResult {
    data class ObservedPending(val attemptId: String, val providerMessageId: Long) :
        SmsProviderReconcileResult
    data class ConfirmedSent(val attemptId: String, val providerMessageId: Long) : SmsProviderReconcileResult
    data class DeliveryUpdated(
        val attemptId: String,
        val providerMessageId: Long,
        val delivered: Boolean,
    ) : SmsProviderReconcileResult
    data class ConfirmedFailed(val attemptId: String, val providerMessageId: Long) : SmsProviderReconcileResult
    data class NoMatch(val attemptId: String) : SmsProviderReconcileResult
    data class Ambiguous(val attemptId: String, val count: Int) : SmsProviderReconcileResult
    data class Unavailable(val reason: String) : SmsProviderReconcileResult
    data class Ignored(val reason: String) : SmsProviderReconcileResult
}

data class SmsProviderReconcileSummary(
    val confirmedSent: Int,
    val deliveryUpdates: Int,
    val confirmedFailed: Int,
    val ambiguous: Int,
    val observedPending: Int = 0,
)

/**
 * Uses the public Telephony provider only as corroboration. Missing or ambiguous rows never cause
 * failure or retry, while a unique sent/failed row can repair a lost PendingIntent callback.
 */
class SmsProviderReconciler(
    private val packageName: String,
    private val reader: SmsProviderReader,
    private val schedules: ScheduleRepository,
    private val occurrences: OccurrenceRepository,
    private val executions: OccurrenceExecutionRepository,
    private val attempts: AttemptRepository,
    private val settings: SettingsRepository,
    private val notifications: NotificationPublisher,
    private val clock: AppClock,
    private val workScheduler: SmsAttemptWorkScheduler,
    private val ids: ExecutionIdFactory = UuidExecutionIdFactory(),
) {
    private val mutex = Mutex()

    suspend fun reconcilePending(): SmsProviderReconcileSummary = mutex.withLock {
        if (!reader.canRead()) return SmsProviderReconcileSummary(0, 0, 0, 0, 0)
        val attemptIds = reconcilableAttemptIds()
        return reconcileAttemptsUnlocked(attemptIds)
    }

    /** Reconciles a row snapshot captured directly from a ContentObserver notification. */
    suspend fun reconcileObserved(messages: List<SmsProviderMessage>): SmsProviderReconcileSummary =
        mutex.withLock {
            if (!reader.canRead() || messages.isEmpty()) {
                return@withLock SmsProviderReconcileSummary(0, 0, 0, 0, 0)
            }
            reconcileAttemptsUnlocked(reconcilableAttemptIds(), messages)
        }

    suspend fun reconcileAttempts(attemptIds: Collection<String>): SmsProviderReconcileSummary =
        mutex.withLock { reconcileAttemptsUnlocked(attemptIds) }

    private suspend fun reconcileAttemptsUnlocked(
        attemptIds: Collection<String>,
        observedMessages: List<SmsProviderMessage>? = null,
    ): SmsProviderReconcileSummary {
        var sent = 0
        var delivery = 0
        var failed = 0
        var ambiguous = 0
        var pending = 0
        val consumedObservedIds = mutableSetOf<Long>()
        val orderedAttemptIds = if (observedMessages == null) {
            attemptIds.distinct()
        } else {
            buildList {
                attemptIds.distinct().forEach { attemptId ->
                    attempts.get(attemptId)?.attempt?.startedAtEpochMillis?.let { startedAt ->
                        val distance = observedMessages.minOf { message ->
                            abs(message.effectiveTimestampEpochMillis() - startedAt)
                        }
                        add(attemptId to distance)
                    }
                }
            }.sortedBy { it.second }.map { it.first }
        }
        orderedAttemptIds.forEach { attemptId ->
            val availableObserved = observedMessages?.filterNot { it.id in consumedObservedIds }
            when (val result = reconcileAttemptUnlocked(attemptId, availableObserved)) {
                is SmsProviderReconcileResult.ObservedPending -> {
                    pending += 1
                    consumedObservedIds += result.providerMessageId
                }
                is SmsProviderReconcileResult.ConfirmedSent -> {
                    sent += 1
                    consumedObservedIds += result.providerMessageId
                }
                is SmsProviderReconcileResult.DeliveryUpdated -> {
                    delivery += 1
                    consumedObservedIds += result.providerMessageId
                }
                is SmsProviderReconcileResult.ConfirmedFailed -> {
                    failed += 1
                    consumedObservedIds += result.providerMessageId
                }
                is SmsProviderReconcileResult.Ambiguous -> ambiguous += 1
                else -> Unit
            }
        }
        return SmsProviderReconcileSummary(sent, delivery, failed, ambiguous, pending)
    }

    suspend fun reconcileAttempt(attemptId: String): SmsProviderReconcileResult =
        mutex.withLock { reconcileAttemptUnlocked(attemptId) }

    /**
     * Catches short-lived outbox/sent rows immediately after SmsManager returns. The total delay is
     * bounded well below BroadcastReceiver.goAsync()'s execution window; WorkManager remains the
     * durable fallback.
     */
    suspend fun reconcileAfterEnqueue(attemptId: String): SmsProviderReconcileResult {
        var result = reconcileAttempt(attemptId)
        FAST_PROVIDER_POLLS.forEach { wait ->
            if (result.isConclusive()) return result
            delay(wait.toMillis())
            result = reconcileAttempt(attemptId)
        }
        return result
    }

    private suspend fun reconcileAttemptUnlocked(
        attemptId: String,
        observedMessages: List<SmsProviderMessage>? = null,
    ): SmsProviderReconcileResult {
        if (!reader.canRead()) return SmsProviderReconcileResult.Unavailable("read_sms_permission_missing")
        val bundle = attempts.get(attemptId)
            ?: return SmsProviderReconcileResult.Ignored("attempt_missing")
        if (bundle.attempt.transportMode != TransportMode.AUTOMATIC_SMS) {
            return SmsProviderReconcileResult.Ignored("assisted_transport")
        }
        val occurrence = occurrences.get(bundle.attempt.occurrenceId)
            ?: return SmsProviderReconcileResult.Ignored("occurrence_missing")
        if (occurrence.activeAttemptId != attemptId || occurrence.state !in RECONCILABLE_STATES) {
            return SmsProviderReconcileResult.Ignored("attempt_not_reconcilable")
        }
        val graph = schedules.get(occurrence.scheduleId)
            ?: return SmsProviderReconcileResult.Ignored("schedule_missing")
        val content = schedules.contentRevision(occurrence.contentRevisionId)
            ?: return SmsProviderReconcileResult.Ignored("content_revision_missing")
        val query = SmsProviderQuery(
            creatorPackage = packageName,
            recipientAddress = graph.recipient.normalizedAddress,
            body = content.text,
            subscriptionId = bundle.attempt.subscriptionId,
            fromEpochMillis = bundle.attempt.startedAtEpochMillis - MATCH_EARLY.toMillis(),
            toEpochMillis = bundle.attempt.startedAtEpochMillis + MATCH_LATE.toMillis(),
            knownProviderMessageId = bundle.attempt.providerMessageId,
        )
        val values = observedMessages ?: when (val read = reader.read(query)) {
                is SmsProviderReadResult.Records -> read.values
                is SmsProviderReadResult.Unavailable ->
                    return SmsProviderReconcileResult.Unavailable(read.reason)
            }
        return when (val match = SmsProviderCandidateMatcher.select(query, values)) {
            SmsProviderMatch.None -> SmsProviderReconcileResult.NoMatch(attemptId)
            is SmsProviderMatch.Ambiguous -> {
                appendEvent(occurrence.id, attemptId, "SMS_PROVIDER_MATCH_AMBIGUOUS", null, match.count)
                SmsProviderReconcileResult.Ambiguous(attemptId, match.count)
            }
            is SmsProviderMatch.Unique -> applyMatch(bundle, occurrence, match.message)
        }
    }

    private suspend fun applyMatch(
        bundle: AttemptBundle,
        occurrence: OccurrenceRecord,
        message: SmsProviderMessage,
    ): SmsProviderReconcileResult = when (message.type) {
        Telephony.Sms.MESSAGE_TYPE_FAILED -> applyFailed(bundle, occurrence, message)
        Telephony.Sms.MESSAGE_TYPE_SENT -> applySent(bundle, occurrence, message)
        Telephony.Sms.MESSAGE_TYPE_OUTBOX,
        Telephony.Sms.MESSAGE_TYPE_QUEUED,
        -> applyPendingObservation(bundle, occurrence, message)
        else -> SmsProviderReconcileResult.Ignored("provider_row_not_outgoing")
    }

    private suspend fun applyPendingObservation(
        bundle: AttemptBundle,
        occurrence: OccurrenceRecord,
        message: SmsProviderMessage,
    ): SmsProviderReconcileResult {
        val now = clock.now()
        val updatedAttempt = bundle.attempt.copy(
            providerMessageId = message.id,
            providerStatus = message.status,
            providerErrorCode = message.errorCode,
            providerObservedAtEpochMillis = now.toEpochMilli(),
        )
        if (updatedAttempt != bundle.attempt && !executions.applyCorroboratedProjection(
                CorroboratedAttemptProjection(
                    expectedOccurrenceStates = RECONCILABLE_STATES,
                    expectedAttempt = bundle.attempt,
                    expectedParts = bundle.parts,
                    occurrenceState = occurrence.state,
                    sendOutcome = occurrence.sendOutcome,
                    deliveryOutcome = occurrence.deliveryOutcome,
                    attempt = updatedAttempt,
                    parts = bundle.parts,
                    event = providerEvent(
                        occurrence.id,
                        bundle.attempt.id,
                        "SMS_PROVIDER_OUTGOING_OBSERVED",
                        message,
                        now,
                    ),
                ),
            )
        ) return SmsProviderReconcileResult.Ignored("attempt_changed_during_provider_observation")
        return SmsProviderReconcileResult.ObservedPending(bundle.attempt.id, message.id)
    }

    private suspend fun applySent(
        bundle: AttemptBundle,
        occurrence: OccurrenceRecord,
        message: SmsProviderMessage,
    ): SmsProviderReconcileResult {
        val now = clock.now()
        val delivery = providerDeliveryOutcome(message.status)
        val updatedParts = bundle.parts.map { part ->
            part.copy(
                sendOutcome = PartOutcome.ACCEPTED,
                deliveryOutcome = when (delivery) {
                    DeliveryOutcome.DELIVERED -> PartOutcome.ACCEPTED
                    DeliveryOutcome.FAILED -> PartOutcome.FAILED
                    else -> part.deliveryOutcome
                },
                sentAtEpochMillis = message.dateSentEpochMillis
                    .takeIf { it > 0 } ?: message.dateEpochMillis,
                deliveredAtEpochMillis = if (delivery in FINAL_DELIVERY_OUTCOMES) {
                    now.toEpochMilli()
                } else {
                    part.deliveredAtEpochMillis
                },
            )
        }
        val updatedAttempt = bundle.attempt.copy(
            sendOutcome = SendOutcome.SENT_TO_CARRIER,
            deliveryOutcome = delivery,
            failureCode = null,
            failureDetail = null,
            finishedAtEpochMillis = bundle.attempt.finishedAtEpochMillis
                ?: now.toEpochMilli(),
            providerMessageId = message.id,
            providerStatus = message.status,
            providerErrorCode = message.errorCode,
            providerObservedAtEpochMillis = now.toEpochMilli(),
        )
        val targetState = when (delivery) {
            DeliveryOutcome.DELIVERED -> OccurrenceState.DELIVERED
            DeliveryOutcome.FAILED -> OccurrenceState.DELIVERY_FAILED
            else -> OccurrenceState.SENT_TO_CARRIER
        }
        val outcomeChanged = occurrence.state != targetState ||
            occurrence.sendOutcome != SendOutcome.SENT_TO_CARRIER ||
            occurrence.deliveryOutcome != delivery
        val persistenceChanged = outcomeChanged ||
            updatedAttempt != bundle.attempt || updatedParts != bundle.parts
        if (persistenceChanged) {
            val expectedStates = if (delivery in FINAL_DELIVERY_OUTCOMES) {
                PROVIDER_FINAL_EXPECTED_STATES
            } else {
                PROVIDER_PENDING_EXPECTED_STATES
            }
            if (!executions.applyCorroboratedProjection(
                    CorroboratedAttemptProjection(
                        expectedOccurrenceStates = expectedStates,
                        expectedAttempt = bundle.attempt,
                        expectedParts = bundle.parts,
                        occurrenceState = targetState,
                        sendOutcome = SendOutcome.SENT_TO_CARRIER,
                        deliveryOutcome = delivery,
                        attempt = updatedAttempt,
                        parts = updatedParts,
                        event = providerEvent(
                            occurrence.id,
                            bundle.attempt.id,
                            "SMS_PROVIDER_RECONCILED",
                            message,
                            now,
                        ),
                    ),
                )
            ) return SmsProviderReconcileResult.Ignored("attempt_changed_during_reconciliation")
        }

        if (outcomeChanged) {
            publishTransition(occurrence, targetState)
        }
        if (targetState == OccurrenceState.SENT_TO_CARRIER) {
            workScheduler.onCarrierAccepted(
                bundle.attempt.id,
                bundle.attempt.deliveryDeadlineAtEpochMillis,
            )
        } else {
            workScheduler.onTerminal(bundle.attempt.id)
        }
        return if (delivery in FINAL_DELIVERY_OUTCOMES) {
            SmsProviderReconcileResult.DeliveryUpdated(
                bundle.attempt.id,
                message.id,
                delivery == DeliveryOutcome.DELIVERED,
            )
        } else {
            SmsProviderReconcileResult.ConfirmedSent(bundle.attempt.id, message.id)
        }
    }

    private suspend fun applyFailed(
        bundle: AttemptBundle,
        occurrence: OccurrenceRecord,
        message: SmsProviderMessage,
    ): SmsProviderReconcileResult {
        val now = clock.now()
        val updatedParts = bundle.parts.map { part ->
            part.copy(
                sendOutcome = PartOutcome.FAILED,
                deliveryOutcome = PartOutcome.PENDING,
                sentAtEpochMillis = now.toEpochMilli(),
            )
        }
        val updatedAttempt = bundle.attempt.copy(
            sendOutcome = SendOutcome.FAILED,
            deliveryOutcome = DeliveryOutcome.NOT_REQUESTED,
            failureCode = "SMS_PROVIDER_FAILED",
            failureDetail = "provider_error_${message.errorCode}",
            finishedAtEpochMillis = now.toEpochMilli(),
            providerMessageId = message.id,
            providerStatus = message.status,
            providerErrorCode = message.errorCode,
            providerObservedAtEpochMillis = now.toEpochMilli(),
        )
        if (!executions.applyCorroboratedProjection(
                CorroboratedAttemptProjection(
                    expectedOccurrenceStates = PROVIDER_FAILURE_EXPECTED_STATES,
                    expectedAttempt = bundle.attempt,
                    expectedParts = bundle.parts,
                    occurrenceState = OccurrenceState.FAILED_TERMINAL,
                    sendOutcome = SendOutcome.FAILED,
                    deliveryOutcome = DeliveryOutcome.NOT_REQUESTED,
                    attempt = updatedAttempt,
                    parts = updatedParts,
                    event = providerEvent(
                        occurrence.id,
                        bundle.attempt.id,
                        "SMS_PROVIDER_RECONCILED_FAILED",
                        message,
                        now,
                    ),
                ),
            )
        ) return SmsProviderReconcileResult.Ignored("attempt_no_longer_active")
        publishTransition(occurrence, OccurrenceState.FAILED_TERMINAL)
        workScheduler.onTerminal(bundle.attempt.id)
        return SmsProviderReconcileResult.ConfirmedFailed(bundle.attempt.id, message.id)
    }

    private suspend fun publishTransition(previous: OccurrenceRecord, state: OccurrenceState) {
        val userSettings = settings.get()
        if (!userSettings.notificationsEnabled) return
        if (
            state in PROVIDER_SENT_STATES &&
            previous.sendOutcome != SendOutcome.SENT_TO_CARRIER &&
            userSettings.sendResultNotificationsEnabled
        ) {
            notifications.publish(
                AppNotification(
                    notificationId(previous.id, "sent"),
                    NotificationKind.SEND_SUCCEEDED,
                    "Message sent",
                    "Android recorded the message as sent.",
                    previous.id,
                ),
            )
        }
        when (state) {
            OccurrenceState.DELIVERED,
            OccurrenceState.DELIVERY_FAILED,
            -> if (previous.state != state && userSettings.deliveryNotificationsEnabled) {
                val delivered = state == OccurrenceState.DELIVERED
                notifications.publish(
                    AppNotification(
                        notificationId(previous.id, "delivery"),
                        if (delivered) NotificationKind.SEND_SUCCEEDED else NotificationKind.SEND_FAILED,
                        if (delivered) "Message delivered" else "Delivery failed",
                        if (delivered) "The carrier reported delivery." else "The carrier reported a delivery failure.",
                        previous.id,
                    ),
                )
            }
            OccurrenceState.FAILED_TERMINAL -> if (
                previous.state != OccurrenceState.FAILED_TERMINAL &&
                userSettings.sendResultNotificationsEnabled
            ) notifications.publish(
                AppNotification(
                    notificationId(previous.id, "failed"),
                    NotificationKind.SEND_FAILED,
                    "Message not sent",
                    "Android recorded a failed SMS attempt.",
                    previous.id,
                ),
            )
            else -> Unit
        }
    }

    private suspend fun appendEvent(
        occurrenceId: String,
        attemptId: String,
        type: String,
        message: SmsProviderMessage?,
        ambiguousCount: Int? = null,
    ) {
        val now = clock.now()
        val detail = when {
            message != null -> "{\"providerMessageId\":${message.id},\"type\":${message.type}," +
                "\"status\":${message.status},\"errorCode\":${message.errorCode}}"
            ambiguousCount != null -> "{\"candidateCount\":$ambiguousCount}"
            else -> null
        }
        occurrences.appendEvent(
            OccurrenceEventRecord(
                id = ids.newId(),
                occurrenceId = occurrenceId,
                attemptId = attemptId,
                type = type,
                detailJson = detail,
                happenedAtEpochMillis = now.toEpochMilli(),
                createdAtEpochMillis = now.toEpochMilli(),
            ),
        )
    }

    private fun providerEvent(
        occurrenceId: String,
        attemptId: String,
        type: String,
        message: SmsProviderMessage,
        now: Instant,
    ) = OccurrenceEventRecord(
        id = ids.newId(),
        occurrenceId = occurrenceId,
        attemptId = attemptId,
        type = type,
        detailJson = "{\"providerMessageId\":${message.id},\"type\":${message.type}," +
            "\"status\":${message.status},\"errorCode\":${message.errorCode}}",
        happenedAtEpochMillis = now.toEpochMilli(),
        createdAtEpochMillis = now.toEpochMilli(),
    )

    companion object {
        private val MATCH_EARLY: Duration = Duration.ofMinutes(2)
        private val MATCH_LATE: Duration = Duration.ofMinutes(10)
        private val FAST_PROVIDER_POLLS = listOf(
            Duration.ofMillis(150),
            Duration.ofMillis(350),
            Duration.ofMillis(750),
            Duration.ofMillis(1_500),
        )
        private val RECONCILABLE_STATES = setOf(
            OccurrenceState.SENDING,
            OccurrenceState.SENT_TO_CARRIER,
            OccurrenceState.PARTIAL_AMBIGUOUS,
            OccurrenceState.DELIVERY_UNAVAILABLE,
        )
        private val FINAL_DELIVERY_OUTCOMES = setOf(
            DeliveryOutcome.DELIVERED,
            DeliveryOutcome.FAILED,
        )
        private val PROVIDER_SENT_STATES = setOf(
            OccurrenceState.SENT_TO_CARRIER,
            OccurrenceState.DELIVERED,
            OccurrenceState.DELIVERY_FAILED,
        )
        private val PROVIDER_PENDING_EXPECTED_STATES = setOf(
            OccurrenceState.SENDING,
            OccurrenceState.SENT_TO_CARRIER,
            OccurrenceState.PARTIAL_AMBIGUOUS,
        )
        private val PROVIDER_FINAL_EXPECTED_STATES = PROVIDER_PENDING_EXPECTED_STATES +
            OccurrenceState.DELIVERY_UNAVAILABLE
        private val PROVIDER_FAILURE_EXPECTED_STATES = setOf(
            OccurrenceState.SENDING,
            OccurrenceState.PARTIAL_AMBIGUOUS,
        )

        internal fun providerDeliveryOutcome(status: Int): DeliveryOutcome = when {
            status == Telephony.Sms.STATUS_NONE -> DeliveryOutcome.PENDING
            status < Telephony.Sms.STATUS_PENDING -> DeliveryOutcome.DELIVERED
            status < Telephony.Sms.STATUS_FAILED -> DeliveryOutcome.PENDING
            else -> DeliveryOutcome.FAILED
        }
    }

    private suspend fun reconcilableAttemptIds(): List<String> = schedules.listAll(500)
        .asSequence()
        .flatMap { it.occurrences.asSequence() }
        .filter { it.state in RECONCILABLE_STATES }
        .mapNotNull(OccurrenceRecord::activeAttemptId)
        .distinct()
        .toList()
}

private fun SmsProviderReconcileResult.isConclusive(): Boolean = when (this) {
    is SmsProviderReconcileResult.ConfirmedSent,
    is SmsProviderReconcileResult.DeliveryUpdated,
    is SmsProviderReconcileResult.ConfirmedFailed,
    -> true
    else -> false
}

private fun SmsProviderMessage.effectiveTimestampEpochMillis(): Long =
    dateSentEpochMillis.takeIf { it > 0 } ?: dateEpochMillis
