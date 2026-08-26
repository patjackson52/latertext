package com.patjackson.latertext.core.domain.recurrence

import com.patjackson.latertext.core.model.MaterializationLimits
import com.patjackson.latertext.core.model.RecurrenceEnd
import com.patjackson.latertext.core.model.RecurrenceFrequency
import com.patjackson.latertext.core.model.RecurrenceRule
import com.patjackson.latertext.core.model.RuleRevisionId
import com.patjackson.latertext.core.model.ScheduleId
import com.patjackson.latertext.core.model.ZonePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class OccurrenceMaterializerTest {
    private val scheduleId = ScheduleId("schedule-1")
    private val revisionId = RuleRevisionId("rule-1")
    private val zone = ZoneId.of("UTC")

    @Test
    fun `materialization persists deterministic jitter target and deadline`() {
        val random = QueueRandom(listOf(-5, 0, 5, -2, 2))
        val result = materializer(random).materialize(
            scheduleId = scheduleId,
            ruleRevisionId = revisionId,
            rule = rule(jitter = 5),
            activeDeviceZone = zone,
            now = Instant.parse("2026-01-01T00:00:00Z"),
            gracePeriod = Duration.ofHours(4),
            limits = MaterializationLimits(5, 5),
        )
        assertEquals(listOf(-5, 0, 5, -2, 2), result.added.take(5).map { it.jitterOffsetMinutes })
        assertEquals("2026-01-01T16:55:00Z", result.added.first().targetAt.toString())
        assertEquals("2026-01-01T20:55:00Z", result.added.first().deadlineAt.toString())
        assertEquals(5, random.calls)
    }

    @Test
    fun `existing occurrence timing is retained and does not consume random draw`() {
        val initialRandom = QueueRandom(listOf(3, 1, -1, 2, -2))
        val first = materializer(initialRandom).materialize(
            scheduleId,
            revisionId,
            rule(jitter = 3),
            zone,
            Instant.parse("2026-01-01T00:00:00Z"),
            Duration.ofHours(4),
            limits = MaterializationLimits(5, 5),
        )
        val secondRandom = QueueRandom(listOf(0))
        val second = materializer(secondRandom).materialize(
            scheduleId,
            revisionId,
            rule(jitter = 3),
            zone,
            Instant.parse("2026-01-01T00:00:00Z"),
            Duration.ofHours(4),
            existing = first.occurrences,
            limits = MaterializationLimits(5, 5),
        )
        assertEquals(first.occurrences, second.occurrences)
        assertTrue(second.added.isEmpty())
        assertEquals(0, secondRandom.calls)
    }

    @Test
    fun `past jittered target is skipped rather than clamped`() {
        val random = QueueRandom(listOf(-10, 0, 0, 0, 0, 0))
        val result = materializer(random).materialize(
            scheduleId,
            revisionId,
            rule(jitter = 10),
            zone,
            Instant.parse("2026-01-01T16:55:00Z"),
            Duration.ofHours(4),
            limits = MaterializationLimits(5, 5),
        )
        assertEquals(0L, result.skippedPastKeys.single().sequence)
        assertEquals(1L, result.added.first().key.sequence)
        assertEquals("2026-01-02T17:00:00Z", result.added.first().targetAt.toString())
    }

    @Test
    fun `monthly rules extend beyond ninety days to preserve five previews`() {
        val monthly = RecurrenceRule(
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = LocalDate.of(2026, 1, 1),
            localTime = LocalTime.of(17, 0),
            zoneId = "UTC",
        )
        val result = materializer(QueueRandom(emptyList())).materialize(
            scheduleId,
            revisionId,
            monthly,
            zone,
            Instant.parse("2026-01-01T00:00:00Z"),
            Duration.ofHours(4),
            limits = MaterializationLimits(5, 32, Duration.ofDays(90)),
        )
        assertEquals(5, result.added.size)
        assertEquals(LocalDate.of(2026, 5, 1), result.added.last().nominalLocalDateTime.toLocalDate())
    }

    @Test
    fun `rolling horizon never exceeds thirty two future occurrences`() {
        val result = materializer(QueueRandom(emptyList())).materialize(
            scheduleId,
            revisionId,
            rule(),
            zone,
            Instant.parse("2026-01-01T00:00:00Z"),
            Duration.ofHours(4),
        )
        assertEquals(32, result.added.size)
    }

    @Test
    fun `jitter that can collide adjacent daily occurrences is rejected`() {
        val invalid = rule(jitter = 720)
        val error = assertThrows(InvalidJitterRangeException::class.java) {
            materializer(QueueRandom(emptyList())).materialize(
                scheduleId,
                revisionId,
                invalid,
                zone,
                Instant.parse("2026-01-01T00:00:00Z"),
                Duration.ofHours(4),
            )
        }
        assertEquals(719, error.validation.maximumSafeRangeMinutes)
    }

    @Test
    fun `finite once schedule allows any configured jitter because it cannot reorder`() {
        val once = RecurrenceRule(
            frequency = RecurrenceFrequency.ONCE,
            startDate = LocalDate.of(2026, 1, 2),
            localTime = LocalTime.NOON,
            zoneId = "UTC",
            jitterRangeMinutes = 1_000,
            end = RecurrenceEnd.AfterOccurrences(1),
        )
        val result = materializer(QueueRandom(listOf(0))).materialize(
            scheduleId,
            revisionId,
            once,
            zone,
            Instant.parse("2026-01-01T00:00:00Z"),
            Duration.ZERO,
        )
        assertEquals(1, result.added.size)
    }

    @Test
    fun `materialized follow-zone occurrence remains frozen after device zone change`() {
        val followRule = RecurrenceRule(
            frequency = RecurrenceFrequency.DAILY,
            startDate = LocalDate.of(2026, 1, 1),
            localTime = LocalTime.of(17, 0),
            zonePolicy = ZonePolicy.FOLLOW_DEVICE_ZONE,
            zoneId = "America/Los_Angeles",
        )
        val first = materializer(QueueRandom(emptyList())).materialize(
            scheduleId,
            revisionId,
            followRule,
            ZoneId.of("America/Los_Angeles"),
            Instant.parse("2026-01-01T00:00:00Z"),
            Duration.ZERO,
            limits = MaterializationLimits(5, 5),
        )
        val reconciled = materializer(QueueRandom(emptyList())).materialize(
            scheduleId,
            revisionId,
            followRule,
            ZoneId.of("America/New_York"),
            Instant.parse("2026-01-01T00:00:00Z"),
            Duration.ZERO,
            existing = first.occurrences,
            limits = MaterializationLimits(5, 5),
        )
        assertEquals(first.occurrences, reconciled.occurrences)
        assertEquals("America/Los_Angeles", reconciled.occurrences.first().zoneId)
    }

    private fun rule(jitter: Int = 0) = RecurrenceRule(
        frequency = RecurrenceFrequency.DAILY,
        startDate = LocalDate.of(2026, 1, 1),
        localTime = LocalTime.of(17, 0),
        zoneId = "UTC",
        jitterRangeMinutes = jitter,
    )

    private fun materializer(random: JitterRandom) = OccurrenceMaterializer(random)

    private class QueueRandom(values: List<Int>) : JitterRandom {
        private val queue = ArrayDeque(values)
        var calls: Int = 0
            private set

        override fun nextInt(fromInclusive: Int, toInclusive: Int): Int {
            calls++
            val value = queue.removeFirst()
            require(value in fromInclusive..toInclusive)
            return value
        }
    }
}
