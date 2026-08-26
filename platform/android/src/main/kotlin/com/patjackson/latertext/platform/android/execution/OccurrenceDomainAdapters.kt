package com.patjackson.latertext.platform.android.execution

import com.patjackson.latertext.core.model.AttemptId
import com.patjackson.latertext.core.model.ClaimToken
import com.patjackson.latertext.core.model.DeliveryOutcome as DomainDeliveryOutcome
import com.patjackson.latertext.core.model.LogicalOccurrenceKey
import com.patjackson.latertext.core.model.OccurrenceId
import com.patjackson.latertext.core.model.OccurrenceSnapshot
import com.patjackson.latertext.core.model.OccurrenceState as DomainOccurrenceState
import com.patjackson.latertext.core.model.OccurrenceTrigger
import com.patjackson.latertext.core.model.RuleRevisionId
import com.patjackson.latertext.core.model.ScheduleId
import com.patjackson.latertext.core.model.SendOutcome as DomainSendOutcome
import com.patjackson.latertext.data.api.DeliveryOutcome
import com.patjackson.latertext.data.api.OccurrenceRecord
import com.patjackson.latertext.data.api.OccurrenceState
import com.patjackson.latertext.data.api.SendOutcome
import java.time.Instant

internal fun OccurrenceRecord.toDomainSnapshot(attemptCount: Int): OccurrenceSnapshot =
    OccurrenceSnapshot(
        id = OccurrenceId(id),
        scheduleId = ScheduleId(scheduleId),
        logicalKey = LogicalOccurrenceKey(
            scheduleId = ScheduleId(scheduleId),
            ruleRevisionId = RuleRevisionId(ruleRevisionId),
            sequence = logicalRecurrenceKey.substringAfterLast(':').toLongOrNull() ?: 0L,
        ),
        trigger = OccurrenceTrigger.SCHEDULED,
        state = state.toDomain(),
        alarmGeneration = alarmGeneration,
        attemptCount = attemptCount,
        activeAttemptId = activeAttemptId?.let(::AttemptId),
        claimToken = claimOwner?.let(::ClaimToken),
        leaseUntil = claimUntilEpochMillis?.let(Instant::ofEpochMilli),
        nextActionAt = retryAtEpochMillis?.let(Instant::ofEpochMilli),
        sendOutcome = sendOutcome.toDomain(),
        deliveryOutcome = deliveryOutcome.toDomain(),
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    )

internal fun DomainOccurrenceState.toRecord(): OccurrenceState = when (this) {
    DomainOccurrenceState.PLANNED -> OccurrenceState.PLANNED
    DomainOccurrenceState.ARMED -> OccurrenceState.ARMED
    DomainOccurrenceState.DUE -> OccurrenceState.DUE
    DomainOccurrenceState.CLAIMED -> OccurrenceState.CLAIMED
    DomainOccurrenceState.SENDING -> OccurrenceState.SENDING
    DomainOccurrenceState.RETRY_WAIT -> OccurrenceState.RETRY_WAIT
    DomainOccurrenceState.READY_FOR_USER -> OccurrenceState.READY_FOR_USER
    DomainOccurrenceState.OPENED_IN_LATER_TEXT -> OccurrenceState.OPENED_IN_LATER_TEXT
    DomainOccurrenceState.SHARED_TO_MESSAGING_APP -> OccurrenceState.SHARED_TO_MESSAGING_APP
    DomainOccurrenceState.SENT_TO_CARRIER -> OccurrenceState.SENT_TO_CARRIER
    DomainOccurrenceState.DELIVERED -> OccurrenceState.DELIVERED
    DomainOccurrenceState.DELIVERY_FAILED -> OccurrenceState.DELIVERY_FAILED
    DomainOccurrenceState.DELIVERY_UNAVAILABLE -> OccurrenceState.DELIVERY_UNAVAILABLE
    DomainOccurrenceState.FAILED_TERMINAL -> OccurrenceState.FAILED_TERMINAL
    DomainOccurrenceState.PARTIAL_AMBIGUOUS -> OccurrenceState.PARTIAL_AMBIGUOUS
    DomainOccurrenceState.EXPIRED -> OccurrenceState.EXPIRED
    DomainOccurrenceState.SKIPPED_PAUSED -> OccurrenceState.SKIPPED_PAUSED
    DomainOccurrenceState.MISSED -> OccurrenceState.MISSED
    DomainOccurrenceState.CANCELLED -> OccurrenceState.CANCELLED
}

