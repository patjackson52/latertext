package com.patjackson.latertext.core.domain.retry

import com.patjackson.latertext.core.model.AttemptSnapshot
import com.patjackson.latertext.core.model.AttemptState
import com.patjackson.latertext.core.model.FailureCertainty
import com.patjackson.latertext.core.model.PartSubmissionOutcome
import com.patjackson.latertext.core.model.RetryDisposition
import com.patjackson.latertext.core.model.RetryReason
import com.patjackson.latertext.core.model.SmsFailureCode
import java.time.Duration
import java.time.Instant

class SmsFailureClassifier {
    fun classify(code: SmsFailureCode): FailureCertainty = when (code) {
        SmsFailureCode.NO_SERVICE,
        SmsFailureCode.RADIO_OFF,
        SmsFailureCode.NETWORK_ERROR,
        SmsFailureCode.RETRYABLE_MODEM_ERROR,
        -> FailureCertainty.DEFINITE_PRE_ACCEPTANCE

        SmsFailureCode.GENERIC_FAILURE,
        SmsFailureCode.MODEM_ERROR,
        SmsFailureCode.NULL_PDU,
        SmsFailureCode.UNKNOWN,
        -> FailureCertainty.AMBIGUOUS

        SmsFailureCode.INVALID_ARGUMENTS,
        SmsFailureCode.INVALID_ADDRESS,
        SmsFailureCode.FDN_BLOCKED,
        SmsFailureCode.SHORT_CODE_BLOCKED,
        SmsFailureCode.LIMIT_EXCEEDED,
        SmsFailureCode.SIM_UNAVAILABLE,
        SmsFailureCode.PERMISSION_DENIED,
        -> FailureCertainty.TERMINAL
    }

    fun partOutcome(code: SmsFailureCode): PartSubmissionOutcome = when (classify(code)) {
        FailureCertainty.DEFINITE_PRE_ACCEPTANCE ->
            PartSubmissionOutcome.FAILED_DEFINITE_PRE_ACCEPTANCE
        FailureCertainty.AMBIGUOUS -> PartSubmissionOutcome.FAILED_AMBIGUOUS
        FailureCertainty.TERMINAL -> PartSubmissionOutcome.FAILED_TERMINAL
    }
}

data class RetryDecision(
    val disposition: RetryDisposition,
    val reason: RetryReason,
    val retryAt: Instant? = null,
) {
    init {
        require((disposition == RetryDisposition.RETRY_AT) == (retryAt != null))
    }
}

class RetryPolicy(
    private val maximumAttempts: Int = 3,
    private val backoffs: List<Duration> = listOf(
        Duration.ofMinutes(2),
        Duration.ofMinutes(15),
    ),
) {
    init {
        require(maximumAttempts > 0)
        require(backoffs.size >= maximumAttempts - 1)
        require(backoffs.all { !it.isNegative && !it.isZero })
    }

    fun decide(attempt: AttemptSnapshot, now: Instant, deadline: Instant): RetryDecision {
        if (attempt.acceptedPartCount > 0) return noRetry(RetryReason.PART_ALREADY_ACCEPTED)
        when (attempt.state) {
            AttemptState.AMBIGUOUS_FAILURE,
            AttemptState.PARTIAL_AMBIGUOUS,
            -> return noRetry(RetryReason.AMBIGUOUS_RESULT)

            AttemptState.TERMINAL_FAILURE -> return noRetry(RetryReason.TERMINAL_FAILURE)
            AttemptState.RETRYABLE_FAILURE -> Unit
            else -> return noRetry(RetryReason.ATTEMPT_NOT_RETRYABLE)
        }
        if (attempt.attemptNumber >= maximumAttempts) {
            return noRetry(RetryReason.ATTEMPT_LIMIT_REACHED)
        }
        val retryAt = now.plus(backoffs[attempt.attemptNumber - 1])
        if (retryAt.isAfter(deadline)) return noRetry(RetryReason.GRACE_DEADLINE_EXCEEDED)
        return RetryDecision(
            disposition = RetryDisposition.RETRY_AT,
            reason = RetryReason.DEFINITE_PRE_ACCEPTANCE_FAILURE,
            retryAt = retryAt,
        )
    }

    private fun noRetry(reason: RetryReason) = RetryDecision(
        disposition = RetryDisposition.DO_NOT_RETRY,
        reason = reason,
    )
}
