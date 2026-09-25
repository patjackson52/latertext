package com.patjackson.latertext.data.impl.db

import com.patjackson.latertext.data.api.AttachmentAssetRecord
import com.patjackson.latertext.data.api.AttemptBundle
import com.patjackson.latertext.data.api.AttemptPartRecord
import com.patjackson.latertext.data.api.CallbackTokenRecord
import com.patjackson.latertext.data.api.ComposerDraftRecord
import com.patjackson.latertext.data.api.ContentAttachmentRecord
import com.patjackson.latertext.data.api.ContentRevisionRecord
import com.patjackson.latertext.data.api.DraftAttachmentRecord
import com.patjackson.latertext.data.api.NotificationRecord
import com.patjackson.latertext.data.api.OccurrenceEventRecord
import com.patjackson.latertext.data.api.OccurrenceRecord
import com.patjackson.latertext.data.api.RecentRecipientRecord
import com.patjackson.latertext.data.api.RecipientEndpointRecord
import com.patjackson.latertext.data.api.RuleRevisionRecord
import com.patjackson.latertext.data.api.ScheduleGraph
import com.patjackson.latertext.data.api.ScheduleRecord
import com.patjackson.latertext.data.api.SendAttemptRecord
import com.patjackson.latertext.data.api.SideEffectOutboxRecord

internal fun RecipientEndpointRecord.toEntity() = RecipientEndpointEntity(
    id, rawAddress, normalizedAddress, displayName, source, contactLookupKey,
    createdAtEpochMillis, updatedAtEpochMillis,
)

internal fun RecipientEndpointEntity.toRecord() = RecipientEndpointRecord(
    id, rawAddress, normalizedAddress, displayName, source, contactLookupKey,
    createdAtEpochMillis, updatedAtEpochMillis,
)

internal fun ScheduleRecord.toEntity(
    activeContentRevisionId: String? = this.activeContentRevisionId,
    activeRuleRevisionId: String? = this.activeRuleRevisionId,
) = ScheduleEntity(
    id, recipientEndpointId, activeContentRevisionId, activeRuleRevisionId, state,
    transportMode, createdAtEpochMillis, updatedAtEpochMillis, deletedAtEpochMillis,
)

internal fun ScheduleEntity.toRecord() = ScheduleRecord(
    id, recipientEndpointId, activeContentRevisionId, activeRuleRevisionId, state,
    transportMode, createdAtEpochMillis, updatedAtEpochMillis, deletedAtEpochMillis,
)

internal fun ContentRevisionRecord.toEntity() = ContentRevisionEntity(
    id, scheduleId, revisionNumber, text, createdAtEpochMillis,
)

internal fun ContentRevisionEntity.toRecord() = ContentRevisionRecord(
    id, scheduleId, revisionNumber, text, createdAtEpochMillis,
)

internal fun AttachmentAssetRecord.toEntity() = AttachmentAssetEntity(
    id, storageClass, relativePath, mimeType, byteCount, widthPixels, heightPixels,
    sha256, isAnimated, intakeSource, state, failureReason, createdAtEpochMillis,
    updatedAtEpochMillis, stagingExpiresAtEpochMillis,
)

internal fun AttachmentAssetEntity.toRecord() = AttachmentAssetRecord(
    id, storageClass, relativePath, mimeType, byteCount, widthPixels, heightPixels,
    sha256, isAnimated, intakeSource, state, failureReason, createdAtEpochMillis,
    updatedAtEpochMillis, stagingExpiresAtEpochMillis,
)

internal fun ContentAttachmentRecord.toEntity() = ContentAttachmentEntity(
    contentRevisionId, attachmentAssetId, sortOrder,
)

internal fun RuleRevisionRecord.toEntity() = RuleRevisionEntity(
    id, scheduleId, revisionNumber, frequency, interval, startEpochDay, secondsOfDay,
    daysOfWeekMask, monthlyDayOfMonth, monthlyEdgePolicy, zonePolicy, zoneId,
    endCondition, endCount, endEpochDay, jitterRangeMinutes, missedPolicy,
    gracePeriodMinutes, createdAtEpochMillis,
)

