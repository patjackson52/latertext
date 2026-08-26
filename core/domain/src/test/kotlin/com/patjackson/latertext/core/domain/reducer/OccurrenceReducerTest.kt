package com.patjackson.latertext.core.domain.reducer

import com.patjackson.latertext.core.model.AttemptId
import com.patjackson.latertext.core.model.ClaimToken
import com.patjackson.latertext.core.model.DeliveryOutcome
import com.patjackson.latertext.core.model.LogicalOccurrenceKey
import com.patjackson.latertext.core.model.OccurrenceId
import com.patjackson.latertext.core.model.OccurrenceSnapshot
import com.patjackson.latertext.core.model.OccurrenceState
import com.patjackson.latertext.core.model.RuleRevisionId
import com.patjackson.latertext.core.model.ScheduleId
import com.patjackson.latertext.core.model.SendOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class OccurrenceReducerTest {
    private val reducer = OccurrenceReducer()
    private val start = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `stale alarm is ignored and active claim lease prevents duplicate ownership`() {
        val armed = reducer.reduce(snapshot(), OccurrenceEvent.Arm(1, time(1)))
        assertEquals(armed, reducer.reduce(armed, OccurrenceEvent.BecomeDue(0, time(2))))
        val due = reducer.reduce(armed, OccurrenceEvent.BecomeDue(1, time(2)))
        val claimed = reducer.reduce(
            due,
            OccurrenceEvent.Claim(ClaimToken("first"), time(20), time(3)),
        )
        val blocked = reducer.reduce(
            claimed,
            OccurrenceEvent.Claim(ClaimToken("second"), time(30), time(4)),
        )
        assertEquals(ClaimToken("first"), blocked.claimToken)

        val reclaimed = reducer.reduce(
            blocked,
            OccurrenceEvent.Claim(ClaimToken("second"), time(40), time(20)),
        )
        assertEquals(ClaimToken("second"), reclaimed.claimToken)
    }

    @Test
    fun `automatic attempt stays distinct from delivery result`() {
        val claimed = claimed()
        val sending = reducer.reduce(
            claimed,
            OccurrenceEvent.BeginAutomaticAttempt(AttemptId("attempt-1"), time(4)),
        )
        assertEquals(OccurrenceState.SENDING, sending.state)
        assertEquals(SendOutcome.PENDING, sending.sendOutcome)

        val accepted = reducer.reduce(sending, OccurrenceEvent.CarrierAccepted(time(5)))
        assertEquals(OccurrenceState.SENT_TO_CARRIER, accepted.state)
        assertEquals(SendOutcome.SENT_TO_CARRIER, accepted.sendOutcome)
        assertEquals(DeliveryOutcome.PENDING, accepted.deliveryOutcome)

        val unavailable = reducer.reduce(
            accepted,
            OccurrenceEvent.RecordDelivery(DeliveryOutcome.UNAVAILABLE, time(6)),
        )
        assertEquals(OccurrenceState.DELIVERY_UNAVAILABLE, unavailable.state)
        assertEquals(SendOutcome.SENT_TO_CARRIER, unavailable.sendOutcome)
        assertEquals(DeliveryOutcome.UNAVAILABLE, unavailable.deliveryOutcome)
    }

    @Test
    fun `retry clears lease and records next action`() {
        val sending = reducer.reduce(
            claimed(),
            OccurrenceEvent.BeginAutomaticAttempt(AttemptId("attempt-1"), time(4)),
        )
        val retry = reducer.reduce(
            sending,
            OccurrenceEvent.ScheduleRetry(retryAt = time(120), at = time(5)),
        )
        assertEquals(OccurrenceState.RETRY_WAIT, retry.state)
        assertEquals(time(120), retry.nextActionAt)
        assertEquals(null, retry.claimToken)
        assertEquals(1, retry.attemptCount)
    }

    @Test
    fun `partial result is terminal and cannot be cancelled or retried`() {
        val sending = reducer.reduce(
            claimed(),
            OccurrenceEvent.BeginAutomaticAttempt(AttemptId("attempt-1"), time(4)),
        )
        val partial = reducer.reduce(sending, OccurrenceEvent.PartialOrAmbiguous(time(5)))
        assertEquals(OccurrenceState.PARTIAL_AMBIGUOUS, partial.state)
        assertEquals(SendOutcome.PARTIAL_OR_AMBIGUOUS, partial.sendOutcome)
        assertThrows(DomainTransitionException::class.java) {
            reducer.reduce(partial, OccurrenceEvent.Cancel(time(6)))
        }
    }

    @Test
    fun `assisted handoff records only unverified sharing`() {
        val ready = reducer.reduce(claimed(), OccurrenceEvent.BeginAssistedReview(time(4)))
        assertEquals(OccurrenceState.READY_FOR_USER, ready.state)
        assertEquals(SendOutcome.USER_ACTION_REQUIRED, ready.sendOutcome)
        assertThrows(DomainTransitionException::class.java) {
            reducer.reduce(ready, OccurrenceEvent.ShareToMessagingApp(time(5)))
        }

        val opened = reducer.reduce(ready, OccurrenceEvent.OpenInLaterText(time(5)))
        val shared = reducer.reduce(opened, OccurrenceEvent.ShareToMessagingApp(time(6)))
        assertEquals(OccurrenceState.SHARED_TO_MESSAGING_APP, shared.state)
        assertEquals(SendOutcome.SHARED_UNVERIFIED, shared.sendOutcome)
        assertEquals(DeliveryOutcome.NOT_REQUESTED, shared.deliveryOutcome)
    }

    @Test
    fun `pause skip and missed are terminal non-send outcomes`() {
        val armed = reducer.reduce(snapshot(), OccurrenceEvent.Arm(1, time(1)))
        val skipped = reducer.reduce(armed, OccurrenceEvent.SkipPaused(time(2)))
        assertEquals(OccurrenceState.SKIPPED_PAUSED, skipped.state)
        assertEquals(SendOutcome.SKIPPED, skipped.sendOutcome)

        val missed = reducer.reduce(snapshot(), OccurrenceEvent.MarkMissed(time(2)))
        assertEquals(OccurrenceState.MISSED, missed.state)
    }

    private fun claimed(): OccurrenceSnapshot {
        val armed = reducer.reduce(snapshot(), OccurrenceEvent.Arm(1, time(1)))
        val due = reducer.reduce(armed, OccurrenceEvent.BecomeDue(1, time(2)))
        return reducer.reduce(
            due,
            OccurrenceEvent.Claim(ClaimToken("claim"), time(20), time(3)),
        )
    }

    private fun snapshot() = OccurrenceSnapshot(
        id = OccurrenceId("occurrence"),
        scheduleId = ScheduleId("schedule"),
        logicalKey = LogicalOccurrenceKey(ScheduleId("schedule"), RuleRevisionId("rule"), 0),
        updatedAt = start,
    )

    private fun time(seconds: Long): Instant = start.plusSeconds(seconds)
}
