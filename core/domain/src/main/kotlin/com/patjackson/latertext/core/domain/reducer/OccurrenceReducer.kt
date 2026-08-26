package com.patjackson.latertext.core.domain.reducer

import com.patjackson.latertext.core.model.AttemptId
import com.patjackson.latertext.core.model.ClaimToken
import com.patjackson.latertext.core.model.DeliveryOutcome
import com.patjackson.latertext.core.model.OccurrenceSnapshot
import com.patjackson.latertext.core.model.OccurrenceState
import com.patjackson.latertext.core.model.SendOutcome
import com.patjackson.latertext.core.model.isTerminal
import java.time.Instant

sealed interface OccurrenceEvent {
    val at: Instant

    data class Arm(val generation: Long, override val at: Instant) : OccurrenceEvent
    data class BecomeDue(val generation: Long, override val at: Instant) : OccurrenceEvent
    data class Claim(
        val token: ClaimToken,
        val leaseUntil: Instant,
        override val at: Instant,
    ) : OccurrenceEvent
    data class BeginAutomaticAttempt(val attemptId: AttemptId, override val at: Instant) : OccurrenceEvent
    data class BeginAssistedReview(override val at: Instant) : OccurrenceEvent
    data class ScheduleRetry(val retryAt: Instant, override val at: Instant) : OccurrenceEvent
    data class CarrierAccepted(override val at: Instant) : OccurrenceEvent
    data class TerminalFailure(override val at: Instant) : OccurrenceEvent
    data class PartialOrAmbiguous(override val at: Instant) : OccurrenceEvent
    data class SkipPaused(override val at: Instant) : OccurrenceEvent
    data class MarkMissed(override val at: Instant) : OccurrenceEvent
    data class OpenInLaterText(override val at: Instant) : OccurrenceEvent
    data class ShareToMessagingApp(override val at: Instant) : OccurrenceEvent
    data class Expire(override val at: Instant) : OccurrenceEvent
    data class RecordDelivery(val outcome: DeliveryOutcome, override val at: Instant) : OccurrenceEvent
    data class Cancel(override val at: Instant) : OccurrenceEvent
}

/** Pure, idempotency-aware state reducer. Persistence must apply each event transactionally. */
class OccurrenceReducer {
    fun reduce(snapshot: OccurrenceSnapshot, event: OccurrenceEvent): OccurrenceSnapshot {
        require(!event.at.isBefore(snapshot.updatedAt)) { "Occurrence event cannot move time backwards" }
        return when (event) {
            is OccurrenceEvent.Arm -> arm(snapshot, event)
            is OccurrenceEvent.BecomeDue -> becomeDue(snapshot, event)
            is OccurrenceEvent.Claim -> claim(snapshot, event)
            is OccurrenceEvent.BeginAutomaticAttempt -> beginAutomatic(snapshot, event)
            is OccurrenceEvent.BeginAssistedReview -> beginAssisted(snapshot, event)
            is OccurrenceEvent.ScheduleRetry -> scheduleRetry(snapshot, event)
            is OccurrenceEvent.CarrierAccepted -> carrierAccepted(snapshot, event)
            is OccurrenceEvent.TerminalFailure -> finish(
                snapshot, event.at, OccurrenceState.FAILED_TERMINAL, SendOutcome.FAILED,
            )
            is OccurrenceEvent.PartialOrAmbiguous -> finish(
                snapshot, event.at, OccurrenceState.PARTIAL_AMBIGUOUS, SendOutcome.PARTIAL_OR_AMBIGUOUS,
            )
            is OccurrenceEvent.SkipPaused -> skip(snapshot, event.at, OccurrenceState.SKIPPED_PAUSED)
            is OccurrenceEvent.MarkMissed -> skip(snapshot, event.at, OccurrenceState.MISSED)
            is OccurrenceEvent.OpenInLaterText -> openInLaterText(snapshot, event)
            is OccurrenceEvent.ShareToMessagingApp -> share(snapshot, event)
            is OccurrenceEvent.Expire -> expire(snapshot, event)
            is OccurrenceEvent.RecordDelivery -> recordDelivery(snapshot, event)
            is OccurrenceEvent.Cancel -> cancel(snapshot, event)
        }
    }

