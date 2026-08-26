package com.patjackson.latertext.core.model

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

sealed interface RecurrenceEnd {
    data object Never : RecurrenceEnd

    data class OnDate(val inclusiveDate: LocalDate) : RecurrenceEnd

    data class AfterOccurrences(val count: Int) : RecurrenceEnd {
        init { require(count > 0) { "Occurrence count must be positive" } }
    }
}

data class RecurrenceRule(
    val frequency: RecurrenceFrequency,
    val interval: Int = 1,
    val startDate: LocalDate,
    val localTime: LocalTime,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val dayOfMonth: Int = startDate.dayOfMonth,
    val end: RecurrenceEnd = RecurrenceEnd.Never,
    val zonePolicy: ZonePolicy = ZonePolicy.FOLLOW_DEVICE_ZONE,
    val zoneId: String,
    val dstGapPolicy: DstGapPolicy = DstGapPolicy.SHIFT_TO_FIRST_VALID_TIME,
    val dstOverlapPolicy: DstOverlapPolicy = DstOverlapPolicy.EARLIER_OFFSET,
    val monthlyDayPolicy: MonthlyDayPolicy = MonthlyDayPolicy.LAST_DAY_OF_MONTH,
    val jitterRangeMinutes: Int = 0,
) {
    init {
        require(interval > 0) { "Recurrence interval must be positive" }
        require(zoneId.isNotBlank()) { "Zone ID must not be blank" }
        require(dayOfMonth in 1..31) { "Day of month must be between 1 and 31" }
        require(jitterRangeMinutes in 0..10_080) { "Jitter must be between 0 and 10080 minutes" }
        if (frequency == RecurrenceFrequency.WEEKLY) {
            require(daysOfWeek.isNotEmpty()) { "Weekly recurrence requires at least one weekday" }
        }
        if (end is RecurrenceEnd.OnDate) {
            require(!end.inclusiveDate.isBefore(startDate)) {
                "Recurrence end date must not be before its start date"
            }
        }
    }
}

data class RecurrenceSlot(
    val sequence: Long,
    val nominalLocalDateTime: LocalDateTime,
    val monthlyAdjustment: MonthlyAdjustment = MonthlyAdjustment.NONE,
) {
    init { require(sequence >= 0) { "Slot sequence must be non-negative" } }
}

data class ResolvedCivilTime(
    val nominalLocalDateTime: LocalDateTime,
    val resolvedLocalDateTime: LocalDateTime,
    val zoneId: String,
    val selectedOffset: ZoneOffset,
    val instant: Instant,
    val dstResolution: DstResolution,
)

data class MaterializedOccurrence(
    val key: LogicalOccurrenceKey,
    val nominalLocalDateTime: LocalDateTime,
    val resolvedLocalDateTime: LocalDateTime,
    val zoneId: String,
    val selectedOffset: ZoneOffset,
    val monthlyAdjustment: MonthlyAdjustment,
    val dstResolution: DstResolution,
    val jitterOffsetMinutes: Int,
    val targetAt: Instant,
    val deadlineAt: Instant,
    val state: OccurrenceState = OccurrenceState.PLANNED,
) {
    init {
        require(!deadlineAt.isBefore(targetAt)) { "Deadline must not precede target" }
        require(key.sequence >= 0)
    }

    val gracePeriod: Duration get() = Duration.between(targetAt, deadlineAt)
}

data class MaterializationLimits(
    val minimumFutureCount: Int = 5,
    val maximumFutureCount: Int = 32,
    val maximumHorizon: Duration = Duration.ofDays(90),
) {
    init {
        require(minimumFutureCount > 0)
        require(maximumFutureCount >= minimumFutureCount)
        require(!maximumHorizon.isNegative && !maximumHorizon.isZero)
    }
}

data class MaterializationResult(
    val occurrences: List<MaterializedOccurrence>,
    val added: List<MaterializedOccurrence>,
    val skippedPastKeys: List<LogicalOccurrenceKey>,
)
