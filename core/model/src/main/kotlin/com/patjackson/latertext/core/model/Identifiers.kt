package com.patjackson.latertext.core.model

/** Stable persisted identifiers. Their string representation is intentionally storage-agnostic. */
@JvmInline
value class RecipientId(val value: String) {
    init { require(value.isNotBlank()) { "RecipientId must not be blank" } }
}

@JvmInline
value class ScheduleId(val value: String) {
    init { require(value.isNotBlank()) { "ScheduleId must not be blank" } }
}

@JvmInline
value class ContentRevisionId(val value: String) {
    init { require(value.isNotBlank()) { "ContentRevisionId must not be blank" } }
}

@JvmInline
value class RuleRevisionId(val value: String) {
    init { require(value.isNotBlank()) { "RuleRevisionId must not be blank" } }
}

@JvmInline
value class OccurrenceId(val value: String) {
    init { require(value.isNotBlank()) { "OccurrenceId must not be blank" } }
}

@JvmInline
value class AttemptId(val value: String) {
    init { require(value.isNotBlank()) { "AttemptId must not be blank" } }
}

@JvmInline
value class AttachmentId(val value: String) {
    init { require(value.isNotBlank()) { "AttachmentId must not be blank" } }
}

@JvmInline
value class DraftId(val value: String) {
    init { require(value.isNotBlank()) { "DraftId must not be blank" } }
}

@JvmInline
value class ClaimToken(val value: String) {
    init { require(value.isNotBlank()) { "ClaimToken must not be blank" } }
}

/**
 * Durable recurrence identity. The sequence is assigned only to emitted recurrence slots;
 * manual sends and skipped, nonexistent month days never consume it.
 */
data class LogicalOccurrenceKey(
    val scheduleId: ScheduleId,
    val ruleRevisionId: RuleRevisionId,
    val sequence: Long,
) {
    init { require(sequence >= 0) { "Occurrence sequence must be non-negative" } }
}