    private fun arm(state: OccurrenceSnapshot, event: OccurrenceEvent.Arm): OccurrenceSnapshot {
        if (state.state.isTerminal) return state
        if (event.generation <= state.alarmGeneration) return state
        if (state.state !in setOf(OccurrenceState.PLANNED, OccurrenceState.ARMED, OccurrenceState.RETRY_WAIT)) {
            return invalid(state, "arm")
        }
        return state.copy(
            state = OccurrenceState.ARMED,
            alarmGeneration = event.generation,
            updatedAt = event.at,
        )
    }

    private fun becomeDue(
        state: OccurrenceSnapshot,
        event: OccurrenceEvent.BecomeDue,
    ): OccurrenceSnapshot {
        if (event.generation != state.alarmGeneration) return state // stale PendingIntent
        if (state.state == OccurrenceState.DUE || state.state == OccurrenceState.CLAIMED) return state
        if (state.state != OccurrenceState.ARMED) return state
        return state.copy(
            state = OccurrenceState.DUE,
            nextActionAt = null,
            updatedAt = event.at,
        )
    }

    private fun claim(state: OccurrenceSnapshot, event: OccurrenceEvent.Claim): OccurrenceSnapshot {
        require(event.leaseUntil.isAfter(event.at)) { "Claim lease must end after claim time" }
        if (state.state == OccurrenceState.CLAIMED) {
            if (state.claimToken == event.token) return state
            if (state.leaseUntil?.isAfter(event.at) == true) return state
        } else if (state.state != OccurrenceState.DUE) {
            return invalid(state, "claim")
        }
        return state.copy(
            state = OccurrenceState.CLAIMED,
            claimToken = event.token,
            leaseUntil = event.leaseUntil,
            updatedAt = event.at,
        )
    }

    private fun beginAutomatic(
        state: OccurrenceSnapshot,
        event: OccurrenceEvent.BeginAutomaticAttempt,
    ): OccurrenceSnapshot {
        if (state.state != OccurrenceState.CLAIMED) return invalid(state, "begin automatic attempt")
        return state.copy(
            state = OccurrenceState.SENDING,
            attemptCount = state.attemptCount + 1,
            activeAttemptId = event.attemptId,
            sendOutcome = SendOutcome.PENDING,
            deliveryOutcome = DeliveryOutcome.NOT_REQUESTED,
            updatedAt = event.at,
        )
    }

    private fun beginAssisted(
        state: OccurrenceSnapshot,
        event: OccurrenceEvent.BeginAssistedReview,
    ): OccurrenceSnapshot {
        if (state.state != OccurrenceState.CLAIMED) return invalid(state, "begin assisted review")
        return state.copy(
            state = OccurrenceState.READY_FOR_USER,
            sendOutcome = SendOutcome.USER_ACTION_REQUIRED,
            claimToken = null,
            leaseUntil = null,
            updatedAt = event.at,
        )
    }

    private fun scheduleRetry(
        state: OccurrenceSnapshot,
        event: OccurrenceEvent.ScheduleRetry,
    ): OccurrenceSnapshot {
        if (state.state != OccurrenceState.SENDING) return invalid(state, "schedule retry")
        require(event.retryAt.isAfter(event.at)) { "Retry must be scheduled in the future" }
        return state.copy(
            state = OccurrenceState.RETRY_WAIT,
            nextActionAt = event.retryAt,
            activeAttemptId = null,
            claimToken = null,
            leaseUntil = null,
            updatedAt = event.at,
        )
    }

    private fun carrierAccepted(
        state: OccurrenceSnapshot,
        event: OccurrenceEvent.CarrierAccepted,
    ): OccurrenceSnapshot {
        if (state.state == OccurrenceState.SENT_TO_CARRIER) return state
        if (state.state != OccurrenceState.SENDING) return invalid(state, "record carrier acceptance")
        return state.copy(
            state = OccurrenceState.SENT_TO_CARRIER,
            sendOutcome = SendOutcome.SENT_TO_CARRIER,
            deliveryOutcome = DeliveryOutcome.PENDING,
            claimToken = null,
            leaseUntil = null,
            updatedAt = event.at,
        )
    }

