package com.patjackson.latertext.core.domain.reducer

import com.patjackson.latertext.core.model.ScheduleAttentionReason
import com.patjackson.latertext.core.model.ScheduleId
import com.patjackson.latertext.core.model.ScheduleSnapshot
import com.patjackson.latertext.core.model.ScheduleState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class ScheduleReducerTest {
    private val reducer = ScheduleReducer()
    private val start = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `pause and resume are idempotent`() {
        val paused = reducer.reduce(snapshot(), ScheduleEvent.Pause(start.plusSeconds(1)))
        assertEquals(ScheduleState.PAUSED, paused.state)
        assertEquals(paused, reducer.reduce(paused, ScheduleEvent.Pause(start.plusSeconds(2))))

        val active = reducer.reduce(paused, ScheduleEvent.Resume(start.plusSeconds(3)))
        assertEquals(ScheduleState.ACTIVE, active.state)
        assertNull(active.pausedAt)
        assertEquals(active, reducer.reduce(active, ScheduleEvent.Resume(start.plusSeconds(4))))
    }

    @Test
    fun `attention preserves and restores paused state`() {
        val paused = reducer.reduce(snapshot(), ScheduleEvent.Pause(start.plusSeconds(1)))
        val attention = reducer.reduce(
            paused,
            ScheduleEvent.RequireAttention(
                ScheduleAttentionReason.SIM_UNAVAILABLE,
                start.plusSeconds(2),
            ),
        )
        assertEquals(ScheduleState.NEEDS_ATTENTION, attention.state)
        assertEquals(ScheduleState.PAUSED, attention.stateBeforeAttention)
        assertEquals(ScheduleAttentionReason.SIM_UNAVAILABLE, attention.attentionReason)

        val restored = reducer.reduce(attention, ScheduleEvent.ResolveAttention(start.plusSeconds(3)))
        assertEquals(ScheduleState.PAUSED, restored.state)
        assertNull(restored.attentionReason)
    }

    @Test
    fun `terminal schedules reject incompatible transitions`() {
        val cancelled = reducer.reduce(snapshot(), ScheduleEvent.Cancel(start.plusSeconds(1)))
        assertEquals(cancelled, reducer.reduce(cancelled, ScheduleEvent.Cancel(start.plusSeconds(2))))
        assertThrows(DomainTransitionException::class.java) {
            reducer.reduce(cancelled, ScheduleEvent.Resume(start.plusSeconds(3)))
        }
        assertThrows(DomainTransitionException::class.java) {
            reducer.reduce(cancelled, ScheduleEvent.Complete(start.plusSeconds(3)))
        }
    }

    private fun snapshot() = ScheduleSnapshot(
        id = ScheduleId("schedule"),
        updatedAt = start,
    )
}
