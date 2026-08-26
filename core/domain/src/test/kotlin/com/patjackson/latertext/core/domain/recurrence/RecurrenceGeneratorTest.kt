package com.patjackson.latertext.core.domain.recurrence

import com.patjackson.latertext.core.model.MonthlyAdjustment
import com.patjackson.latertext.core.model.MonthlyDayPolicy
import com.patjackson.latertext.core.model.RecurrenceEnd
import com.patjackson.latertext.core.model.RecurrenceFrequency
import com.patjackson.latertext.core.model.RecurrenceRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RecurrenceGeneratorTest {
    private val generator = RecurrenceGenerator()

    @Test
    fun `once emits exactly the start slot`() {
        val slots = generator.slots(rule(RecurrenceFrequency.ONCE)).toList()
        assertEquals(listOf(LocalDateTime.of(2026, 1, 1, 17, 0)), slots.map { it.nominalLocalDateTime })
        assertEquals(listOf(0L), slots.map { it.sequence })
    }

    @Test
    fun `daily interval and occurrence count use emitted slots`() {
        val slots = generator.slots(
            rule(
                frequency = RecurrenceFrequency.DAILY,
                interval = 2,
                end = RecurrenceEnd.AfterOccurrences(3),
            ),
        ).toList()
        assertEquals(
            listOf("2026-01-01T17:00", "2026-01-03T17:00", "2026-01-05T17:00"),
            slots.map { it.nominalLocalDateTime.toString() },
        )
    }

    @Test
    fun `end date is inclusive`() {
        val slots = generator.slots(
            rule(end = RecurrenceEnd.OnDate(LocalDate.of(2026, 1, 3))),
        ).toList()
        assertEquals(3, slots.size)
        assertEquals(LocalDate.of(2026, 1, 3), slots.last().nominalLocalDateTime.toLocalDate())
    }

    @Test
    fun `weekly recurrence is anchored to start week and ordered Monday through Sunday`() {
        val slots = generator.slots(
            rule(
                frequency = RecurrenceFrequency.WEEKLY,
                startDate = LocalDate.of(2026, 1, 7), // Wednesday
                days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                end = RecurrenceEnd.AfterOccurrences(5),
            ),
        ).toList()
        assertEquals(
            listOf("2026-01-07", "2026-01-09", "2026-01-12", "2026-01-14", "2026-01-16"),
            slots.map { it.nominalLocalDateTime.toLocalDate().toString() },
        )
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), slots.map { it.sequence })
    }

    @Test
    fun `multi-week interval skips intervening anchor weeks`() {
        val slots = generator.slots(
            rule(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 2,
                startDate = LocalDate.of(2026, 1, 5),
                days = setOf(DayOfWeek.MONDAY),
                end = RecurrenceEnd.AfterOccurrences(3),
            ),
        ).toList()
        assertEquals(
            listOf("2026-01-05", "2026-01-19", "2026-02-02"),
            slots.map { it.nominalLocalDateTime.toLocalDate().toString() },
        )
    }

    @Test
    fun `monthly last-day policy records adjustment including leap February`() {
        val slots = generator.slots(
            rule(
                frequency = RecurrenceFrequency.MONTHLY,
                startDate = LocalDate.of(2027, 1, 31),
                dayOfMonth = 31,
                monthlyPolicy = MonthlyDayPolicy.LAST_DAY_OF_MONTH,
                end = RecurrenceEnd.AfterOccurrences(15),
            ),
        ).toList()
        assertEquals(LocalDate.of(2027, 2, 28), slots[1].nominalLocalDateTime.toLocalDate())
        assertEquals(MonthlyAdjustment.USED_LAST_DAY_OF_MONTH, slots[1].monthlyAdjustment)
        assertEquals(LocalDate.of(2028, 2, 29), slots[13].nominalLocalDateTime.toLocalDate())
    }

    @Test
    fun `monthly skip policy omits nonexistent dates without gaps in logical sequence`() {
        val slots = generator.slots(
            rule(
                frequency = RecurrenceFrequency.MONTHLY,
                startDate = LocalDate.of(2026, 1, 31),
                dayOfMonth = 31,
                monthlyPolicy = MonthlyDayPolicy.SKIP_MONTH,
                end = RecurrenceEnd.AfterOccurrences(4),
            ),
        ).toList()
        assertEquals(
            listOf("2026-01-31", "2026-03-31", "2026-05-31", "2026-07-31"),
            slots.map { it.nominalLocalDateTime.toLocalDate().toString() },
        )
        assertEquals(listOf(0L, 1L, 2L, 3L), slots.map { it.sequence })
    }

    private fun rule(
        frequency: RecurrenceFrequency = RecurrenceFrequency.DAILY,
        interval: Int = 1,
        startDate: LocalDate = LocalDate.of(2026, 1, 1),
        days: Set<DayOfWeek> = emptySet(),
        dayOfMonth: Int = startDate.dayOfMonth,
        monthlyPolicy: MonthlyDayPolicy = MonthlyDayPolicy.LAST_DAY_OF_MONTH,
        end: RecurrenceEnd = RecurrenceEnd.Never,
    ) = RecurrenceRule(
        frequency = frequency,
        interval = interval,
        startDate = startDate,
        localTime = LocalTime.of(17, 0),
        daysOfWeek = days,
        dayOfMonth = dayOfMonth,
        end = end,
        zoneId = "America/Los_Angeles",
        monthlyDayPolicy = monthlyPolicy,
    )
}
