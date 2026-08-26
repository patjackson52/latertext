package com.patjackson.latertext.data.api

/**
 * Persistence-facing models deliberately use string IDs and epoch based time values.
 * Domain modules can wrap these values without making the data API depend on Room or Android.
 */
enum class RecipientSource { MANUAL, CONTACT_PICKER, LEGACY_PHONE_PICKER, SHARE }

enum class ScheduleState { ACTIVE, PAUSED, NEEDS_ATTENTION, COMPLETED, CANCELLED, DELETED }

enum class TransportMode { AUTOMATIC_SMS, ASSISTED_TEXT, ASSISTED_MEDIA }

enum class AttachmentIntakeSource { KEYBOARD, CLIPBOARD, PHOTO_PICKER, SHARE, DRAG_DROP }

enum class AttachmentStorageClass { STAGING, DURABLE, CACHE }

enum class AttachmentState { COPYING, READY, FAILED, MISSING, QUARANTINED }

enum class RecurrenceFrequency { ONCE, DAILY, WEEKLY, MONTHLY }

enum class ZonePolicy { FOLLOW_DEVICE_ZONE, FIXED_ZONE }

enum class MonthlyEdgePolicy { SKIP_MONTH, LAST_DAY_OF_MONTH }

enum class EndCondition { NEVER, AFTER_COUNT, ON_DATE }

enum class MissedPolicy { SEND_AS_SOON_AS_POSSIBLE, ASK_ME, SKIP }

enum class DstResolution { EXACT, GAP_SHIFTED_FORWARD, OVERLAP_EARLIER_OFFSET }

enum class OccurrenceState {
    PLANNED,
    ARMED,
    DUE,
    CLAIMED,
    SENDING,
    RETRY_WAIT,
    READY_FOR_USER,
    OPENED_IN_LATER_TEXT,
    SHARED_TO_MESSAGING_APP,
    SENT_TO_CARRIER,
    DELIVERED,
    DELIVERY_FAILED,
    DELIVERY_UNAVAILABLE,
    FAILED_TERMINAL,
    PARTIAL_AMBIGUOUS,
    SKIPPED_PAUSED,
    SKIPPED_MISSED,
    MISSED,
    EXPIRED,
    CANCELLED,
}

enum class SendOutcome {
    NOT_STARTED,
    PENDING,
    SENT_TO_CARRIER,
    FAILED,
    PARTIAL_OR_AMBIGUOUS,
    SKIPPED,
    USER_ACTION_REQUIRED,
    SHARED_UNVERIFIED,
}

enum class DeliveryOutcome { PENDING, DELIVERED, FAILED, UNAVAILABLE, NOT_REQUESTED }

enum class PartOutcome { PENDING, ACCEPTED, FAILED, UNKNOWN }

enum class CallbackKind { SENT, DELIVERED }

enum class CallbackTokenState { READY, CONSUMED, EXPIRED }

enum class DraftState { EDITING, IMPORTING_ATTACHMENT, READY, FAILED }

enum class NotificationRecordState { PLANNED, POSTED, DISMISSED, CANCELLED, FAILED }

