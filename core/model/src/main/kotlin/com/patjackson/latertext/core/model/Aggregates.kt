package com.patjackson.latertext.core.model

import java.time.Instant

data class ScheduleSnapshot(
    val id: ScheduleId,
    val state: ScheduleState = ScheduleState.ACTIVE,
    val pausedAt: Instant? = null,
    val attentionReason: ScheduleAttentionReason? = null,
    val stateBeforeAttention: ScheduleState? = null,
    val updatedAt: Instant,
)

data class OccurrenceSnapshot(
    val id: OccurrenceId,
    val scheduleId: ScheduleId,
    /** Null only for a manual Send now occurrence, which never consumes a recurrence slot. */
    val logicalKey: LogicalOccurrenceKey?,
    val trigger: OccurrenceTrigger = OccurrenceTrigger.SCHEDULED,
    val state: OccurrenceState = OccurrenceState.PLANNED,
    val alarmGeneration: Long = 0,
    val attemptCount: Int = 0,
    val activeAttemptId: AttemptId? = null,
    val claimToken: ClaimToken? = null,
    val leaseUntil: Instant? = null,
    val nextActionAt: Instant? = null,
    val sendOutcome: SendOutcome = SendOutcome.NOT_STARTED,
    val deliveryOutcome: DeliveryOutcome = DeliveryOutcome.NOT_REQUESTED,
    val updatedAt: Instant,
) {
    init {
        require(alarmGeneration >= 0)
        require(attemptCount >= 0)
        require(logicalKey == null || logicalKey.scheduleId == scheduleId)
        require((trigger == OccurrenceTrigger.MANUAL) == (logicalKey == null)) {
            "Only manual occurrences omit a logical recurrence key"
        }
    }
}

data class SentPartResult(
    val partIndex: Int,
    val outcome: PartSubmissionOutcome,
    val failureCode: SmsFailureCode? = null,
    val callbackAt: Instant,
) {
    init {
        require(partIndex >= 0)
        require((outcome == PartSubmissionOutcome.ACCEPTED) == (failureCode == null)) {
            "Accepted parts cannot have a failure code and failed parts must have one"
        }
    }
}

data class DeliveryPartResult(
    val partIndex: Int,
    val outcome: DeliveryPartOutcome,
    val callbackAt: Instant,
) {
    init { require(partIndex >= 0) }
}

data class AttemptSnapshot(
    val id: AttemptId,
    val occurrenceId: OccurrenceId,
    val attemptNumber: Int,
    val totalParts: Int,
    val state: AttemptState = AttemptState.CREATED,
    val sentParts: Map<Int, SentPartResult> = emptyMap(),
    val deliveryParts: Map<Int, DeliveryPartResult> = emptyMap(),
    val deliveryOutcome: DeliveryOutcome = DeliveryOutcome.NOT_REQUESTED,
    val startedAt: Instant,
    val completedAt: Instant? = null,
) {
    init {
        require(attemptNumber > 0)
        require(totalParts > 0)
        require(sentParts.keys.all { it in 0 until totalParts })
        require(deliveryParts.keys.all { it in 0 until totalParts })
    }

    val acceptedPartCount: Int
        get() = sentParts.values.count { it.outcome == PartSubmissionOutcome.ACCEPTED }
}
