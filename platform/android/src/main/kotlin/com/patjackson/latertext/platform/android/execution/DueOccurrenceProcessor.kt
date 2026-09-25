package com.patjackson.latertext.platform.android.execution

import com.patjackson.latertext.core.domain.reducer.OccurrenceEvent
import com.patjackson.latertext.core.domain.reducer.OccurrenceReducer
import com.patjackson.latertext.core.domain.usecase.DueAction
import com.patjackson.latertext.core.domain.usecase.DueOccurrenceContext
import com.patjackson.latertext.core.domain.usecase.DueOccurrenceEvaluator
import com.patjackson.latertext.core.model.AttemptId
import com.patjackson.latertext.core.model.MissedPolicy as DomainMissedPolicy
import com.patjackson.latertext.data.api.AttemptBundle
import com.patjackson.latertext.data.api.AttemptPartRecord
import com.patjackson.latertext.data.api.AttemptRepository
import com.patjackson.latertext.data.api.AttachmentRepository
import com.patjackson.latertext.data.api.AttachmentState
import com.patjackson.latertext.data.api.CallbackKind
import com.patjackson.latertext.data.api.CallbackTokenRecord
import com.patjackson.latertext.data.api.CallbackTokenState
import com.patjackson.latertext.data.api.ClaimedOccurrence
import com.patjackson.latertext.data.api.DeliveryOutcome
import com.patjackson.latertext.data.api.ExecutionClaimResult
import com.patjackson.latertext.data.api.MissedPolicy
import com.patjackson.latertext.data.api.OccurrenceEventRecord
import com.patjackson.latertext.data.api.OccurrenceExecutionRepository
import com.patjackson.latertext.data.api.OccurrenceRepository
import com.patjackson.latertext.data.api.OccurrenceState
import com.patjackson.latertext.data.api.PartOutcome
import com.patjackson.latertext.data.api.ScheduleState
import com.patjackson.latertext.data.api.SendAttemptRecord
import com.patjackson.latertext.data.api.SendOutcome
import com.patjackson.latertext.data.api.SettingsRepository
import com.patjackson.latertext.data.api.TransportMode
import com.patjackson.latertext.platform.api.AppClock
import com.patjackson.latertext.platform.api.AppNotification
import com.patjackson.latertext.platform.api.AutomaticSmsGateway
import com.patjackson.latertext.platform.api.NotificationKind
import com.patjackson.latertext.platform.api.NotificationPublisher
import com.patjackson.latertext.platform.api.ReadinessGateway
import com.patjackson.latertext.platform.api.MmsSendRequest
import com.patjackson.latertext.platform.api.SmsSendRequest
import com.patjackson.latertext.platform.api.SubscriptionGateway
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Duration
import java.time.Instant

sealed interface DueProcessResult {
    data class AwaitingSmsCallbacks(val attemptId: String, val partCount: Int) : DueProcessResult
    data class ReadyForUser(val notificationPublished: Boolean) : DueProcessResult
    data class Deferred(val retryAt: Instant) : DueProcessResult
    data class Terminal(val state: OccurrenceState, val reason: String) : DueProcessResult
    data class Ignored(val reason: String) : DueProcessResult
}

