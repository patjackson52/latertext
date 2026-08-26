package com.patjackson.latertext.core.domain.projection

import com.patjackson.latertext.core.model.DeliveryOutcome
import com.patjackson.latertext.core.model.LogicalOccurrenceKey
import com.patjackson.latertext.core.model.OccurrenceId
import com.patjackson.latertext.core.model.OccurrenceSnapshot
import com.patjackson.latertext.core.model.OccurrenceState
import com.patjackson.latertext.core.model.RuleRevisionId
import com.patjackson.latertext.core.model.ScheduleId
import com.patjackson.latertext.core.model.SendOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class OccurrenceProjectionFactoryTest {
    private val factory = OccurrenceProjectionFactory()
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `retry time is primary upcoming action`() {
        val retryAt = now.plusSeconds(600)
        val projection = factory.upcoming(
            occurrence = snapshot().copy(
                state = OccurrenceState.RETRY_WAIT,
                sendOutcome = SendOutcome.PENDING,
                nextActionAt = retryAt,
            ),
            targetAt = now.minusSeconds(300),
        )
        assertEquals(retryAt, projection.nextActionAt)
        assertFalse(projection.requiresUserAction)
    }

    @Test
    fun `assisted shared result remains unverified and terminal`() {
        val projection = factory.history(
            snapshot().copy(
                state = OccurrenceState.SHARED_TO_MESSAGING_APP,
                sendOutcome = SendOutcome.SHARED_UNVERIFIED,
                deliveryOutcome = DeliveryOutcome.NOT_REQUESTED,
            ),
        )
        assertTrue(projection.assistedOutcomeUnverified)
        assertTrue(projection.terminal)
        assertEquals(DeliveryOutcome.NOT_REQUESTED, projection.deliveryOutcome)
    }

    private fun snapshot() = OccurrenceSnapshot(
        id = OccurrenceId("occurrence"),
        scheduleId = ScheduleId("schedule"),
        logicalKey = LogicalOccurrenceKey(ScheduleId("schedule"), RuleRevisionId("rule"), 0),
        updatedAt = now,
    )
}
