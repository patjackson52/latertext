package com.patjackson.latertext.platform.android.execution

import com.patjackson.latertext.core.domain.recurrence.DefaultJitterRandom
import com.patjackson.latertext.core.domain.recurrence.OccurrenceMaterializer
import com.patjackson.latertext.core.model.LogicalOccurrenceKey
import com.patjackson.latertext.core.model.MaterializedOccurrence
import com.patjackson.latertext.core.model.MonthlyAdjustment
import com.patjackson.latertext.core.model.RecurrenceEnd
import com.patjackson.latertext.core.model.RecurrenceRule
import com.patjackson.latertext.core.model.RuleRevisionId
import com.patjackson.latertext.core.model.ScheduleId
import com.patjackson.latertext.data.api.EndCondition
import com.patjackson.latertext.data.api.OccurrenceRecord
import com.patjackson.latertext.data.api.OccurrenceRepository
import com.patjackson.latertext.data.api.OccurrenceState
import com.patjackson.latertext.data.api.RuleRevisionRecord
import com.patjackson.latertext.data.api.ScheduleRepository
import com.patjackson.latertext.data.api.ScheduleState
import com.patjackson.latertext.platform.api.AppClock
import com.patjackson.latertext.platform.api.DeviceZoneProvider
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MaterializationReconciliationResult(
    val schedulesInspected: Int,
    val occurrencesAdded: Int,
)

/** Replenishes the persisted 5..32 occurrence rolling window without rerolling existing jitter. */
class OccurrenceMaterializationCoordinator(
    private val schedules: ScheduleRepository,
    private val occurrences: OccurrenceRepository,
    private val clock: AppClock,
    private val deviceZone: DeviceZoneProvider,
    private val materializer: OccurrenceMaterializer = OccurrenceMaterializer(DefaultJitterRandom()),
    private val queryLimit: Int = 500,
) {
    private val mutex = Mutex()

    suspend fun replenishAll(): MaterializationReconciliationResult = mutex.withLock {
        val now = clock.now()
        val active = schedules.listAll(queryLimit).filter {
            it.schedule.state == ScheduleState.ACTIVE &&
                it.activeRule != null && it.activeContent != null
        }
        var addedCount = 0
        active.forEach { graph ->
            val ruleRecord = requireNotNull(graph.activeRule)
            val content = requireNotNull(graph.activeContent)
            val existing = graph.occurrences
                .filter { it.ruleRevisionId == ruleRecord.id }
                .map(OccurrenceRecord::toMaterialized)
            val added = materializer.materialize(
                scheduleId = ScheduleId(graph.schedule.id),
                ruleRevisionId = RuleRevisionId(ruleRecord.id),
                rule = ruleRecord.toDomainRule(),
                activeDeviceZone = deviceZone.currentZone(),
                now = now,
                gracePeriod = Duration.ofMinutes(ruleRecord.gracePeriodMinutes.toLong()),
                existing = existing,
            ).added
            if (added.isNotEmpty()) {
                occurrences.insertMaterialized(
                    added.map { it.toRecord(content.id, now.toEpochMilli()) },
                )
                addedCount += added.size
            }
        }
        MaterializationReconciliationResult(active.size, addedCount)
    }
}

private fun RuleRevisionRecord.toDomainRule() = RecurrenceRule(
    frequency = com.patjackson.latertext.core.model.RecurrenceFrequency.valueOf(frequency.name),
    interval = interval,
    startDate = LocalDate.ofEpochDay(startEpochDay),
    localTime = LocalTime.ofSecondOfDay(secondsOfDay.toLong()),
    daysOfWeek = DayOfWeek.entries.filterTo(mutableSetOf()) {
        daysOfWeekMask and (1 shl (it.value - 1)) != 0
    },
    dayOfMonth = monthlyDayOfMonth ?: LocalDate.ofEpochDay(startEpochDay).dayOfMonth,
    end = when (endCondition) {
        EndCondition.NEVER -> RecurrenceEnd.Never
        EndCondition.AFTER_COUNT -> RecurrenceEnd.AfterOccurrences(requireNotNull(endCount))
        EndCondition.ON_DATE -> RecurrenceEnd.OnDate(LocalDate.ofEpochDay(requireNotNull(endEpochDay)))
    },
    zonePolicy = com.patjackson.latertext.core.model.ZonePolicy.valueOf(zonePolicy.name),
    zoneId = zoneId,
    monthlyDayPolicy = com.patjackson.latertext.core.model.MonthlyDayPolicy.valueOf(monthlyEdgePolicy.name),
    jitterRangeMinutes = jitterRangeMinutes,
)

private fun OccurrenceRecord.toMaterialized(): MaterializedOccurrence {
    val resolvedInstant = Instant.ofEpochMilli(targetAtEpochMillis)
        .minusSeconds(jitterOffsetMinutes.toLong() * 60L)
    val offset = ZoneOffset.ofTotalSeconds(selectedOffsetSeconds)
    return MaterializedOccurrence(
        key = LogicalOccurrenceKey(
            ScheduleId(scheduleId),
            RuleRevisionId(ruleRevisionId),
            logicalRecurrenceKey.substringAfterLast(':').toLong(),
        ),
        nominalLocalDateTime = LocalDateTime.of(
            LocalDate.ofEpochDay(nominalEpochDay),
            LocalTime.ofSecondOfDay(nominalSecondsOfDay.toLong()),
        ),
        resolvedLocalDateTime = LocalDateTime.ofInstant(resolvedInstant, offset),
        zoneId = selectedZoneId,
        selectedOffset = offset,
        monthlyAdjustment = MonthlyAdjustment.NONE,
        dstResolution = com.patjackson.latertext.core.model.DstResolution.valueOf(dstResolution.name),
        jitterOffsetMinutes = jitterOffsetMinutes,
        targetAt = Instant.ofEpochMilli(targetAtEpochMillis),
        deadlineAt = Instant.ofEpochMilli(deadlineAtEpochMillis),
        state = state.toDomainState(),
    )
}

private fun OccurrenceState.toDomainState() = when (this) {
    OccurrenceState.SKIPPED_MISSED -> com.patjackson.latertext.core.model.OccurrenceState.MISSED
    else -> com.patjackson.latertext.core.model.OccurrenceState.valueOf(name)
}

private fun MaterializedOccurrence.toRecord(contentRevisionId: String, createdAt: Long) =
    OccurrenceRecord(
        id = UUID.randomUUID().toString(),
        scheduleId = key.scheduleId.value,
        ruleRevisionId = key.ruleRevisionId.value,
        contentRevisionId = contentRevisionId,
        logicalRecurrenceKey = "${key.scheduleId.value}:${key.ruleRevisionId.value}:${key.sequence}",
        nominalEpochDay = nominalLocalDateTime.toLocalDate().toEpochDay(),
        nominalSecondsOfDay = nominalLocalDateTime.toLocalTime().toSecondOfDay(),
        selectedZoneId = zoneId,
        selectedOffsetSeconds = selectedOffset.totalSeconds,
        dstResolution = com.patjackson.latertext.data.api.DstResolution.valueOf(dstResolution.name),
        jitterOffsetMinutes = jitterOffsetMinutes,
        targetAtEpochMillis = targetAt.toEpochMilli(),
        deadlineAtEpochMillis = deadlineAt.toEpochMilli(),
        state = OccurrenceState.PLANNED,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = createdAt,
    )
