package com.patjackson.latertext.core.domain.recurrence

import com.patjackson.latertext.core.model.DstResolution
import com.patjackson.latertext.core.model.RecurrenceFrequency
import com.patjackson.latertext.core.model.RecurrenceRule
import com.patjackson.latertext.core.model.ZonePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class CivilTimeResolverTest {
    private val resolver = CivilTimeResolver()
    private val losAngeles = ZoneId.of("America/Los_Angeles")

    @Test
    fun `DST gap shifts to first valid local time rather than preserving minutes`() {
        val result = resolver.resolve(LocalDateTime.of(2026, 3, 8, 2, 30), losAngeles)
        assertEquals(LocalDateTime.of(2026, 3, 8, 3, 0), result.resolvedLocalDateTime)
        assertEquals(ZoneOffset.ofHours(-7), result.selectedOffset)
        assertEquals("2026-03-08T10:00:00Z", result.instant.toString())
        assertEquals(DstResolution.GAP_SHIFTED_FORWARD, result.dstResolution)
    }

    @Test
    fun `DST overlap chooses earlier instant offset`() {
        val result = resolver.resolve(LocalDateTime.of(2026, 11, 1, 1, 30), losAngeles)
        assertEquals(LocalDateTime.of(2026, 11, 1, 1, 30), result.resolvedLocalDateTime)
        assertEquals(ZoneOffset.ofHours(-7), result.selectedOffset)
        assertEquals("2026-11-01T08:30:00Z", result.instant.toString())
        assertEquals(DstResolution.OVERLAP_EARLIER_OFFSET, result.dstResolution)
    }

    @Test
    fun `fixed zone ignores active device zone while follow policy uses it`() {
        val fixed = baseRule(ZonePolicy.FIXED_ZONE)
        val follow = baseRule(ZonePolicy.FOLLOW_DEVICE_ZONE)
        assertEquals(losAngeles, resolver.selectedZone(fixed, ZoneId.of("Europe/London")))
        assertEquals(ZoneId.of("Europe/London"), resolver.selectedZone(follow, ZoneId.of("Europe/London")))
    }

    private fun baseRule(policy: ZonePolicy) = RecurrenceRule(
        frequency = RecurrenceFrequency.DAILY,
        startDate = LocalDate.of(2026, 1, 1),
        localTime = LocalTime.NOON,
        zonePolicy = policy,
        zoneId = losAngeles.id,
    )
}
