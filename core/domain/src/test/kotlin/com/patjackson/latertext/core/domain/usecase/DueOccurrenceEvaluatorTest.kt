package com.patjackson.latertext.core.domain.usecase

import com.patjackson.latertext.core.model.MissedPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class DueOccurrenceEvaluatorTest {
    private val evaluator = DueOccurrenceEvaluator()
    private val target = Instant.parse("2026-01-01T17:00:00Z")
    private val deadline = target.plusSeconds(4 * 60 * 60)

    @Test
    fun `future and paused in-grace occurrences wait`() {
        assertEquals(DueAction.WAIT, evaluate(now = target.minusSeconds(1)))
        assertEquals(DueAction.WAIT, evaluate(now = target.plusSeconds(1), schedulePaused = true))
        assertEquals(DueAction.WAIT, evaluate(now = target.plusSeconds(1), globallyPaused = true))
    }

    @Test
    fun `paused occurrence becomes skipped only after grace expires`() {
        assertEquals(
            DueAction.SKIP_PAUSED,
            evaluate(now = deadline.plusSeconds(1), schedulePaused = true),
        )
    }

    @Test
    fun `on-time send is unaffected by missed policy`() {
        MissedPolicy.entries.forEach { policy ->
            assertEquals(DueAction.SEND_NOW, evaluate(now = target, policy = policy), policy.name)
        }
    }

    @Test
    fun `late occurrence follows selected missed policy inside grace`() {
        val late = target.plusSeconds(1)
        assertEquals(
            DueAction.SEND_NOW,
            evaluate(now = late, policy = MissedPolicy.SEND_AS_SOON_AS_POSSIBLE),
        )
        assertEquals(DueAction.MARK_MISSED, evaluate(now = late, policy = MissedPolicy.SKIP))
        assertEquals(
            DueAction.REQUIRE_USER,
            evaluate(now = late, policy = MissedPolicy.ASK_ME, notifications = true),
        )
        assertEquals(
            DueAction.ASK_ME_UNAVAILABLE,
            evaluate(now = late, policy = MissedPolicy.ASK_ME, notifications = false),
        )
    }

    @Test
    fun `all unpaused occurrences after grace are missed`() {
        MissedPolicy.entries.forEach { policy ->
            assertEquals(
                DueAction.MARK_MISSED,
                evaluate(now = deadline.plusSeconds(1), policy = policy),
                policy.name,
            )
        }
    }

    private fun evaluate(
        now: Instant,
        schedulePaused: Boolean = false,
        globallyPaused: Boolean = false,
        policy: MissedPolicy = MissedPolicy.SEND_AS_SOON_AS_POSSIBLE,
        notifications: Boolean = true,
    ) = evaluator.evaluate(
        DueOccurrenceContext(
            targetAt = target,
            deadlineAt = deadline,
            now = now,
            schedulePaused = schedulePaused,
            globallyPaused = globallyPaused,
            missedPolicy = policy,
            actionNotificationAvailable = notifications,
        ),
    )
}
