package com.patjackson.latertext

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import works.sloop.swip.*
import works.sloop.swip.pipeline.LatencyTier
import works.sloop.swip.schema.latertext.*

class DiagnosticPrivacyTest {
    @Test fun `raw SDK fields and identities cannot enter the drawer`() {
        val event = LatertextScreenViewed(LatertextScreenViewed.Screen.COMPOSER)
        val raw = DebugRecord.Enqueued("event-secret", event.schema,
            mapOf("recipient" to JsonPrimitive("2025550142"), "message" to JsonPrimitive("private")),
            mapOf("secret" to JsonPrimitive("private")), "person", "session", LatencyTier.NORMAL, false)
        val record = requireNotNull(sanitizedDiagnosticRecord(raw, event))
        assertEquals(event.props, record.propsRaw)
        assertEquals("", record.eventId)
        assertNull(record.distinctId)
        assertNull(record.sessionId)
        assertNull(record.propsStripped)
        assertNull(sanitizedDiagnosticRecord(raw, null))
        assertNull(sanitizedDiagnosticRecord(raw.copy(schema = "unknown"), event))
        assertNull(sanitizedDiagnosticRecord(DebugRecord.Purged("private"), event))
    }

    @Test fun `all diagnostic event properties are bounded product values`() {
        val events = AppScreen.entries.map { LatertextScreenViewed(LatertextScreenViewed.Screen.valueOf(it.name)) } +
            DiagnosticOperation.entries.map { LatertextScheduleChanged(LatertextScheduleChanged.Operation.valueOf(it.name)) } +
            LatertextScheduleSaved(LatertextScheduleSaved.Outcome.SAVED, LatertextScheduleSaved.Frequency.WEEKLY, true)
        events.forEach { event ->
            assertTrue(event.schema in LatertextAnonymousSafe.schemas)
            assertTrue(event.props.keys.all { it in setOf("screen", "operation", "outcome", "frequency", "has_media") })
        }
        val config = LatertextSwip.androidDev()
        assertEquals(DeliveryMode.LOCAL_ONLY, config.deliveryMode)
        assertEquals("", config.stableEndpoint)
        assertEquals("dev", config.channel)
    }
}
