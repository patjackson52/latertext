package com.patjackson.latertext.core.domain.usecase

import com.patjackson.latertext.core.model.MissedPolicy
import java.time.Instant

enum class DueAction {
    WAIT,
    SEND_NOW,
    REQUIRE_USER,
    SKIP_PAUSED,
    MARK_MISSED,
    ASK_ME_UNAVAILABLE,
}

data class DueOccurrenceContext(
    val targetAt: Instant,
    val deadlineAt: Instant,
    val now: Instant,
    val schedulePaused: Boolean,
    val globallyPaused: Boolean,
    val missedPolicy: MissedPolicy,
    val actionNotificationAvailable: Boolean,
) {
    init { require(!deadlineAt.isBefore(targetAt)) }
}

/** Decides only what should happen; side effects and state transitions remain external. */
class DueOccurrenceEvaluator {
    fun evaluate(context: DueOccurrenceContext): DueAction {
        if (context.now.isBefore(context.targetAt)) return DueAction.WAIT
        val paused = context.schedulePaused || context.globallyPaused
        if (paused) {
            return if (context.now.isAfter(context.deadlineAt)) {
                DueAction.SKIP_PAUSED
            } else {
                DueAction.WAIT
            }
        }
        if (context.now.isAfter(context.deadlineAt)) return DueAction.MARK_MISSED
        if (context.now == context.targetAt) return DueAction.SEND_NOW
        return when (context.missedPolicy) {
            MissedPolicy.SEND_AS_SOON_AS_POSSIBLE -> DueAction.SEND_NOW
            MissedPolicy.SKIP -> DueAction.MARK_MISSED
            MissedPolicy.ASK_ME -> if (context.actionNotificationAvailable) {
                DueAction.REQUIRE_USER
            } else {
                DueAction.ASK_ME_UNAVAILABLE
            }
        }
    }
}
