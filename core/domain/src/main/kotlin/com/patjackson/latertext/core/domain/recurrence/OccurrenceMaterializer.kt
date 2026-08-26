package com.patjackson.latertext.core.domain.recurrence

import com.patjackson.latertext.core.model.LogicalOccurrenceKey
import com.patjackson.latertext.core.model.MaterializationLimits
import com.patjackson.latertext.core.model.MaterializationResult
import com.patjackson.latertext.core.model.MaterializedOccurrence
import com.patjackson.latertext.core.model.RecurrenceRule
import com.patjackson.latertext.core.model.RuleRevisionId
import com.patjackson.latertext.core.model.ScheduleId
import com.patjackson.latertext.core.model.isTerminal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class OccurrenceMaterializer(
    private val random: JitterRandom,
    private val generator: RecurrenceGenerator = RecurrenceGenerator(),
    private val resolver: CivilTimeResolver = CivilTimeResolver(),
    private val jitterValidator: JitterPolicyValidator = JitterPolicyValidator(generator, resolver),
) {
    fun materialize(
        scheduleId: ScheduleId,
        ruleRevisionId: RuleRevisionId,
        rule: RecurrenceRule,
        activeDeviceZone: ZoneId,
        now: Instant,
        gracePeriod: Duration,
        existing: Collection<MaterializedOccurrence> = emptyList(),
        limits: MaterializationLimits = MaterializationLimits(),
    ): MaterializationResult {
        require(!gracePeriod.isNegative) { "Grace period cannot be negative" }
        val validation = jitterValidator.validate(rule, activeDeviceZone)
        if (!validation.valid) throw InvalidJitterRangeException(validation)

        val duplicate = existing.groupBy { it.key }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) { "Existing occurrences contain duplicate key ${duplicate?.key}" }
        require(existing.all {
            it.key.scheduleId == scheduleId && it.key.ruleRevisionId == ruleRevisionId
        }) { "Existing occurrence belongs to a different schedule or rule revision" }

        val existingByKey = existing.associateBy { it.key }
        val retained = existing.toMutableList()
        val added = mutableListOf<MaterializedOccurrence>()
        val skippedPast = mutableListOf<LogicalOccurrenceKey>()
        var futureOpenCount = existing.count { !it.state.isTerminal && !it.targetAt.isBefore(now) }
        val horizonEnd = now.plus(limits.maximumHorizon)
        val zone = resolver.selectedZone(rule, activeDeviceZone)

        for (slot in generator.slots(rule)) {
            if (futureOpenCount >= limits.maximumFutureCount) break
            val key = LogicalOccurrenceKey(scheduleId, ruleRevisionId, slot.sequence)
            val persisted = existingByKey[key]
            if (persisted != null) continue // Persisted jitter and timing are authoritative.

            val resolved = resolver.resolve(slot.nominalLocalDateTime, zone)
            val latestPossibleTarget = resolved.instant.plusSeconds(
                rule.jitterRangeMinutes.toLong() * 60L,
            )
            if (latestPossibleTarget.isBefore(now)) {
                // Old recurrence slots are not materialized and do not consume random values.
                continue
            }
            if (futureOpenCount >= limits.minimumFutureCount && resolved.instant.isAfter(horizonEnd)) break

            val jitter = if (rule.jitterRangeMinutes == 0) {
                0
            } else {
                random.nextInt(-rule.jitterRangeMinutes, rule.jitterRangeMinutes)
            }
            val target = resolved.instant.plusSeconds(jitter.toLong() * 60L)
            if (target.isBefore(now)) {
                skippedPast += key
                continue
            }

            val occurrence = MaterializedOccurrence(
                key = key,
                nominalLocalDateTime = slot.nominalLocalDateTime,
                resolvedLocalDateTime = resolved.resolvedLocalDateTime,
                zoneId = resolved.zoneId,
                selectedOffset = resolved.selectedOffset,
                monthlyAdjustment = slot.monthlyAdjustment,
                dstResolution = resolved.dstResolution,
                jitterOffsetMinutes = jitter,
                targetAt = target,
                deadlineAt = target.plus(gracePeriod),
            )
            retained += occurrence
            added += occurrence
            futureOpenCount++
        }

        return MaterializationResult(
            occurrences = retained.sortedWith(
                compareBy<MaterializedOccurrence> { it.targetAt }.thenBy { it.key.sequence },
            ),
            added = added.sortedBy { it.targetAt },
            skippedPastKeys = skippedPast,
        )
    }
}
