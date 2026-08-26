package com.patjackson.latertext.core.domain.projection

import com.patjackson.latertext.core.model.HistoryOutcomeProjection
import com.patjackson.latertext.core.model.OccurrenceSnapshot
import com.patjackson.latertext.core.model.OccurrenceState
import com.patjackson.latertext.core.model.ScheduleId
import com.patjackson.latertext.core.model.UpcomingOccurrenceProjection
import com.patjackson.latertext.core.model.isTerminal
import java.time.Instant

class OccurrenceProjectionFactory {
    fun upcoming(
        occurrence: OccurrenceSnapshot,
        targetAt: Instant,
        retryAt: Instant? = occurrence.nextActionAt,
    ): UpcomingOccurrenceProjection = UpcomingOccurrenceProjection(
        occurrenceId = occurrence.id,
        scheduleId = occurrence.scheduleId,
        targetAt = targetAt,
        state = occurrence.state,
        sendOutcome = occurrence.sendOutcome,
        deliveryOutcome = occurrence.deliveryOutcome,
        requiresUserAction = occurrence.state in setOf(
            OccurrenceState.READY_FOR_USER,
            OccurrenceState.OPENED_IN_LATER_TEXT,
            OccurrenceState.PARTIAL_AMBIGUOUS,
            OccurrenceState.FAILED_TERMINAL,
        ),
        nextActionAt = retryAt ?: targetAt,
    )

    fun history(occurrence: OccurrenceSnapshot): HistoryOutcomeProjection =
        HistoryOutcomeProjection(
            occurrenceId = occurrence.id,
            sendOutcome = occurrence.sendOutcome,
            deliveryOutcome = occurrence.deliveryOutcome,
            assistedOutcomeUnverified = occurrence.state == OccurrenceState.SHARED_TO_MESSAGING_APP,
            terminal = occurrence.state.isTerminal,
        )
}
