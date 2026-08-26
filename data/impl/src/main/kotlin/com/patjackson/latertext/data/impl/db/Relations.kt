package com.patjackson.latertext.data.impl.db

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class ContentRevisionWithAttachment(
    @Embedded val contentRevision: ContentRevisionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = ContentAttachmentEntity::class,
            parentColumn = "content_revision_id",
            entityColumn = "attachment_asset_id",
        ),
    )
    val attachments: List<AttachmentAssetEntity>,
)

data class ScheduleAggregate(
    @Embedded val schedule: ScheduleEntity,
    @Relation(parentColumn = "recipient_endpoint_id", entityColumn = "id")
    val recipient: RecipientEndpointEntity,
    @Relation(parentColumn = "id", entityColumn = "schedule_id")
    val contentRevisions: List<ContentRevisionEntity>,
    @Relation(parentColumn = "id", entityColumn = "schedule_id")
    val ruleRevisions: List<RuleRevisionEntity>,
    @Relation(parentColumn = "id", entityColumn = "schedule_id")
    val occurrences: List<OccurrenceEntity>,
)

data class AttemptAggregate(
    @Embedded val attempt: SendAttemptEntity,
    @Relation(parentColumn = "id", entityColumn = "attempt_id")
    val parts: List<AttemptPartEntity>,
    @Relation(parentColumn = "id", entityColumn = "attempt_id")
    val callbackTokens: List<CallbackTokenEntity>,
)