internal fun RuleRevisionEntity.toRecord() = RuleRevisionRecord(
    id, scheduleId, revisionNumber, frequency, interval, startEpochDay, secondsOfDay,
    daysOfWeekMask, monthlyDayOfMonth, monthlyEdgePolicy, zonePolicy, zoneId,
    endCondition, endCount, endEpochDay, jitterRangeMinutes, missedPolicy,
    gracePeriodMinutes, createdAtEpochMillis,
)

internal fun OccurrenceRecord.toEntity() = OccurrenceEntity(
    id, scheduleId, ruleRevisionId, contentRevisionId, logicalRecurrenceKey,
    nominalEpochDay, nominalSecondsOfDay, selectedZoneId, selectedOffsetSeconds,
    dstResolution, jitterOffsetMinutes, targetAtEpochMillis, deadlineAtEpochMillis,
    state, sendOutcome, deliveryOutcome, activeAttemptId, retryAtEpochMillis,
    claimOwner, claimUntilEpochMillis, alarmGeneration, createdAtEpochMillis,
    updatedAtEpochMillis,
)

internal fun OccurrenceEntity.toRecord() = OccurrenceRecord(
    id, scheduleId, ruleRevisionId, contentRevisionId, logicalRecurrenceKey,
    nominalEpochDay, nominalSecondsOfDay, selectedZoneId, selectedOffsetSeconds,
    dstResolution, jitterOffsetMinutes, targetAtEpochMillis, deadlineAtEpochMillis,
    state, sendOutcome, deliveryOutcome, activeAttemptId, retryAtEpochMillis,
    claimOwner, claimUntilEpochMillis, alarmGeneration, createdAtEpochMillis,
    updatedAtEpochMillis,
)

internal fun SendAttemptRecord.toEntity() = SendAttemptEntity(
    id, occurrenceId, attemptNumber, transportMode, sendOutcome, deliveryOutcome,
    failureCode, failureDetail, startedAtEpochMillis, finishedAtEpochMillis,
    deliveryDeadlineAtEpochMillis, subscriptionId, providerMessageId, providerStatus,
    providerErrorCode, providerObservedAtEpochMillis,
)

internal fun SendAttemptEntity.toRecord() = SendAttemptRecord(
    id, occurrenceId, attemptNumber, transportMode, sendOutcome, deliveryOutcome,
    failureCode, failureDetail, startedAtEpochMillis, finishedAtEpochMillis,
    deliveryDeadlineAtEpochMillis, subscriptionId, providerMessageId, providerStatus,
    providerErrorCode, providerObservedAtEpochMillis,
)

internal fun AttemptPartRecord.toEntity() = AttemptPartEntity(
    attemptId, partIndex, totalParts, sendOutcome, deliveryOutcome, sentResultCode,
    deliveryResultCode, sentAtEpochMillis, deliveredAtEpochMillis,
)

internal fun AttemptPartEntity.toRecord() = AttemptPartRecord(
    attemptId, partIndex, totalParts, sendOutcome, deliveryOutcome, sentResultCode,
    deliveryResultCode, sentAtEpochMillis, deliveredAtEpochMillis,
)

internal fun CallbackTokenRecord.toEntity() = CallbackTokenEntity(
    token, attemptId, partIndex, kind, state, createdAtEpochMillis,
    expiresAtEpochMillis, consumedAtEpochMillis,
)

internal fun CallbackTokenEntity.toRecord() = CallbackTokenRecord(
    token, attemptId, partIndex, kind, state, createdAtEpochMillis,
    expiresAtEpochMillis, consumedAtEpochMillis,
)