/** Executes one immutable alarm generation. Every externally visible decision is persisted first. */
class DueOccurrenceProcessor(
    private val occurrences: OccurrenceRepository,
    private val executions: OccurrenceExecutionRepository,
    private val attempts: AttemptRepository,
    private val attachments: AttachmentRepository,
    private val settings: SettingsRepository,
    private val readiness: ReadinessGateway,
    private val subscriptions: SubscriptionGateway,
    private val automaticSms: AutomaticSmsGateway,
    private val notifications: NotificationPublisher,
    private val clock: AppClock,
    private val ids: ExecutionIdFactory = UuidExecutionIdFactory(),
    private val evaluator: DueOccurrenceEvaluator = DueOccurrenceEvaluator(),
    private val occurrenceReducer: OccurrenceReducer = OccurrenceReducer(),
    private val claimLease: Duration = Duration.ofMinutes(5),
    private val deliveryTimeout: Duration = Duration.ofHours(24),
) {
    suspend fun process(occurrenceId: String, generation: Long): DueProcessResult {
        require(occurrenceId.isNotBlank())
        require(generation >= 0)
        val now = clock.now()
        val owner = "alarm:$occurrenceId:$generation"
        return when (
            val result = executions.claimExpected(
                occurrenceId = occurrenceId,
                generation = generation,
                owner = owner,
                nowEpochMillis = now.toEpochMilli(),
                leaseUntilEpochMillis = now.plus(claimLease).toEpochMilli(),
            )
        ) {
            is ExecutionClaimResult.Claimed -> processClaimed(result.value, owner, now)
            is ExecutionClaimResult.StaleGeneration -> DueProcessResult.Ignored("stale_alarm_generation")
            is ExecutionClaimResult.Paused -> DueProcessResult.Ignored("schedule_paused")
            is ExecutionClaimResult.Expired -> terminalizeExpired(result, now)
            is ExecutionClaimResult.Rejected -> DueProcessResult.Ignored(result.reason)
        }
    }

    private suspend fun processClaimed(
        claimed: ClaimedOccurrence,
        owner: String,
        now: Instant,
    ): DueProcessResult {
        val occurrence = claimed.occurrence
        val userSettings = settings.get()
        val ready = readiness.snapshot()
        val actionNotificationAvailable = userSettings.notificationsEnabled &&
            userSettings.actionRequiredNotificationsEnabled && ready.canPostNotifications
        val dueAction = evaluator.evaluate(
            DueOccurrenceContext(
                targetAt = Instant.ofEpochMilli(occurrence.targetAtEpochMillis),
                deadlineAt = Instant.ofEpochMilli(occurrence.deadlineAtEpochMillis),
                now = now,
                schedulePaused = claimed.schedule.state == ScheduleState.PAUSED,
                globallyPaused = userSettings.globalPaused,
                missedPolicy = claimed.rule.missedPolicy.toDomain(),
                actionNotificationAvailable = actionNotificationAvailable,
            ),
        )
        val attemptCount = executions.attemptCount(occurrence.id)
        val snapshot = occurrence.toDomainSnapshot(attemptCount)
        val eventAt = monotonicEventTime(occurrence, now)

        return when (dueAction) {
            DueAction.WAIT -> {
                val deferredUntil = if (userSettings.globalPaused) {
                    Instant.ofEpochMilli(occurrence.deadlineAtEpochMillis)
                } else {
                    Instant.ofEpochMilli(occurrence.targetAtEpochMillis)
                }
                if (!executions.transitionClaimed(
                    occurrence.id,
                    owner,
                    OccurrenceState.RETRY_WAIT,
                    retryAtEpochMillis = deferredUntil.toEpochMilli(),
                    nowEpochMillis = eventAt.toEpochMilli(),
                )) return DueProcessResult.Ignored("claim_lost_before_deferral")
                DueProcessResult.Deferred(deferredUntil)
            }
            DueAction.SKIP_PAUSED -> transitionTerminal(
                claimed,
                owner,
                occurrenceReducer.reduce(snapshot, OccurrenceEvent.SkipPaused(eventAt)),
                eventAt,
                "skipped_while_paused",
            )
            DueAction.MARK_MISSED,
            DueAction.ASK_ME_UNAVAILABLE,
            -> transitionTerminal(
                claimed,
                owner,
                occurrenceReducer.reduce(snapshot, OccurrenceEvent.MarkMissed(eventAt)),
                eventAt,
                if (dueAction == DueAction.ASK_ME_UNAVAILABLE) {
                    "ask_me_notification_unavailable"
                } else {
                    "missed_deadline"
                },
            )
            DueAction.REQUIRE_USER -> prepareAssisted(claimed, owner, snapshot, eventAt, true)
            DueAction.SEND_NOW -> when (claimed.schedule.transportMode) {
                TransportMode.AUTOMATIC_SMS -> sendAutomaticSms(claimed, owner, snapshot, eventAt)
                TransportMode.AUTOMATIC_MMS -> sendAutomaticMms(claimed, owner, snapshot, eventAt)
                TransportMode.ASSISTED_TEXT,
                TransportMode.ASSISTED_MEDIA,
                -> prepareAssisted(claimed, owner, snapshot, eventAt, actionNotificationAvailable)
            }
        }
    }

    private suspend fun sendAutomaticSms(
        claimed: ClaimedOccurrence,
        owner: String,
        snapshot: com.patjackson.latertext.core.model.OccurrenceSnapshot,
        now: Instant,
    ): DueProcessResult {
        val currentSettings = settings.get()
        val readinessSnapshot = readiness.snapshot()
        if (!readinessSnapshot.canSendSms) {
            return failBeforeAttempt(claimed, owner, now, "sms_permission_or_service_unavailable")
        }
        val subscriptionId = resolveSubscription(currentSettings.preferredSubscriptionId)
            ?: return failBeforeAttempt(claimed, owner, now, "sms_subscription_unavailable")
        val parts = runCatching {
            automaticSms.divideMessage(claimed.content.text, subscriptionId)
        }.getOrElse {
            return failBeforeAttempt(claimed, owner, now, "message_segmentation_failed")
        }
        if (parts.isEmpty()) {
            return failBeforeAttempt(claimed, owner, now, "message_has_no_sms_parts")
        }

        val attemptId = ids.newId()
        val attemptNumber = executions.attemptCount(claimed.occurrence.id) + 1
        val deliveryDeadline = now.plus(deliveryTimeout)
        val bundle = newAttemptBundle(
            attemptId = attemptId,
            occurrenceId = claimed.occurrence.id,
            attemptNumber = attemptNumber,
            partCount = parts.size,
            subscriptionId = subscriptionId,
            startedAt = now,
            deliveryDeadline = deliveryDeadline,
            transportMode = TransportMode.AUTOMATIC_SMS,
            requestDeliveryCallbacks = true,
        )
        attempts.create(bundle)

        val sending = occurrenceReducer.reduce(
            snapshot,
            OccurrenceEvent.BeginAutomaticAttempt(AttemptId(attemptId), now),
        )
        if (
            !executions.transitionClaimed(
                occurrenceId = claimed.occurrence.id,
                owner = owner,
                newState = sending.state.toRecord(),
                sendOutcome = sending.sendOutcome.toRecord(),
                deliveryOutcome = sending.deliveryOutcome.toRecord(),
                activeAttemptId = attemptId,
                nowEpochMillis = now.toEpochMilli(),
            )
        ) return DueProcessResult.Ignored("claim_lost_before_enqueue")

        val enqueue = runCatching {
            automaticSms.enqueue(
                SmsSendRequest(
                    attemptId = attemptId,
                    recipientAddress = claimed.recipient.normalizedAddress,
                    body = claimed.content.text,
                    subscriptionId = subscriptionId,
                    requestDeliveryReport = true,
                ),
            )
        }.getOrElse { error ->
            return failEnqueue(claimed, bundle, "enqueue_exception:${error.javaClass.simpleName}", now)
        }
        if (!enqueue.acceptedByPlatform) {
            return failEnqueue(claimed, bundle, enqueue.immediateError ?: "enqueue_rejected", now)
        }
        if (enqueue.partCount != parts.size) {
            return failEnqueue(claimed, bundle, "enqueue_part_count_mismatch", now, ambiguous = true)
        }
        appendEvent(claimed.occurrence.id, attemptId, "SMS_ENQUEUED", now)
        return DueProcessResult.AwaitingSmsCallbacks(attemptId, parts.size)
    }

    private suspend fun sendAutomaticMms(
        claimed: ClaimedOccurrence,
        owner: String,
        snapshot: com.patjackson.latertext.core.model.OccurrenceSnapshot,
        now: Instant,
    ): DueProcessResult {
        val readinessSnapshot = readiness.snapshot()
        if (!readinessSnapshot.canSendSms) {
            return failBeforeAttempt(claimed, owner, now, "mms_permission_or_service_unavailable")
        }
        val currentSettings = settings.get()
        val subscriptionId = resolveSubscription(currentSettings.preferredSubscriptionId)
            ?: return failBeforeAttempt(claimed, owner, now, "mms_subscription_unavailable")
        val attachment = claimed.attachment
            ?: return failBeforeAttempt(claimed, owner, now, "mms_attachment_missing")
        if (attachment.state != AttachmentState.READY) {
            return failBeforeAttempt(claimed, owner, now, "mms_attachment_not_ready")
        }
        val capability = runCatching { automaticSms.mmsCapability(subscriptionId) }.getOrElse {
            return failBeforeAttempt(claimed, owner, now, "mms_carrier_config_unavailable")
        }
        if (!capability.acceptsText(claimed.content.text.toByteArray(Charsets.UTF_8).size)) {
            return failBeforeAttempt(claimed, owner, now, "mms_text_exceeds_carrier_limit")
        }
        if (!capability.canPrepare(
                attachment.mimeType,
                attachment.byteCount,
                attachment.widthPixels,
                attachment.heightPixels,
                attachment.isAnimated,
            )
        ) {
            return failBeforeAttempt(
                claimed,
                owner,
                now,
                capability.reason ?: "mms_attachment_exceeds_carrier_limits",
            )
        }
        val attachmentBytes = runCatching {
            attachments.open(attachment.id).use { input ->
                input.readBounded(MAX_MMS_SOURCE_BYTES)
            }
        }.getOrElse {
            return failBeforeAttempt(claimed, owner, now, "mms_attachment_read_failed")
        }

        val attemptId = ids.newId()
        val bundle = newAttemptBundle(
            attemptId = attemptId,
            occurrenceId = claimed.occurrence.id,
            attemptNumber = executions.attemptCount(claimed.occurrence.id) + 1,
            partCount = 1,
            subscriptionId = subscriptionId,
            startedAt = now,
            deliveryDeadline = null,
            transportMode = TransportMode.AUTOMATIC_MMS,
            requestDeliveryCallbacks = false,
        )
        attempts.create(bundle)
        val sending = occurrenceReducer.reduce(
            snapshot,
            OccurrenceEvent.BeginAutomaticAttempt(AttemptId(attemptId), now),
        )
        if (!executions.transitionClaimed(
                occurrenceId = claimed.occurrence.id,
                owner = owner,
                newState = sending.state.toRecord(),
                sendOutcome = sending.sendOutcome.toRecord(),
                deliveryOutcome = DeliveryOutcome.NOT_REQUESTED,
                activeAttemptId = attemptId,
                nowEpochMillis = now.toEpochMilli(),
            )
        ) return DueProcessResult.Ignored("claim_lost_before_mms_enqueue")

        val enqueue = runCatching {
            automaticSms.enqueueMms(
                MmsSendRequest(
                    attemptId = attemptId,
                    recipientAddress = claimed.recipient.normalizedAddress,
                    body = claimed.content.text,
                    attachmentBytes = attachmentBytes,
                    attachmentMimeType = attachment.mimeType,
                    attachmentIsAnimated = attachment.isAnimated,
                    attachmentWidthPixels = attachment.widthPixels,
                    attachmentHeightPixels = attachment.heightPixels,
                    subscriptionId = subscriptionId,
                ),
            )
        }.getOrElse { error ->
            return failEnqueue(claimed, bundle, "mms_enqueue_exception:${error.javaClass.simpleName}", now)
        }
        if (!enqueue.acceptedByPlatform) {
            return failEnqueue(claimed, bundle, enqueue.immediateError ?: "mms_enqueue_rejected", now)
        }
        appendEvent(claimed.occurrence.id, attemptId, "MMS_ENQUEUED", now)
        return DueProcessResult.AwaitingSmsCallbacks(attemptId, 1)
    }

    private suspend fun prepareAssisted(
        claimed: ClaimedOccurrence,
        owner: String,
        snapshot: com.patjackson.latertext.core.model.OccurrenceSnapshot,
        now: Instant,
        mayNotify: Boolean,
    ): DueProcessResult {
        val next = occurrenceReducer.reduce(snapshot, OccurrenceEvent.BeginAssistedReview(now))
        if (!executions.transitionClaimed(
                claimed.occurrence.id,
                owner,
                next.state.toRecord(),
                sendOutcome = next.sendOutcome.toRecord(),
                deliveryOutcome = next.deliveryOutcome.toRecord(),
                nowEpochMillis = now.toEpochMilli(),
            )
        ) return DueProcessResult.Ignored("claim_lost_before_assisted_review")
        if (mayNotify) {
            notifications.publish(
                AppNotification(
                    id = notificationId(claimed.occurrence.id, "action"),
                    kind = NotificationKind.ACTION_REQUIRED,
                    title = "Message ready to send",
                    body = "Review the message for ${claimed.recipient.displayName ?: claimed.recipient.rawAddress}.",
                    occurrenceId = claimed.occurrence.id,
                ),
            )
        }
        appendEvent(claimed.occurrence.id, null, "ACTION_REQUIRED", now)
        return DueProcessResult.ReadyForUser(mayNotify)
    }

    private suspend fun failBeforeAttempt(
        claimed: ClaimedOccurrence,
        owner: String,
        now: Instant,
        reason: String,
    ): DueProcessResult {
        if (!executions.transitionClaimed(
            claimed.occurrence.id,
            owner,
            OccurrenceState.FAILED_TERMINAL,
            sendOutcome = SendOutcome.FAILED,
            deliveryOutcome = DeliveryOutcome.NOT_REQUESTED,
            nowEpochMillis = now.toEpochMilli(),
        )) return DueProcessResult.Ignored("claim_lost_before_failure")
        publishFailure(claimed, reason)
        appendEvent(claimed.occurrence.id, null, "SEND_FAILED_TERMINAL", now)
        return DueProcessResult.Terminal(OccurrenceState.FAILED_TERMINAL, reason)
    }

    private suspend fun failEnqueue(
        claimed: ClaimedOccurrence,
        bundle: AttemptBundle,
        reason: String,
        now: Instant,
        ambiguous: Boolean = false,
    ): DueProcessResult {
        attempts.updateAttempt(
            bundle.attempt.copy(
                sendOutcome = if (ambiguous) SendOutcome.PARTIAL_OR_AMBIGUOUS else SendOutcome.FAILED,
                failureCode = if (ambiguous) "AMBIGUOUS_ENQUEUE" else "ENQUEUE_REJECTED",
                failureDetail = reason,
                finishedAtEpochMillis = now.toEpochMilli(),
            ),
        )
        val state = if (ambiguous) OccurrenceState.PARTIAL_AMBIGUOUS else OccurrenceState.FAILED_TERMINAL
        if (!executions.applyAttemptProjection(
            occurrenceId = claimed.occurrence.id,
            expectedAttemptId = bundle.attempt.id,
            newState = state,
            sendOutcome = if (ambiguous) SendOutcome.PARTIAL_OR_AMBIGUOUS else SendOutcome.FAILED,
            deliveryOutcome = DeliveryOutcome.NOT_REQUESTED,
            nowEpochMillis = now.toEpochMilli(),
        )) return DueProcessResult.Ignored("attempt_no_longer_active")
        publishFailure(claimed, reason)
        appendEvent(claimed.occurrence.id, bundle.attempt.id, "SMS_ENQUEUE_FAILED", now)
        return DueProcessResult.Terminal(state, reason)
    }

    private suspend fun transitionTerminal(
        claimed: ClaimedOccurrence,
        owner: String,
        next: com.patjackson.latertext.core.model.OccurrenceSnapshot,
        now: Instant,
        reason: String,
    ): DueProcessResult {
        if (!executions.transitionClaimed(
            claimed.occurrence.id,
            owner,
            next.state.toRecord(),
            sendOutcome = next.sendOutcome.toRecord(),
            deliveryOutcome = next.deliveryOutcome.toRecord(),
            nowEpochMillis = now.toEpochMilli(),
        )) return DueProcessResult.Ignored("claim_lost_before_terminal_transition")
        appendEvent(claimed.occurrence.id, null, reason.uppercase(), now)
        return DueProcessResult.Terminal(next.state.toRecord(), reason)
    }

    private suspend fun terminalizeExpired(
        result: ExecutionClaimResult.Expired,
        now: Instant,
    ): DueProcessResult {
        val target = if (result.scheduleState == ScheduleState.PAUSED) {
            OccurrenceState.SKIPPED_PAUSED
        } else {
            OccurrenceState.MISSED
        }
        val applied = occurrences.compareAndSetState(
            result.occurrence.id,
            setOf(
                OccurrenceState.PLANNED,
                OccurrenceState.ARMED,
                OccurrenceState.DUE,
                OccurrenceState.CLAIMED,
                OccurrenceState.RETRY_WAIT,
            ),
            target,
            now.toEpochMilli(),
        )
        if (!applied) return DueProcessResult.Ignored("expired_occurrence_already_transitioned")
        appendEvent(result.occurrence.id, null, target.name, now)
        return DueProcessResult.Terminal(target, "deadline_expired")
    }

    private fun resolveSubscription(preferredId: Int?): Int? {
        if (preferredId != null) return preferredId.takeIf(subscriptions::isActive)
        val defaultId = subscriptions.defaultSmsSubscriptionId()
        if (defaultId != null && subscriptions.isActive(defaultId)) return defaultId
        return subscriptions.activeSubscriptions().singleOrNull()?.subscriptionId
    }

    private fun newAttemptBundle(
        attemptId: String,
        occurrenceId: String,
        attemptNumber: Int,
        partCount: Int,
        subscriptionId: Int,
        startedAt: Instant,
        deliveryDeadline: Instant?,
        transportMode: TransportMode,
        requestDeliveryCallbacks: Boolean,
    ): AttemptBundle {
        val parts = List(partCount) { index ->
            AttemptPartRecord(
                attemptId = attemptId,
                partIndex = index,
                totalParts = partCount,
                sendOutcome = PartOutcome.PENDING,
                deliveryOutcome = PartOutcome.PENDING,
                sentResultCode = null,
                deliveryResultCode = null,
                sentAtEpochMillis = null,
                deliveredAtEpochMillis = null,
            )
        }
        val expiry = (deliveryDeadline ?: startedAt).plus(Duration.ofHours(24)).toEpochMilli()
        val tokens = parts.flatMap { part ->
            val kinds = if (requestDeliveryCallbacks) CallbackKind.entries else listOf(CallbackKind.SENT)
            kinds.map { kind ->
                CallbackTokenRecord(
                    token = SmsCallbackToken.create(attemptId, part.partIndex, kind),
                    attemptId = attemptId,
                    partIndex = part.partIndex,
                    kind = kind,
                    state = CallbackTokenState.READY,
                    createdAtEpochMillis = startedAt.toEpochMilli(),
                    expiresAtEpochMillis = expiry,
                    consumedAtEpochMillis = null,
                )
            }
        }
        return AttemptBundle(
            attempt = SendAttemptRecord(
                id = attemptId,
                occurrenceId = occurrenceId,
                attemptNumber = attemptNumber,
                transportMode = transportMode,
                sendOutcome = SendOutcome.PENDING,
                deliveryOutcome = if (requestDeliveryCallbacks) {
                    DeliveryOutcome.PENDING
                } else {
                    DeliveryOutcome.NOT_REQUESTED
                },
                failureCode = null,
                failureDetail = null,
                startedAtEpochMillis = startedAt.toEpochMilli(),
                finishedAtEpochMillis = null,
                deliveryDeadlineAtEpochMillis = deliveryDeadline?.toEpochMilli(),
                subscriptionId = subscriptionId,
            ),
            parts = parts,
            callbackTokens = tokens,
        )
    }

    private suspend fun publishFailure(claimed: ClaimedOccurrence, reason: String) {
        val userSettings = settings.get()
        if (
            !userSettings.notificationsEnabled ||
            !userSettings.sendResultNotificationsEnabled ||
            !readiness.snapshot().canPostNotifications
        ) return
        notifications.publish(
            AppNotification(
                id = notificationId(claimed.occurrence.id, "failed"),
                kind = NotificationKind.SEND_FAILED,
                title = "Message not sent",
                body = "LaterText could not send the message (${reason.replace('_', ' ')}).",
                occurrenceId = claimed.occurrence.id,
            ),
        )
    }

    private suspend fun appendEvent(
        occurrenceId: String,
        attemptId: String?,
        type: String,
        at: Instant,
    ) {
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

private const val MAX_MMS_SOURCE_BYTES = 25 * 1_024 * 1_024

private fun InputStream.readBounded(maxBytes: Int): ByteArray {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1_024))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "Attachment grew beyond the carrier MMS limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun MissedPolicy.toDomain(): DomainMissedPolicy = when (this) {
    MissedPolicy.SEND_AS_SOON_AS_POSSIBLE -> DomainMissedPolicy.SEND_AS_SOON_AS_POSSIBLE
    MissedPolicy.ASK_ME -> DomainMissedPolicy.ASK_ME
    MissedPolicy.SKIP -> DomainMissedPolicy.SKIP
}