    private fun finish(
        state: OccurrenceSnapshot,
        at: Instant,
        terminalState: OccurrenceState,
        sendOutcome: SendOutcome,
    ): OccurrenceSnapshot {
        if (state.state == terminalState) return state
        if (state.state != OccurrenceState.SENDING) return invalid(state, "finish automatic send")
        return state.copy(
            state = terminalState,
            sendOutcome = sendOutcome,
            deliveryOutcome = DeliveryOutcome.NOT_REQUESTED,
            claimToken = null,
            leaseUntil = null,
            nextActionAt = null,
            updatedAt = at,
        )
    }

    private fun skip(
        state: OccurrenceSnapshot,
        at: Instant,
        target: OccurrenceState,
    ): OccurrenceSnapshot {
        if (state.state == target) return state
        if (state.state !in setOf(
                OccurrenceState.PLANNED,
                OccurrenceState.ARMED,
                OccurrenceState.DUE,
                OccurrenceState.CLAIMED,
                OccurrenceState.RETRY_WAIT,
            )
        ) return invalid(state, "mark $target")
        return state.copy(
            state = target,
            sendOutcome = SendOutcome.SKIPPED,
            claimToken = null,
            leaseUntil = null,
            nextActionAt = null,
            updatedAt = at,
        )
    }

    private fun openInLaterText(
        state: OccurrenceSnapshot,
        event: OccurrenceEvent.OpenInLaterText,
    ): OccurrenceSnapshot {
        if (state.state == OccurrenceState.OPENED_IN_LATER_TEXT) return state
        if (state.state != OccurrenceState.READY_FOR_USER) return invalid(state, "open assisted review")
        return state.copy(state = OccurrenceState.OPENED_IN_LATER_TEXT, updatedAt = event.at)
    }

    private fun share(
        state: OccurrenceSnapshot,
        event: OccurrenceEvent.ShareToMessagingApp,
    ): OccurrenceSnapshot {
        if (state.state == OccurrenceState.SHARED_TO_MESSAGING_APP) return state
        if (state.state != OccurrenceState.OPENED_IN_LATER_TEXT) {
            return invalid(state, "share to messaging app")
        }
        return state.copy(
            state = OccurrenceState.SHARED_TO_MESSAGING_APP,
            sendOutcome = SendOutcome.SHARED_UNVERIFIED,
            updatedAt = event.at,
        )
    }

    private fun expire(state: OccurrenceSnapshot, event: OccurrenceEvent.Expire): OccurrenceSnapshot {
        if (state.state == OccurrenceState.EXPIRED) return state
        if (state.state !in setOf(
                OccurrenceState.READY_FOR_USER,
                OccurrenceState.OPENED_IN_LATER_TEXT,
            )
        ) return invalid(state, "expire assisted handoff")
        return state.copy(
            state = OccurrenceState.EXPIRED,
            sendOutcome = SendOutcome.SKIPPED,
            updatedAt = event.at,
        )
    }

    private fun recordDelivery(
        state: OccurrenceSnapshot,
        event: OccurrenceEvent.RecordDelivery,
    ): OccurrenceSnapshot {
        val targetState = when (event.outcome) {
            DeliveryOutcome.DELIVERED -> OccurrenceState.DELIVERED
            DeliveryOutcome.FAILED -> OccurrenceState.DELIVERY_FAILED
            DeliveryOutcome.UNAVAILABLE -> OccurrenceState.DELIVERY_UNAVAILABLE
            else -> return invalid(state, "record non-terminal delivery outcome")
        }
        if (state.state == targetState) return state
        if (state.state != OccurrenceState.SENT_TO_CARRIER) return invalid(state, "record delivery")
        return state.copy(
            state = targetState,
            deliveryOutcome = event.outcome,
            updatedAt = event.at,
        )
    }

    private fun cancel(state: OccurrenceSnapshot, event: OccurrenceEvent.Cancel): OccurrenceSnapshot {
        if (state.state == OccurrenceState.CANCELLED) return state
        if (state.state.isTerminal || state.state in setOf(
                OccurrenceState.SENDING,
                OccurrenceState.SENT_TO_CARRIER,
            )
        ) return invalid(state, "cancel")
        return state.copy(
            state = OccurrenceState.CANCELLED,
            sendOutcome = SendOutcome.SKIPPED,
            claimToken = null,
            leaseUntil = null,
            nextActionAt = null,
            updatedAt = event.at,
        )
    }

    private fun invalid(state: OccurrenceSnapshot, action: String): Nothing =
        throw DomainTransitionException(
            "Cannot $action occurrence ${state.id.value} from ${state.state}",
        )
}
