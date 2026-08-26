package com.patjackson.latertext.core.domain.reducer

import com.patjackson.latertext.core.model.AttemptSnapshot
import com.patjackson.latertext.core.model.AttemptState
import com.patjackson.latertext.core.model.DeliveryOutcome
import com.patjackson.latertext.core.model.DeliveryPartOutcome
import com.patjackson.latertext.core.model.DeliveryPartResult
import com.patjackson.latertext.core.model.PartSubmissionOutcome
import com.patjackson.latertext.core.model.SentPartResult
import com.patjackson.latertext.core.model.SmsFailureCode
import java.time.Instant

sealed interface AttemptEvent {
    val at: Instant

    data class Submit(override val at: Instant) : AttemptEvent
    data class SentPartCallback(
        val partIndex: Int,
        val outcome: PartSubmissionOutcome,
        val failureCode: SmsFailureCode? = null,
        override val at: Instant,
    ) : AttemptEvent
    data class DeliveryPartCallback(
        val partIndex: Int,
        val outcome: DeliveryPartOutcome,
        override val at: Instant,
    ) : AttemptEvent
    data class DeliveryTimedOut(override val at: Instant) : AttemptEvent
}

class AttemptReducer {
    fun reduce(snapshot: AttemptSnapshot, event: AttemptEvent): AttemptSnapshot {
        require(!event.at.isBefore(snapshot.startedAt)) { "Attempt event predates attempt" }
        return when (event) {
            is AttemptEvent.Submit -> submit(snapshot, event)
            is AttemptEvent.SentPartCallback -> sentPart(snapshot, event)
            is AttemptEvent.DeliveryPartCallback -> deliveryPart(snapshot, event)
            is AttemptEvent.DeliveryTimedOut -> deliveryTimedOut(snapshot, event)
        }
    }

    private fun submit(state: AttemptSnapshot, event: AttemptEvent.Submit): AttemptSnapshot = when (state.state) {
        AttemptState.CREATED -> state.copy(
            state = AttemptState.AWAITING_SENT_CALLBACKS,
            deliveryOutcome = DeliveryOutcome.NOT_REQUESTED,
        )
        AttemptState.AWAITING_SENT_CALLBACKS -> state
        else -> invalid(state, "submit")
    }

    private fun sentPart(
        state: AttemptSnapshot,
        event: AttemptEvent.SentPartCallback,
    ): AttemptSnapshot {
        if (state.state != AttemptState.AWAITING_SENT_CALLBACKS) {
            val persisted = state.sentParts[event.partIndex]
            if (persisted != null && persisted.matches(event)) return state
            return invalid(state, "record sent callback")
        }
        require(event.partIndex in 0 until state.totalParts) { "Part index is out of range" }
        val result = SentPartResult(
            partIndex = event.partIndex,
            outcome = event.outcome,
            failureCode = event.failureCode,
            callbackAt = event.at,
        )
        val existing = state.sentParts[event.partIndex]
        if (existing != null) {
            if (existing == result || existing.matches(event)) return state
            throw DomainTransitionException(
                "Conflicting callback for attempt ${state.id.value} part ${event.partIndex}",
            )
        }

        val sentParts = state.sentParts + (event.partIndex to result)
        if (sentParts.size < state.totalParts) return state.copy(sentParts = sentParts)

        val accepted = sentParts.values.count { it.outcome == PartSubmissionOutcome.ACCEPTED }
        val finalState = when {
            accepted == state.totalParts -> AttemptState.ACCEPTED
            accepted > 0 -> AttemptState.PARTIAL_AMBIGUOUS
            sentParts.values.any { it.outcome == PartSubmissionOutcome.FAILED_AMBIGUOUS } ->
                AttemptState.AMBIGUOUS_FAILURE
            sentParts.values.any { it.outcome == PartSubmissionOutcome.FAILED_TERMINAL } ->
                AttemptState.TERMINAL_FAILURE
            else -> AttemptState.RETRYABLE_FAILURE
        }
        return state.copy(
            state = finalState,
            sentParts = sentParts,
            deliveryOutcome = if (finalState == AttemptState.ACCEPTED) {
                DeliveryOutcome.PENDING
            } else {
                DeliveryOutcome.NOT_REQUESTED
            },
            completedAt = event.at,
        )
    }

    private fun deliveryPart(
        state: AttemptSnapshot,
        event: AttemptEvent.DeliveryPartCallback,
    ): AttemptSnapshot {
        if (state.state != AttemptState.ACCEPTED) return invalid(state, "record delivery callback")
        require(event.partIndex in 0 until state.totalParts) { "Part index is out of range" }
        val result = DeliveryPartResult(event.partIndex, event.outcome, event.at)
        val existing = state.deliveryParts[event.partIndex]
        if (existing != null) {
            if (existing == result || (existing.outcome == event.outcome)) return state
            throw DomainTransitionException(
                "Conflicting delivery callback for attempt ${state.id.value} part ${event.partIndex}",
            )
        }
        val parts = state.deliveryParts + (event.partIndex to result)
        val outcome = when {
            parts.size < state.totalParts -> DeliveryOutcome.PENDING
            parts.values.all { it.outcome == DeliveryPartOutcome.DELIVERED } -> DeliveryOutcome.DELIVERED
            else -> DeliveryOutcome.FAILED
        }
        return state.copy(deliveryParts = parts, deliveryOutcome = outcome)
    }

    private fun deliveryTimedOut(
        state: AttemptSnapshot,
        event: AttemptEvent.DeliveryTimedOut,
    ): AttemptSnapshot {
        if (state.state != AttemptState.ACCEPTED) return invalid(state, "time out delivery")
        if (state.deliveryOutcome in setOf(DeliveryOutcome.DELIVERED, DeliveryOutcome.FAILED)) return state
        return state.copy(deliveryOutcome = DeliveryOutcome.UNAVAILABLE)
    }

    private fun SentPartResult.matches(event: AttemptEvent.SentPartCallback): Boolean =
        partIndex == event.partIndex && outcome == event.outcome && failureCode == event.failureCode

    private fun invalid(state: AttemptSnapshot, action: String): Nothing =
        throw DomainTransitionException("Cannot $action attempt ${state.id.value} from ${state.state}")
}
