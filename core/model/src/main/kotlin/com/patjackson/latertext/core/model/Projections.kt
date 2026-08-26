package com.patjackson.latertext.core.model

import java.time.Instant
import java.time.LocalDateTime

data class OccurrencePreview(
    val key: LogicalOccurrenceKey,
    val nominalLocalDateTime: LocalDateTime,
    val targetAt: Instant,
    val jitterOffsetMinutes: Int,
    val monthlyAdjustment: MonthlyAdjustment,
    val dstResolution: DstResolution,
)

data class UpcomingOccurrenceProjection(
    val occurrenceId: OccurrenceId,
    val scheduleId: ScheduleId,
    val targetAt: Instant,
    val state: OccurrenceState,
    val sendOutcome: SendOutcome,
    val deliveryOutcome: DeliveryOutcome,
    val requiresUserAction: Boolean,
    val nextActionAt: Instant?,
)

data class HistoryOutcomeProjection(
    val occurrenceId: OccurrenceId,
    val sendOutcome: SendOutcome,
    val deliveryOutcome: DeliveryOutcome,
    val assistedOutcomeUnverified: Boolean,
    val terminal: Boolean,
)
