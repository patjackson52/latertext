package com.patjackson.latertext.core.domain.usecase

import com.patjackson.latertext.core.domain.recurrence.OccurrenceMaterializer
import com.patjackson.latertext.core.model.MaterializationLimits
import com.patjackson.latertext.core.model.OccurrencePreview
import com.patjackson.latertext.core.model.RecurrenceRule
import com.patjackson.latertext.core.model.RuleRevisionId
import com.patjackson.latertext.core.model.ScheduleId
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class SchedulePreviewUseCase(
    private val materializer: OccurrenceMaterializer,
) {
    /**
     * Produces concrete sampled occurrences. A caller that presents these offsets as actual next
     * sends must persist the same materialization result; a pre-save editor should instead present
     * the nominal times and jitter range without claiming a random offset.
     */
    fun preview(
        scheduleId: ScheduleId,
        ruleRevisionId: RuleRevisionId,
        rule: RecurrenceRule,
        activeDeviceZone: ZoneId,
        now: Instant,
        count: Int = 5,
    ): List<OccurrencePreview> {
        require(count in 1..32)
        return materializer.materialize(
            scheduleId = scheduleId,
            ruleRevisionId = ruleRevisionId,
            rule = rule,
            activeDeviceZone = activeDeviceZone,
            now = now,
            gracePeriod = Duration.ZERO,
            limits = MaterializationLimits(
                minimumFutureCount = count,
                maximumFutureCount = count,
            ),
        ).added.map {
            OccurrencePreview(
                key = it.key,
                nominalLocalDateTime = it.nominalLocalDateTime,
                targetAt = it.targetAt,
                jitterOffsetMinutes = it.jitterOffsetMinutes,
                monthlyAdjustment = it.monthlyAdjustment,
                dstResolution = it.dstResolution,
            )
        }
    }
}
