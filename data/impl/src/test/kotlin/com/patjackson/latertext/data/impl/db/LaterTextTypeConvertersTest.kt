package com.patjackson.latertext.data.impl.db

import com.patjackson.latertext.data.api.AttachmentIntakeSource
import com.patjackson.latertext.data.api.DeliveryOutcome
import com.patjackson.latertext.data.api.OccurrenceState
import com.patjackson.latertext.data.api.OutboxState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LaterTextTypeConvertersTest {
    private val converters = LaterTextTypeConverters()

    @Test
    fun stableEnumsUseTheirNamesAndRoundTrip() {
        assertEquals("PHOTO_PICKER", converters.intakeSourceToString(AttachmentIntakeSource.PHOTO_PICKER))
        assertEquals(
            AttachmentIntakeSource.PHOTO_PICKER,
            converters.stringToIntakeSource("PHOTO_PICKER"),
        )
        assertEquals(
            OccurrenceState.PARTIAL_AMBIGUOUS,
            converters.stringToOccurrenceState(
                converters.occurrenceStateToString(OccurrenceState.PARTIAL_AMBIGUOUS),
            ),
        )
        assertEquals(
            DeliveryOutcome.UNAVAILABLE,
            converters.stringToDeliveryOutcome(
                converters.deliveryOutcomeToString(DeliveryOutcome.UNAVAILABLE),
            ),
        )
        assertEquals(
            OutboxState.CLAIMED,
            converters.stringToOutboxState(converters.outboxStateToString(OutboxState.CLAIMED)),
        )
    }
}