enum class OutboxState { READY, CLAIMED, COMPLETED, FAILED }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class RecipientEndpointRecord(
    val id: String,
    val rawAddress: String,
    val normalizedAddress: String,
    val displayName: String?,
    val source: RecipientSource,
    val contactLookupKey: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class ScheduleRecord(
    val id: String,
    val recipientEndpointId: String,
    val activeContentRevisionId: String?,
    val activeRuleRevisionId: String?,
    val state: ScheduleState,
    val transportMode: TransportMode,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val deletedAtEpochMillis: Long? = null,
)

data class ContentRevisionRecord(
    val id: String,
    val scheduleId: String,
    val revisionNumber: Int,
    val text: String,
    val createdAtEpochMillis: Long,
)

data class AttachmentAssetRecord(
    val id: String,
    val storageClass: AttachmentStorageClass,
    val relativePath: String,
    val mimeType: String,
    val byteCount: Long,
    val widthPixels: Int?,
    val heightPixels: Int?,
    val sha256: String,
    val isAnimated: Boolean,
    val intakeSource: AttachmentIntakeSource,
    val state: AttachmentState,
    val failureReason: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val stagingExpiresAtEpochMillis: Long? = null,
)

data class ContentAttachmentRecord(
    val contentRevisionId: String,
    val attachmentAssetId: String,
    val sortOrder: Int = 0,
)

data class RuleRevisionRecord(
    val id: String,
    val scheduleId: String,
    val revisionNumber: Int,
    val frequency: RecurrenceFrequency,
    val interval: Int,
    val startEpochDay: Long,
    val secondsOfDay: Int,
    /** ISO day-of-week bit mask, Monday at bit zero. */
    val daysOfWeekMask: Int,
    val monthlyDayOfMonth: Int?,
    val monthlyEdgePolicy: MonthlyEdgePolicy,
    val zonePolicy: ZonePolicy,
    val zoneId: String,
    val endCondition: EndCondition,
    val endCount: Int?,
    val endEpochDay: Long?,
    val jitterRangeMinutes: Int,
    val missedPolicy: MissedPolicy,
    val gracePeriodMinutes: Int,
    val createdAtEpochMillis: Long,
)

data class OccurrenceRecord(
    val id: String,
    val scheduleId: String,
    val ruleRevisionId: String,
    val contentRevisionId: String,
    val logicalRecurrenceKey: String,
    val nominalEpochDay: Long,
    val nominalSecondsOfDay: Int,
    val selectedZoneId: String,
    val selectedOffsetSeconds: Int,
    val dstResolution: DstResolution,
    val jitterOffsetMinutes: Int,
    val targetAtEpochMillis: Long,
    val deadlineAtEpochMillis: Long,
    val state: OccurrenceState,
    val sendOutcome: SendOutcome = SendOutcome.NOT_STARTED,
    val deliveryOutcome: DeliveryOutcome = DeliveryOutcome.NOT_REQUESTED,
    val activeAttemptId: String? = null,
    val retryAtEpochMillis: Long? = null,
    val claimOwner: String? = null,
    val claimUntilEpochMillis: Long? = null,
    val alarmGeneration: Long = 0,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class SendAttemptRecord(
    val id: String,
    val occurrenceId: String,
    val attemptNumber: Int,
    val transportMode: TransportMode,
    val sendOutcome: SendOutcome,
    val deliveryOutcome: DeliveryOutcome,
    val failureCode: String?,
    val failureDetail: String?,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long?,
    val deliveryDeadlineAtEpochMillis: Long?,
)

data class AttemptPartRecord(
    val attemptId: String,
    val partIndex: Int,
    val totalParts: Int,
    val sendOutcome: PartOutcome,
    val deliveryOutcome: PartOutcome,
    val sentResultCode: Int?,
    val deliveryResultCode: Int?,
    val sentAtEpochMillis: Long?,
    val deliveredAtEpochMillis: Long?,
)

data class CallbackTokenRecord(
    val token: String,
    val attemptId: String,
    val partIndex: Int,
    val kind: CallbackKind,
    val state: CallbackTokenState,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val consumedAtEpochMillis: Long?,
)

data class OccurrenceEventRecord(
    val id: String,
    val occurrenceId: String,
    val attemptId: String?,
    val type: String,
    val detailJson: String?,
    val happenedAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
)

data class ComposerDraftRecord(
    val id: String,
    val text: String,
    val recipientEndpointId: String?,
    val rawRecipientAddress: String?,
    val source: String,
    val state: DraftState,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class DraftAttachmentRecord(
    val draftId: String,
    val attachmentAssetId: String,
    val createdAtEpochMillis: Long,
)

data class RecentRecipientRecord(
    val normalizedAddress: String,
    val rawAddress: String,
    val displayName: String?,
    val recipientEndpointId: String?,
    val lastUsedAtEpochMillis: Long,
    val useCount: Long,
)

data class NotificationRecord(
    val id: String,
    val occurrenceId: String?,
    val type: String,
    val androidNotificationId: Int,
    val channelId: String,
    val state: NotificationRecordState,
    val postedAtEpochMillis: Long?,
    val cancelledAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class SideEffectOutboxRecord(
    val id: String,
    val aggregateType: String,
    val aggregateId: String,
    val effectType: String,
    val payloadJson: String,
    val deduplicationKey: String,
    val state: OutboxState,
    val availableAtEpochMillis: Long,
    val claimOwner: String?,
    val claimUntilEpochMillis: Long?,
    val attemptCount: Int,
    val lastError: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class ScheduleGraph(
    val schedule: ScheduleRecord,
    val recipient: RecipientEndpointRecord,
    val activeContent: ContentRevisionRecord?,
    val activeAttachment: AttachmentAssetRecord?,
    val activeRule: RuleRevisionRecord?,
    val occurrences: List<OccurrenceRecord>,
)
