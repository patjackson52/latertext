package com.patjackson.latertext.core.domain.reducer

import com.patjackson.latertext.core.model.ScheduleAttentionReason
import com.patjackson.latertext.core.model.ScheduleSnapshot
import com.patjackson.latertext.core.model.ScheduleState
import com.patjackson.latertext.core.model.isTerminal
import java.time.Instant

sealed interface ScheduleEvent {
    val at: Instant

    data class Pause(override val at: Instant) : ScheduleEvent
    data class Resume(override val at: Instant) : ScheduleEvent
    data class RequireAttention(
        val reason: ScheduleAttentionReason,
        override val at: Instant,
    ) : ScheduleEvent
    data class ResolveAttention(override val at: Instant) : ScheduleEvent
    data class Complete(override val at: Instant) : ScheduleEvent
    data class Cancel(override val at: Instant) : ScheduleEvent
}

class ScheduleReducer {
    fun reduce(snapshot: ScheduleSnapshot, event: ScheduleEvent): ScheduleSnapshot {
        require(!event.at.isBefore(snapshot.updatedAt)) { "Schedule event cannot move time backwards" }
        return when (event) {
            is ScheduleEvent.Pause -> pause(snapshot, event)
            is ScheduleEvent.Resume -> resume(snapshot, event)
            is ScheduleEvent.RequireAttention -> requireAttention(snapshot, event)
            is ScheduleEvent.ResolveAttention -> resolveAttention(snapshot, event)
            is ScheduleEvent.Complete -> complete(snapshot, event)
            is ScheduleEvent.Cancel -> cancel(snapshot, event)
        }
    }

    private fun pause(state: ScheduleSnapshot, event: ScheduleEvent.Pause): ScheduleSnapshot = when {
        state.state == ScheduleState.PAUSED -> state
        state.state == ScheduleState.ACTIVE -> state.copy(
            state = ScheduleState.PAUSED,
            pausedAt = event.at,
            updatedAt = event.at,
        )
        else -> invalid(state, "pause")
    }

    private fun resume(state: ScheduleSnapshot, event: ScheduleEvent.Resume): ScheduleSnapshot = when {
        state.state == ScheduleState.ACTIVE -> state
        state.state == ScheduleState.PAUSED -> state.copy(
            state = ScheduleState.ACTIVE,
            pausedAt = null,
            updatedAt = event.at,
        )
        else -> invalid(state, "resume")
    }

    private fun requireAttention(
        state: ScheduleSnapshot,
        event: ScheduleEvent.RequireAttention,
    ): ScheduleSnapshot = when {
        state.state.isTerminal -> invalid(state, "require attention")
        state.state == ScheduleState.NEEDS_ATTENTION -> state.copy(
            attentionReason = event.reason,
            updatedAt = event.at,
        )
        else -> state.copy(
            state = ScheduleState.NEEDS_ATTENTION,
            attentionReason = event.reason,
            stateBeforeAttention = state.state,
            updatedAt = event.at,
        )
    }

    private fun resolveAttention(
        state: ScheduleSnapshot,
        event: ScheduleEvent.ResolveAttention,
    ): ScheduleSnapshot {
        if (state.state != ScheduleState.NEEDS_ATTENTION) return invalid(state, "resolve attention")
        val restored = state.stateBeforeAttention ?: ScheduleState.ACTIVE
        return state.copy(
            state = restored,
            attentionReason = null,
            stateBeforeAttention = null,
            updatedAt = event.at,
        )
    }

    private fun complete(state: ScheduleSnapshot, event: ScheduleEvent.Complete): ScheduleSnapshot = when {
        state.state == ScheduleState.COMPLETED -> state
        state.state == ScheduleState.CANCELLED -> invalid(state, "complete")
        else -> state.copy(
            state = ScheduleState.COMPLETED,
            pausedAt = null,
            attentionReason = null,
            stateBeforeAttention = null,
            updatedAt = event.at,
        )
    }

    private fun cancel(state: ScheduleSnapshot, event: ScheduleEvent.Cancel): ScheduleSnapshot = when {
        state.state == ScheduleState.CANCELLED -> state
        state.state == ScheduleState.COMPLETED -> invalid(state, "cancel")
        else -> state.copy(
            state = ScheduleState.CANCELLED,
            pausedAt = null,
            attentionReason = null,
            stateBeforeAttention = null,
            updatedAt = event.at,
        )
    }

    private fun invalid(state: ScheduleSnapshot, action: String): Nothing =
        throw DomainTransitionException("Cannot $action schedule ${state.id.value} from ${state.state}")
}
