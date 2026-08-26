package com.patjackson.latertext.core.domain.retry

import com.patjackson.latertext.core.model.AttemptId
import com.patjackson.latertext.core.model.AttemptSnapshot
import com.patjackson.latertext.core.model.AttemptState
import com.patjackson.latertext.core.model.FailureCertainty
import com.patjackson.latertext.core.model.OccurrenceId
import com.patjackson.latertext.core.model.PartSubmissionOutcome
import com.patjackson.latertext.core.model.RetryDisposition
import com.patjackson.latertext.core.model.RetryReason
import com.patjackson.latertext.core.model.SentPartResult
import com.patjackson.latertext.core.model.SmsFailureCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class RetryPolicyTest {
    private val classifier = SmsFailureClassifier()
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `failure classifier partitions every platform neutral result`() {
        val definite = setOf(
            SmsFailureCode.NO_SERVICE,
            SmsFailureCode.RADIO_OFF,
            SmsFailureCode.NETWORK_ERROR,
            SmsFailureCode.RETRYABLE_MODEM_ERROR,
        )
        val ambiguous = setOf(
            SmsFailureCode.GENERIC_FAILURE,
            SmsFailureCode.MODEM_ERROR,
            SmsFailureCode.NULL_PDU,
            SmsFailureCode.UNKNOWN,
        )
        SmsFailureCode.entries.forEach { code ->
            val expected = when (code) {
                in definite -> FailureCertainty.DEFINITE_PRE_ACCEPTANCE
                in ambiguous -> FailureCertainty.AMBIGUOUS
                else -> FailureCertainty.TERMINAL
            }
            assertEquals(expected, classifier.classify(code), code.name)
        }
    }

    @Test
    fun `safe failures retry at bounded backoff inside grace`() {
        val first = RetryPolicy().decide(
            attempt(state = AttemptState.RETRYABLE_FAILURE, number = 1),
            now,
            now.plusSeconds(10_000),
        )
        assertEquals(RetryDisposition.RETRY_AT, first.disposition)
        assertEquals(now.plusSeconds(120), first.retryAt)
        assertEquals(RetryReason.DEFINITE_PRE_ACCEPTANCE_FAILURE, first.reason)

        val second = RetryPolicy().decide(
            attempt(state = AttemptState.RETRYABLE_FAILURE, number = 2),
            now,
            now.plusSeconds(10_000),
        )
        assertEquals(now.plusSeconds(900), second.retryAt)
    }

    @Test
    fun `attempt limit and grace deadline prevent retries`() {
        assertEquals(
            RetryReason.ATTEMPT_LIMIT_REACHED,
            RetryPolicy().decide(
                attempt(state = AttemptState.RETRYABLE_FAILURE, number = 3),
                now,
                now.plusSeconds(10_000),
            ).reason,
        )
        assertEquals(
            RetryReason.GRACE_DEADLINE_EXCEEDED,
            RetryPolicy().decide(
                attempt(state = AttemptState.RETRYABLE_FAILURE, number = 1),
                now,
                now.plusSeconds(119),
            ).reason,
        )
    }

    @Test
    fun `partial ambiguous terminal and active attempts never auto retry`() {
        assertEquals(
            RetryReason.PART_ALREADY_ACCEPTED,
            RetryPolicy().decide(
                attempt(
                    state = AttemptState.PARTIAL_AMBIGUOUS,
                    accepted = true,
                ),
                now,
                now.plusSeconds(10_000),
            ).reason,
        )
        assertEquals(
            RetryReason.AMBIGUOUS_RESULT,
            RetryPolicy().decide(
                attempt(state = AttemptState.AMBIGUOUS_FAILURE),
                now,
                now.plusSeconds(10_000),
            ).reason,
        )
        assertEquals(
            RetryReason.TERMINAL_FAILURE,
            RetryPolicy().decide(
                attempt(state = AttemptState.TERMINAL_FAILURE),
                now,
                now.plusSeconds(10_000),
            ).reason,
        )
        assertEquals(
            RetryReason.ATTEMPT_NOT_RETRYABLE,
            RetryPolicy().decide(
                attempt(state = AttemptState.AWAITING_SENT_CALLBACKS),
                now,
                now.plusSeconds(10_000),
            ).reason,
        )
    }

    private fun attempt(
        state: AttemptState,
        number: Int = 1,
        accepted: Boolean = false,
    ) = AttemptSnapshot(
        id = AttemptId("attempt-$number"),
        occurrenceId = OccurrenceId("occurrence"),
        attemptNumber = number,
        totalParts = 1,
        state = state,
        sentParts = if (accepted) {
            mapOf(
                0 to SentPartResult(
                    0,
                    PartSubmissionOutcome.ACCEPTED,
                    callbackAt = now,
                ),
            )
        } else {
            emptyMap()
        },
        startedAt = now,
    )
}