internal fun OccurrenceEventRecord.toEntity() = OccurrenceEventEntity(
    id, occurrenceId, attemptId, type, detailJson, happenedAtEpochMillis, createdAtEpochMillis,
)

internal fun ComposerDraftRecord.toEntity() = ComposerDraftEntity(
    id, text, recipientEndpointId, rawRecipientAddress, source, state,
    createdAtEpochMillis, updatedAtEpochMillis,
)

internal fun ComposerDraftEntity.toRecord() = ComposerDraftRecord(
    id, text, recipientEndpointId, rawRecipientAddress, source, state,
    createdAtEpochMillis, updatedAtEpochMillis,
)

internal fun DraftAttachmentRecord.toEntity() = DraftAttachmentEntity(
    draftId, attachmentAssetId, createdAtEpochMillis,
)

internal fun RecentRecipientRecord.toEntity() = RecentRecipientEntity(
    normalizedAddress, rawAddress, displayName, recipientEndpointId,
    lastUsedAtEpochMillis, useCount,
)

internal fun RecentRecipientEntity.toRecord() = RecentRecipientRecord(
    normalizedAddress, rawAddress, displayName, recipientEndpointId,
    lastUsedAtEpochMillis, useCount,
)

internal fun NotificationRecord.toEntity() = NotificationRecordEntity(
    id, occurrenceId, type, androidNotificationId, channelId, state,
    postedAtEpochMillis, cancelledAtEpochMillis, createdAtEpochMillis, updatedAtEpochMillis,
)

internal fun NotificationRecordEntity.toRecord() = NotificationRecord(
    id, occurrenceId, type, androidNotificationId, channelId, state,
    postedAtEpochMillis, cancelledAtEpochMillis, createdAtEpochMillis, updatedAtEpochMillis,
)

internal fun SideEffectOutboxRecord.toEntity() = SideEffectOutboxEntity(
    id, aggregateType, aggregateId, effectType, payloadJson, deduplicationKey, state,
    availableAtEpochMillis, claimOwner, claimUntilEpochMillis, attemptCount, lastError,
    createdAtEpochMillis, updatedAtEpochMillis,
)

internal fun SideEffectOutboxEntity.toRecord() = SideEffectOutboxRecord(
    id, aggregateType, aggregateId, effectType, payloadJson, deduplicationKey, state,
    availableAtEpochMillis, claimOwner, claimUntilEpochMillis, attemptCount, lastError,
    createdAtEpochMillis, updatedAtEpochMillis,
)

internal fun AttemptAggregate.toRecord() = AttemptBundle(
    attempt = attempt.toRecord(),
    parts = parts.sortedBy { it.partIndex }.map(AttemptPartEntity::toRecord),
    callbackTokens = callbackTokens.sortedWith(compareBy({ it.partIndex }, { it.kind.name }))
        .map(CallbackTokenEntity::toRecord),
)

internal fun ScheduleAggregate.toRecord(
    attachmentsByContentRevisionId: Map<String, AttachmentAssetEntity>,
): ScheduleGraph {
    val activeContent = contentRevisions.firstOrNull { it.id == schedule.activeContentRevisionId }
    val activeRule = ruleRevisions.firstOrNull { it.id == schedule.activeRuleRevisionId }
    return ScheduleGraph(
        schedule = schedule.toRecord(),
        recipient = recipient.toRecord(),
        activeContent = activeContent?.toRecord(),
        activeAttachment = activeContent?.id?.let(attachmentsByContentRevisionId::get)?.toRecord(),
        activeRule = activeRule?.toRecord(),
        occurrences = occurrences.sortedWith(compareBy({ it.targetAtEpochMillis }, { it.id }))
            .map(OccurrenceEntity::toRecord),
        contentRevisions = contentRevisions.sortedBy(ContentRevisionEntity::revisionNumber)
            .map(ContentRevisionEntity::toRecord),
        attachmentsByContentRevisionId = attachmentsByContentRevisionId.mapValues { it.value.toRecord() },
    )
}
