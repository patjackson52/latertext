package com.patjackson.latertext.core.domain.reducer

import com.patjackson.latertext.core.model.AttemptId
import com.patjackson.latertext.core.model.AttemptSnapshot
import com.patjackson.latertext.core.model.AttemptState
import com.patjackson.latertext.core.model.DeliveryOutcome
import com.patjackson.latertext.core.model.DeliveryPartOutcome
import com.patjackson.latertext.core.model.OccurrenceId
import com.patjackson.latertext.core.model.PartSubmissionOutcome
import com.patjackson.latertext.core.model.SmsFailureCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class AttemptReducerTest {
    private val reducer = AttemptReducer()
    private val start = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `out of order accepted callbacks aggregate and duplicate is idempotent`() {
        val submitted = reducer.reduce(snapshot(2), AttemptEvent.Submit(time(1)))
        val partOne = reducer.reduce(
            submitted,
            AttemptEvent.SentPartCallback(1, PartSubmissionOutcome.ACCEPTED, at = time(2)),
        )
        val accepted = reducer.reduce(
            partOne,
            AttemptEvent.SentPartCallback(0, PartSubmissionOutcome.ACCEPTED, at = time(3)),
        )
        assertEquals(AttemptState.ACCEPTED, accepted.state)
        assertEquals(DeliveryOutcome.PENDING, accepted.deliveryOutcome)
        assertEquals(2, accepted.acceptedPartCount)

        val duplicate = reducer.reduce(
            accepted,
            AttemptEvent.SentPartCallback(0, PartSubmissionOutcome.ACCEPTED, at = time(4)),
        )
        assertEquals(accepted, duplicate)
    }

    @Test
    fun `all definite pre-acceptance failures are retryable`() {
        val failed = finishSingle(
            PartSubmissionOutcome.FAILED_DEFINITE_PRE_ACCEPTANCE,
            SmsFailureCode.NO_SERVICE,
        )
        assertEquals(AttemptState.RETRYABLE_FAILURE, failed.state)
        assertEquals(0, failed.acceptedPartCount)
    }

    @Test
    fun `any accepted part makes a multipart failure partial and terminal`() {
        val submitted = reducer.reduce(snapshot(2), AttemptEvent.Submit(time(1)))
        val accepted = reducer.reduce(
            submitted,
            AttemptEvent.SentPartCallback(0, PartSubmissionOutcome.ACCEPTED, at = time(2)),
        )
        val partial = reducer.reduce(
            accepted,
            AttemptEvent.SentPartCallback(
                1,
                PartSubmissionOutcome.FAILED_DEFINITE_PRE_ACCEPTANCE,
                SmsFailureCode.NO_SERVICE,
                time(3),
            ),
        )
        assertEquals(AttemptState.PARTIAL_AMBIGUOUS, partial.state)
    }

    @Test
    fun `ambiguous and terminal failures remain distinct`() {
        assertEquals(
            AttemptState.AMBIGUOUS_FAILURE,
            finishSingle(PartSubmissionOutcome.FAILED_AMBIGUOUS, SmsFailureCode.GENERIC_FAILURE).state,
        )
        assertEquals(
            AttemptState.TERMINAL_FAILURE,
            finishSingle(PartSubmissionOutcome.FAILED_TERMINAL, SmsFailureCode.INVALID_ADDRESS).state,
        )
    }

    @Test
    fun `conflicting duplicate callback is rejected`() {
        val submitted = reducer.reduce(snapshot(2), AttemptEvent.Submit(time(1)))
        val first = reducer.reduce(
            submitted,
            AttemptEvent.SentPartCallback(0, PartSubmissionOutcome.ACCEPTED, at = time(2)),
        )
        assertThrows(DomainTransitionException::class.java) {
            reducer.reduce(
                first,
                AttemptEvent.SentPartCallback(
                    0,
                    PartSubmissionOutcome.FAILED_AMBIGUOUS,
                    SmsFailureCode.UNKNOWN,
                    time(3),
                ),
            )
        }
    }

    @Test
    fun `delivery is aggregated independently and missing receipt times out unavailable`() {
        val accepted = accepted(2)
        val firstDelivery = reducer.reduce(
            accepted,
            AttemptEvent.DeliveryPartCallback(0, DeliveryPartOutcome.DELIVERED, time(5)),
        )
        assertEquals(DeliveryOutcome.PENDING, firstDelivery.deliveryOutcome)
        val delivered = reducer.reduce(
            firstDelivery,
            AttemptEvent.DeliveryPartCallback(1, DeliveryPartOutcome.DELIVERED, time(6)),
        )
        assertEquals(DeliveryOutcome.DELIVERED, delivered.deliveryOutcome)

        val timedOut = reducer.reduce(accepted, AttemptEvent.DeliveryTimedOut(time(20)))
        assertEquals(DeliveryOutcome.UNAVAILABLE, timedOut.deliveryOutcome)
        assertEquals(AttemptState.ACCEPTED, timedOut.state)
    }

    private fun finishSingle(
        outcome: PartSubmissionOutcome,
        code: SmsFailureCode,
    ): AttemptSnapshot {
        val submitted = reducer.reduce(snapshot(1), AttemptEvent.Submit(time(1)))
        return reducer.reduce(
            submitted,
            AttemptEvent.SentPartCallback(0, outcome, code, time(2)),
        )
    }

    private fun accepted(parts: Int): AttemptSnapshot {
        var state = reducer.reduce(snapshot(parts), AttemptEvent.Submit(time(1)))
        repeat(parts) { index ->
            state = reducer.reduce(
                state,
                AttemptEvent.SentPartCallback(
                    index,
                    PartSubmissionOutcome.ACCEPTED,
                    at = time((index + 2).toLong()),
                ),
            )
        }
        return state
    }

    private fun snapshot(parts: Int) = AttemptSnapshot(
        id = AttemptId("attempt"),
        occurrenceId = OccurrenceId("occurrence"),
        attemptNumber = 1,
        totalParts = parts,
        startedAt = start,
    )

    private fun time(seconds: Long): Instant = start.plusSeconds(seconds)
}
