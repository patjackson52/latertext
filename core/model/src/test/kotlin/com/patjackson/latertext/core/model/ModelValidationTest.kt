package com.patjackson.latertext.core.model

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant

class ModelValidationTest {
    @Test
    fun `stable identifiers reject blank values`() {
        assertThrows(IllegalArgumentException::class.java) { ScheduleId(" ") }
        assertThrows(IllegalArgumentException::class.java) { OccurrenceId("") }
        assertThrows(IllegalArgumentException::class.java) { ClaimToken("\t") }
    }

    @Test
    fun `weekly rules require weekdays`() {
        assertThrows(IllegalArgumentException::class.java) {
            rule(frequency = RecurrenceFrequency.WEEKLY)
        }
        rule(
            frequency = RecurrenceFrequency.WEEKLY,
            daysOfWeek = setOf(DayOfWeek.MONDAY),
        )
    }

    @Test
    fun `rules reject invalid intervals jitter and end dates`() {
        assertThrows(IllegalArgumentException::class.java) { rule(interval = 0) }
        assertThrows(IllegalArgumentException::class.java) { rule(jitterRangeMinutes = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            rule(end = RecurrenceEnd.OnDate(LocalDate.of(2025, 12, 31)))
        }
        assertThrows(IllegalArgumentException::class.java) { RecurrenceEnd.AfterOccurrences(0) }
    }

    @Test
    fun `manual occurrence has no logical recurrence key`() {
        val manual = OccurrenceSnapshot(
            id = OccurrenceId("manual"),
            scheduleId = ScheduleId("schedule"),
            logicalKey = null,
            trigger = OccurrenceTrigger.MANUAL,
            updatedAt = Instant.EPOCH,
        )
        org.junit.jupiter.api.Assertions.assertNull(manual.logicalKey)
        assertThrows(IllegalArgumentException::class.java) {
            manual.copy(trigger = OccurrenceTrigger.SCHEDULED)
        }
    }

    private fun rule(
        frequency: RecurrenceFrequency = RecurrenceFrequency.DAILY,
        interval: Int = 1,
        daysOfWeek: Set<DayOfWeek> = emptySet(),
        jitterRangeMinutes: Int = 0,
        end: RecurrenceEnd = RecurrenceEnd.Never,
    ) = RecurrenceRule(
        frequency = frequency,
        interval = interval,
        startDate = LocalDate.of(2026, 1, 1),
        localTime = LocalTime.of(17, 0),
        daysOfWeek = daysOfWeek,
        end = end,
        zoneId = "America/Los_Angeles",
        jitterRangeMinutes = jitterRangeMinutes,
    )
}
