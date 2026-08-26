package com.patjackson.latertext.core.domain.recurrence

import com.patjackson.latertext.core.model.DstResolution
import com.patjackson.latertext.core.model.RecurrenceRule
import com.patjackson.latertext.core.model.ResolvedCivilTime
import com.patjackson.latertext.core.model.ZonePolicy
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Resolves a recurrence's civil time with LaterText's fixed gap/overlap semantics. */
class CivilTimeResolver {
    fun selectedZone(rule: RecurrenceRule, activeDeviceZone: ZoneId): ZoneId =
        when (rule.zonePolicy) {
            ZonePolicy.FOLLOW_DEVICE_ZONE -> activeDeviceZone
            ZonePolicy.FIXED_ZONE -> ZoneId.of(rule.zoneId)
        }

    fun resolve(
        localDateTime: LocalDateTime,
        zone: ZoneId,
    ): ResolvedCivilTime {
        val rules = zone.rules
        val offsets = rules.getValidOffsets(localDateTime)
        val (resolvedDateTime, offset, resolution) = when {
            offsets.size == 1 -> Triple(localDateTime, offsets.single(), DstResolution.EXACT)
            offsets.isEmpty() -> {
                val transition = checkNotNull(rules.getTransition(localDateTime)) {
                    "Zone rules reported a gap without a transition"
                }
                Triple(
                    transition.dateTimeAfter,
                    transition.offsetAfter,
                    DstResolution.GAP_SHIFTED_FORWARD,
                )
            }
            else -> Triple(
                localDateTime,
                offsets.first(),
                DstResolution.OVERLAP_EARLIER_OFFSET,
            )
        }
        val zoned = ZonedDateTime.ofLocal(resolvedDateTime, zone, offset)
        return ResolvedCivilTime(
            nominalLocalDateTime = localDateTime,
            resolvedLocalDateTime = resolvedDateTime,
            zoneId = zone.id,
            selectedOffset = offset,
            instant = zoned.toInstant(),
            dstResolution = resolution,
        )
    }
}
