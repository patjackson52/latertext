package com.patjackson.latertext.data.impl.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.patjackson.latertext.data.api.AttachmentIntakeSource
import com.patjackson.latertext.data.api.AttachmentState
import com.patjackson.latertext.data.api.AttachmentStorageClass
import com.patjackson.latertext.data.api.CallbackKind
import com.patjackson.latertext.data.api.CallbackTokenState
import com.patjackson.latertext.data.api.DeliveryOutcome
import com.patjackson.latertext.data.api.DraftState
import com.patjackson.latertext.data.api.DstResolution
import com.patjackson.latertext.data.api.EndCondition
import com.patjackson.latertext.data.api.MissedPolicy
import com.patjackson.latertext.data.api.MonthlyEdgePolicy
import com.patjackson.latertext.data.api.NotificationRecordState
import com.patjackson.latertext.data.api.OccurrenceState
import com.patjackson.latertext.data.api.OutboxState
import com.patjackson.latertext.data.api.PartOutcome
import com.patjackson.latertext.data.api.RecipientSource
import com.patjackson.latertext.data.api.RecurrenceFrequency
import com.patjackson.latertext.data.api.ScheduleState
import com.patjackson.latertext.data.api.SendOutcome
import com.patjackson.latertext.data.api.TransportMode
import com.patjackson.latertext.data.api.ZonePolicy

@Entity(
    tableName = "recipient_endpoint",
    indices = [
        Index(value = ["normalized_address"]),
        Index(value = ["contact_lookup_key"]),
    ],
)
data class RecipientEndpointEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "raw_address") val rawAddress: String,
    @ColumnInfo(name = "normalized_address") val normalizedAddress: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    val source: RecipientSource,
    @ColumnInfo(name = "contact_lookup_key") val contactLookupKey: String?,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "schedule",
    foreignKeys = [
        ForeignKey(
            entity = RecipientEndpointEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipient_endpoint_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContentRevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["active_content_revision_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
            deferred = true,
        ),
        ForeignKey(
            entity = RuleRevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["active_rule_revision_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
            deferred = true,
        ),
    ],
    indices = [
        Index(value = ["recipient_endpoint_id"]),
        Index(value = ["active_content_revision_id"]),
        Index(value = ["active_rule_revision_id"]),
        Index(value = ["state", "updated_at"]),
    ],
)
data class ScheduleEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "recipient_endpoint_id") val recipientEndpointId: String,
    @ColumnInfo(name = "active_content_revision_id") val activeContentRevisionId: String?,
    @ColumnInfo(name = "active_rule_revision_id") val activeRuleRevisionId: String?,
    val state: ScheduleState,
    @ColumnInfo(name = "transport_mode") val transportMode: TransportMode,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "deleted_at") val deletedAtEpochMillis: Long?,
)

@Entity(
    tableName = "content_revision",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["schedule_id", "revision_number"], unique = true),
    ],
)
data class ContentRevisionEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "schedule_id") val scheduleId: String,
    @ColumnInfo(name = "revision_number") val revisionNumber: Int,
    val text: String,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "attachment_asset",
    indices = [
        Index(value = ["sha256"]),
        Index(value = ["storage_class", "relative_path"], unique = true),
        Index(value = ["storage_class", "state"]),
        Index(value = ["staging_expires_at"]),
    ],
)
data class AttachmentAssetEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "storage_class") val storageClass: AttachmentStorageClass,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "byte_count") val byteCount: Long,
    @ColumnInfo(name = "width_pixels") val widthPixels: Int?,
    @ColumnInfo(name = "height_pixels") val heightPixels: Int?,
    val sha256: String,
    @ColumnInfo(name = "is_animated") val isAnimated: Boolean,
    @ColumnInfo(name = "intake_source") val intakeSource: AttachmentIntakeSource,
    val state: AttachmentState,
    @ColumnInfo(name = "failure_reason") val failureReason: String?,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "staging_expires_at") val stagingExpiresAtEpochMillis: Long?,
)