internal fun DomainSendOutcome.toRecord(): SendOutcome = when (this) {
    DomainSendOutcome.NOT_STARTED -> SendOutcome.NOT_STARTED
    DomainSendOutcome.PENDING -> SendOutcome.PENDING
    DomainSendOutcome.SENT_TO_CARRIER -> SendOutcome.SENT_TO_CARRIER
    DomainSendOutcome.FAILED -> SendOutcome.FAILED
    DomainSendOutcome.PARTIAL_OR_AMBIGUOUS -> SendOutcome.PARTIAL_OR_AMBIGUOUS
    DomainSendOutcome.SKIPPED -> SendOutcome.SKIPPED
    DomainSendOutcome.USER_ACTION_REQUIRED -> SendOutcome.USER_ACTION_REQUIRED
    DomainSendOutcome.SHARED_UNVERIFIED -> SendOutcome.SHARED_UNVERIFIED
}

internal fun DomainDeliveryOutcome.toRecord(): DeliveryOutcome = when (this) {
    DomainDeliveryOutcome.NOT_REQUESTED -> DeliveryOutcome.NOT_REQUESTED
    DomainDeliveryOutcome.PENDING -> DeliveryOutcome.PENDING
    DomainDeliveryOutcome.DELIVERED -> DeliveryOutcome.DELIVERED
    DomainDeliveryOutcome.FAILED -> DeliveryOutcome.FAILED
    DomainDeliveryOutcome.UNAVAILABLE -> DeliveryOutcome.UNAVAILABLE
}

private fun OccurrenceState.toDomain(): DomainOccurrenceState = when (this) {
    OccurrenceState.PLANNED -> DomainOccurrenceState.PLANNED
    OccurrenceState.ARMED -> DomainOccurrenceState.ARMED
    OccurrenceState.DUE -> DomainOccurrenceState.DUE
    OccurrenceState.CLAIMED -> DomainOccurrenceState.CLAIMED
    OccurrenceState.SENDING -> DomainOccurrenceState.SENDING
    OccurrenceState.RETRY_WAIT -> DomainOccurrenceState.RETRY_WAIT
    OccurrenceState.READY_FOR_USER -> DomainOccurrenceState.READY_FOR_USER
    OccurrenceState.OPENED_IN_LATER_TEXT -> DomainOccurrenceState.OPENED_IN_LATER_TEXT
    OccurrenceState.SHARED_TO_MESSAGING_APP -> DomainOccurrenceState.SHARED_TO_MESSAGING_APP
    OccurrenceState.SENT_TO_CARRIER -> DomainOccurrenceState.SENT_TO_CARRIER
    OccurrenceState.DELIVERED -> DomainOccurrenceState.DELIVERED
    OccurrenceState.DELIVERY_FAILED -> DomainOccurrenceState.DELIVERY_FAILED
    OccurrenceState.DELIVERY_UNAVAILABLE -> DomainOccurrenceState.DELIVERY_UNAVAILABLE
    OccurrenceState.FAILED_TERMINAL -> DomainOccurrenceState.FAILED_TERMINAL
    OccurrenceState.PARTIAL_AMBIGUOUS -> DomainOccurrenceState.PARTIAL_AMBIGUOUS
    OccurrenceState.SKIPPED_PAUSED -> DomainOccurrenceState.SKIPPED_PAUSED
    OccurrenceState.SKIPPED_MISSED,
    OccurrenceState.MISSED,
    -> DomainOccurrenceState.MISSED
    OccurrenceState.EXPIRED -> DomainOccurrenceState.EXPIRED
    OccurrenceState.CANCELLED -> DomainOccurrenceState.CANCELLED
}

private fun SendOutcome.toDomain(): DomainSendOutcome = when (this) {
    SendOutcome.NOT_STARTED -> DomainSendOutcome.NOT_STARTED
    SendOutcome.PENDING -> DomainSendOutcome.PENDING
    SendOutcome.SENT_TO_CARRIER -> DomainSendOutcome.SENT_TO_CARRIER
    SendOutcome.FAILED -> DomainSendOutcome.FAILED
    SendOutcome.PARTIAL_OR_AMBIGUOUS -> DomainSendOutcome.PARTIAL_OR_AMBIGUOUS
    SendOutcome.SKIPPED -> DomainSendOutcome.SKIPPED
    SendOutcome.USER_ACTION_REQUIRED -> DomainSendOutcome.USER_ACTION_REQUIRED
    SendOutcome.SHARED_UNVERIFIED -> DomainSendOutcome.SHARED_UNVERIFIED
}

private fun DeliveryOutcome.toDomain(): DomainDeliveryOutcome = when (this) {
    DeliveryOutcome.NOT_REQUESTED -> DomainDeliveryOutcome.NOT_REQUESTED
    DeliveryOutcome.PENDING -> DomainDeliveryOutcome.PENDING
    DeliveryOutcome.DELIVERED -> DomainDeliveryOutcome.DELIVERED
    DeliveryOutcome.FAILED -> DomainDeliveryOutcome.FAILED
    DeliveryOutcome.UNAVAILABLE -> DomainDeliveryOutcome.UNAVAILABLE
}

internal fun monotonicEventTime(record: OccurrenceRecord, actual: Instant): Instant =
    maxOf(actual, Instant.ofEpochMilli(record.updatedAtEpochMillis))
