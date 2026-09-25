package com.patjackson.latertext

import com.patjackson.latertext.data.api.DeliveryOutcome
import com.patjackson.latertext.data.api.DstResolution
import com.patjackson.latertext.data.api.OccurrenceRecord
import com.patjackson.latertext.data.api.OccurrenceState
import com.patjackson.latertext.data.api.SendOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StatusProjectionTest {
    @Test
    fun `delivered occurrence keeps send and delivery dimensions separate`() {
        val occurrence = occurrence(
            state = OccurrenceState.DELIVERED,
            send = SendOutcome.SENT_TO_CARRIER,
            delivery = DeliveryOutcome.DELIVERED,
        )

        assertEquals("Sent to carrier", occurrence.sendStatusDisplay())
        assertEquals("Delivered", occurrence.deliveryOutcome.displayName())
    }

    @Test
    fun `enqueued occurrence does not claim carrier acceptance`() {
        val occurrence = occurrence(
            state = OccurrenceState.SENDING,
            send = SendOutcome.PENDING,
            delivery = DeliveryOutcome.NOT_REQUESTED,
        )

        assertEquals("Waiting for carrier confirmation", occurrence.sendStatusDisplay())
        assertEquals("Not requested yet", occurrence.deliveryOutcome.displayName())
    }

    @Test
    fun `missing callback is honest about duplicate risk`() {
        val occurrence = occurrence(
            state = OccurrenceState.PARTIAL_AMBIGUOUS,
            send = SendOutcome.PARTIAL_OR_AMBIGUOUS,
            delivery = DeliveryOutcome.NOT_REQUESTED,
        )

        assertEquals("Status unknown · message may have sent", occurrence.sendStatusDisplay())
    }

    private fun occurrence(
        state: OccurrenceState,
        send: SendOutcome,
        delivery: DeliveryOutcome,
    ) = OccurrenceRecord(
        id = "occurrence",
        scheduleId = "schedule",
        ruleRevisionId = "rule",
        contentRevisionId = "content",
        logicalRecurrenceKey = "schedule:rule:0",
        nominalEpochDay = 1,
        nominalSecondsOfDay = 1,
        selectedZoneId = "UTC",
        selectedOffsetSeconds = 0,
        dstResolution = DstResolution.EXACT,
        jitterOffsetMinutes = 0,
        targetAtEpochMillis = 1,
        deadlineAtEpochMillis = 2,
        state = state,
        sendOutcome = send,
        deliveryOutcome = delivery,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )
}