@Entity(
    tableName = "content_attachment",
    primaryKeys = ["content_revision_id"],
    foreignKeys = [
        ForeignKey(
            entity = ContentRevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["content_revision_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AttachmentAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["attachment_asset_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["attachment_asset_id"])],
)
data class ContentAttachmentEntity(
    @ColumnInfo(name = "content_revision_id") val contentRevisionId: String,
    @ColumnInfo(name = "attachment_asset_id") val attachmentAssetId: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

@Entity(
    tableName = "rule_revision",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["schedule_id", "revision_number"], unique = true)],
)
data class RuleRevisionEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "schedule_id") val scheduleId: String,
    @ColumnInfo(name = "revision_number") val revisionNumber: Int,
    val frequency: RecurrenceFrequency,
    val interval: Int,
    @ColumnInfo(name = "start_epoch_day") val startEpochDay: Long,
    @ColumnInfo(name = "seconds_of_day") val secondsOfDay: Int,
    @ColumnInfo(name = "days_of_week_mask") val daysOfWeekMask: Int,
    @ColumnInfo(name = "monthly_day_of_month") val monthlyDayOfMonth: Int?,
    @ColumnInfo(name = "monthly_edge_policy") val monthlyEdgePolicy: MonthlyEdgePolicy,
    @ColumnInfo(name = "zone_policy") val zonePolicy: ZonePolicy,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "end_condition") val endCondition: EndCondition,
    @ColumnInfo(name = "end_count") val endCount: Int?,
    @ColumnInfo(name = "end_epoch_day") val endEpochDay: Long?,
    @ColumnInfo(name = "jitter_range_minutes") val jitterRangeMinutes: Int,
    @ColumnInfo(name = "missed_policy") val missedPolicy: MissedPolicy,
    @ColumnInfo(name = "grace_period_minutes") val gracePeriodMinutes: Int,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "occurrence",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RuleRevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["rule_revision_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContentRevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["content_revision_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["schedule_id", "logical_recurrence_key"], unique = true),
        Index(value = ["rule_revision_id"]),
        Index(value = ["content_revision_id"]),
        Index(value = ["state", "target_at"]),
        Index(value = ["state", "retry_at"]),
        Index(value = ["active_attempt_id"]),
        Index(value = ["claim_until"]),
    ],
)
data class OccurrenceEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "schedule_id") val scheduleId: String,
    @ColumnInfo(name = "rule_revision_id") val ruleRevisionId: String,
    @ColumnInfo(name = "content_revision_id") val contentRevisionId: String,
    @ColumnInfo(name = "logical_recurrence_key") val logicalRecurrenceKey: String,
    @ColumnInfo(name = "nominal_epoch_day") val nominalEpochDay: Long,
    @ColumnInfo(name = "nominal_seconds_of_day") val nominalSecondsOfDay: Int,
    @ColumnInfo(name = "selected_zone_id") val selectedZoneId: String,
    @ColumnInfo(name = "selected_offset_seconds") val selectedOffsetSeconds: Int,
    @ColumnInfo(name = "dst_resolution") val dstResolution: DstResolution,
    @ColumnInfo(name = "jitter_offset_minutes") val jitterOffsetMinutes: Int,
    @ColumnInfo(name = "target_at") val targetAtEpochMillis: Long,
    @ColumnInfo(name = "deadline_at") val deadlineAtEpochMillis: Long,
    val state: OccurrenceState,
    @ColumnInfo(name = "send_outcome") val sendOutcome: SendOutcome,
    @ColumnInfo(name = "delivery_outcome") val deliveryOutcome: DeliveryOutcome,
    @ColumnInfo(name = "active_attempt_id") val activeAttemptId: String?,
    @ColumnInfo(name = "retry_at") val retryAtEpochMillis: Long?,
    @ColumnInfo(name = "claim_owner") val claimOwner: String?,
    @ColumnInfo(name = "claim_until") val claimUntilEpochMillis: Long?,
    @ColumnInfo(name = "alarm_generation") val alarmGeneration: Long,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "send_attempt",
    foreignKeys = [
        ForeignKey(
            entity = OccurrenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["occurrence_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["occurrence_id", "attempt_number"], unique = true)],
)
data class SendAttemptEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
    @ColumnInfo(name = "attempt_number") val attemptNumber: Int,
    @ColumnInfo(name = "transport_mode") val transportMode: TransportMode,
    @ColumnInfo(name = "send_outcome") val sendOutcome: SendOutcome,
    @ColumnInfo(name = "delivery_outcome") val deliveryOutcome: DeliveryOutcome,
    @ColumnInfo(name = "failure_code") val failureCode: String?,
    @ColumnInfo(name = "failure_detail") val failureDetail: String?,
    @ColumnInfo(name = "started_at") val startedAtEpochMillis: Long,
    @ColumnInfo(name = "finished_at") val finishedAtEpochMillis: Long?,
    @ColumnInfo(name = "delivery_deadline_at") val deliveryDeadlineAtEpochMillis: Long?,
)

@Entity(
    tableName = "attempt_part",
    primaryKeys = ["attempt_id", "part_index"],
    foreignKeys = [
        ForeignKey(
            entity = SendAttemptEntity::class,
            parentColumns = ["id"],
            childColumns = ["attempt_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["attempt_id"])],
)
data class AttemptPartEntity(
    @ColumnInfo(name = "attempt_id") val attemptId: String,
    @ColumnInfo(name = "part_index") val partIndex: Int,
    @ColumnInfo(name = "total_parts") val totalParts: Int,
    @ColumnInfo(name = "send_outcome") val sendOutcome: PartOutcome,
    @ColumnInfo(name = "delivery_outcome") val deliveryOutcome: PartOutcome,
    @ColumnInfo(name = "sent_result_code") val sentResultCode: Int?,
    @ColumnInfo(name = "delivery_result_code") val deliveryResultCode: Int?,
    @ColumnInfo(name = "sent_at") val sentAtEpochMillis: Long?,
    @ColumnInfo(name = "delivered_at") val deliveredAtEpochMillis: Long?,
)

@Entity(
    tableName = "callback_token",
    foreignKeys = [
        ForeignKey(
            entity = SendAttemptEntity::class,
            parentColumns = ["id"],
            childColumns = ["attempt_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["attempt_id", "part_index", "kind"], unique = true),
        Index(value = ["state", "expires_at"]),
    ],
)
data class CallbackTokenEntity(
    @androidx.room.PrimaryKey val token: String,
    @ColumnInfo(name = "attempt_id") val attemptId: String,
    @ColumnInfo(name = "part_index") val partIndex: Int,
    val kind: CallbackKind,
    val state: CallbackTokenState,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "expires_at") val expiresAtEpochMillis: Long,
    @ColumnInfo(name = "consumed_at") val consumedAtEpochMillis: Long?,
)

@Entity(
    tableName = "occurrence_event",
    foreignKeys = [
        ForeignKey(
            entity = OccurrenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["occurrence_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SendAttemptEntity::class,
            parentColumns = ["id"],
            childColumns = ["attempt_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["occurrence_id", "happened_at"]),
        Index(value = ["attempt_id"]),
    ],
)
data class OccurrenceEventEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
    @ColumnInfo(name = "attempt_id") val attemptId: String?,
    val type: String,
    @ColumnInfo(name = "detail_json") val detailJson: String?,
    @ColumnInfo(name = "happened_at") val happenedAtEpochMillis: Long,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "composer_draft",
    foreignKeys = [
        ForeignKey(
            entity = RecipientEndpointEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipient_endpoint_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["recipient_endpoint_id"]), Index(value = ["updated_at"])],
)
data class ComposerDraftEntity(
    @androidx.room.PrimaryKey val id: String,
    val text: String,
    @ColumnInfo(name = "recipient_endpoint_id") val recipientEndpointId: String?,
    @ColumnInfo(name = "raw_recipient_address") val rawRecipientAddress: String?,
    val source: String,
    val state: DraftState,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "draft_attachment",
    primaryKeys = ["draft_id"],
    foreignKeys = [
        ForeignKey(
            entity = ComposerDraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draft_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AttachmentAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["attachment_asset_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["attachment_asset_id"], unique = true)],
)
data class DraftAttachmentEntity(
    @ColumnInfo(name = "draft_id") val draftId: String,
    @ColumnInfo(name = "attachment_asset_id") val attachmentAssetId: String,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "recent_recipient",
    foreignKeys = [
        ForeignKey(
            entity = RecipientEndpointEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipient_endpoint_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["recipient_endpoint_id"]),
        Index(value = ["last_used_at"]),
    ],
)
data class RecentRecipientEntity(
    @androidx.room.PrimaryKey @ColumnInfo(name = "normalized_address") val normalizedAddress: String,
    @ColumnInfo(name = "raw_address") val rawAddress: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "recipient_endpoint_id") val recipientEndpointId: String?,
    @ColumnInfo(name = "last_used_at") val lastUsedAtEpochMillis: Long,
    @ColumnInfo(name = "use_count") val useCount: Long,
)

@Entity(
    tableName = "notification_record",
    foreignKeys = [
        ForeignKey(
            entity = OccurrenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["occurrence_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["occurrence_id"]),
        Index(value = ["android_notification_id"], unique = true),
        Index(value = ["state", "updated_at"]),
    ],
)
data class NotificationRecordEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String?,
    val type: String,
    @ColumnInfo(name = "android_notification_id") val androidNotificationId: Int,
    @ColumnInfo(name = "channel_id") val channelId: String,
    val state: NotificationRecordState,
    @ColumnInfo(name = "posted_at") val postedAtEpochMillis: Long?,
    @ColumnInfo(name = "cancelled_at") val cancelledAtEpochMillis: Long?,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "side_effect_outbox",
    indices = [
        Index(value = ["deduplication_key"], unique = true),
        Index(value = ["state", "available_at", "created_at"]),
        Index(value = ["claim_until"]),
        Index(value = ["aggregate_type", "aggregate_id"]),
    ],
)
data class SideEffectOutboxEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "aggregate_type") val aggregateType: String,
    @ColumnInfo(name = "aggregate_id") val aggregateId: String,
    @ColumnInfo(name = "effect_type") val effectType: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "deduplication_key") val deduplicationKey: String,
    val state: OutboxState,
    @ColumnInfo(name = "available_at") val availableAtEpochMillis: Long,
    @ColumnInfo(name = "claim_owner") val claimOwner: String?,
    @ColumnInfo(name = "claim_until") val claimUntilEpochMillis: Long?,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtEpochMillis: Long,
)
