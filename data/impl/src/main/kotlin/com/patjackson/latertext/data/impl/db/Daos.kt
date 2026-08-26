package com.patjackson.latertext.data.impl.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.patjackson.latertext.data.api.CallbackTokenState
import com.patjackson.latertext.data.api.DeliveryOutcome
import com.patjackson.latertext.data.api.NotificationRecordState
import com.patjackson.latertext.data.api.OccurrenceState
import com.patjackson.latertext.data.api.OutboxState
import com.patjackson.latertext.data.api.ScheduleState
import com.patjackson.latertext.data.api.SendOutcome

@Dao
interface RecipientDao {
    @Upsert
    suspend fun upsert(entity: RecipientEndpointEntity)

    @Query("SELECT * FROM recipient_endpoint WHERE id = :id")
    suspend fun get(id: String): RecipientEndpointEntity?

    @Upsert
    suspend fun upsertRecent(entity: RecentRecipientEntity)

    @Query("SELECT * FROM recent_recipient ORDER BY last_used_at DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<RecentRecipientEntity>
}

@Dao
interface ScheduleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ScheduleEntity)

    @Query("SELECT * FROM schedule WHERE id = :id")
    suspend fun get(id: String): ScheduleEntity?

    @Transaction
    @Query("SELECT * FROM schedule WHERE id = :id")
    suspend fun getAggregate(id: String): ScheduleAggregate?

    @Transaction
    @Query(
        """
        SELECT DISTINCT schedule.* FROM schedule
        INNER JOIN occurrence ON occurrence.schedule_id = schedule.id
        WHERE schedule.deleted_at IS NULL
          AND occurrence.state IN (
            'PLANNED', 'ARMED', 'DUE', 'CLAIMED', 'SENDING', 'RETRY_WAIT',
            'READY_FOR_USER', 'OPENED_IN_LATER_TEXT'
          )
        ORDER BY occurrence.target_at ASC
        LIMIT :limit
        """,
    )
    suspend fun listUpcoming(limit: Int): List<ScheduleAggregate>

    @Transaction
    @Query(
        """
        SELECT * FROM schedule
        WHERE deleted_at IS NULL
        ORDER BY updated_at DESC
        LIMIT :limit
        """,
    )
    suspend fun listAll(limit: Int): List<ScheduleAggregate>

    @Query(
        """
        UPDATE schedule
        SET active_content_revision_id = :contentRevisionId,
            active_rule_revision_id = :ruleRevisionId,
            updated_at = :updatedAtEpochMillis
        WHERE id = :scheduleId
          AND active_content_revision_id IS NULL
          AND active_rule_revision_id IS NULL
        """,
    )
    suspend fun activateInitialRevisions(
        scheduleId: String,
        contentRevisionId: String,
        ruleRevisionId: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE schedule
        SET active_content_revision_id = :newRevisionId,
            updated_at = :updatedAtEpochMillis
        WHERE id = :scheduleId
          AND active_content_revision_id = :expectedRevisionId
          AND deleted_at IS NULL
        """,
    )
    suspend fun compareAndSetContentRevision(
        scheduleId: String,
        expectedRevisionId: String,
        newRevisionId: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE schedule
        SET active_rule_revision_id = :newRevisionId,
            updated_at = :updatedAtEpochMillis
        WHERE id = :scheduleId
          AND active_rule_revision_id = :expectedRevisionId
          AND deleted_at IS NULL
        """,
    )
    suspend fun compareAndSetRuleRevision(
        scheduleId: String,
        expectedRevisionId: String,
        newRevisionId: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE schedule SET state = :newState, updated_at = :updatedAtEpochMillis
        WHERE id = :scheduleId AND deleted_at IS NULL
        """,
    )
    suspend fun setState(
        scheduleId: String,
        newState: ScheduleState,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE schedule
        SET state = :deletedState, deleted_at = :deletedAtEpochMillis, updated_at = :deletedAtEpochMillis
        WHERE id = :scheduleId AND deleted_at IS NULL
        """,
    )
    suspend fun softDelete(
        scheduleId: String,
        deletedState: ScheduleState,
        deletedAtEpochMillis: Long,
    ): Int
}

@Dao
interface ContentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ContentRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttachment(entity: ContentAttachmentEntity)

    @Query("SELECT * FROM content_revision WHERE id = :id")
    suspend fun get(id: String): ContentRevisionEntity?

    @Transaction
    @Query("SELECT * FROM content_revision WHERE id = :id")
    suspend fun getWithAttachment(id: String): ContentRevisionWithAttachment?
}

@Dao
interface AttachmentAssetDao {
    @Upsert
    suspend fun upsert(entity: AttachmentAssetEntity)

    @Query("SELECT * FROM attachment_asset WHERE id = :id")
    suspend fun get(id: String): AttachmentAssetEntity?

    @Query(
        """
        SELECT * FROM attachment_asset
        WHERE staging_expires_at IS NOT NULL
          AND staging_expires_at <= :nowEpochMillis
          AND NOT EXISTS (
              SELECT 1 FROM content_attachment WHERE attachment_asset_id = attachment_asset.id
          )
          AND NOT EXISTS (
              SELECT 1 FROM draft_attachment WHERE attachment_asset_id = attachment_asset.id
          )
        """,
    )
    suspend fun findExpiredUnreferencedStaging(nowEpochMillis: Long): List<AttachmentAssetEntity>

    @Query(
        """
        DELETE FROM attachment_asset
        WHERE id = :id
          AND NOT EXISTS (
              SELECT 1 FROM content_attachment WHERE attachment_asset_id = attachment_asset.id
          )
          AND NOT EXISTS (
              SELECT 1 FROM draft_attachment WHERE attachment_asset_id = attachment_asset.id
          )
        """,
    )
    suspend fun deleteIfUnreferenced(id: String): Int
}

@Dao
interface RuleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: RuleRevisionEntity)

    @Query("SELECT * FROM rule_revision WHERE id = :id")
    suspend fun get(id: String): RuleRevisionEntity?
}

@Dao
interface OccurrenceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<OccurrenceEntity>)

    @Query("SELECT * FROM occurrence WHERE id = :id")
    suspend fun get(id: String): OccurrenceEntity?

    @Query("SELECT * FROM occurrence WHERE schedule_id = :scheduleId ORDER BY target_at, id")
    suspend fun listForSchedule(scheduleId: String): List<OccurrenceEntity>

    @Query(
        """
        SELECT * FROM occurrence
        WHERE state IN (:states)
        ORDER BY target_at, id
        LIMIT 1
        """,
    )
    suspend fun nextActionable(states: List<OccurrenceState>): OccurrenceEntity?

    @Query(
        """
        SELECT occurrence.* FROM occurrence
        INNER JOIN schedule ON schedule.id = occurrence.schedule_id
        WHERE schedule.state = :activeScheduleState
          AND schedule.deleted_at IS NULL
          AND occurrence.deadline_at >= :nowEpochMillis
          AND occurrence.state IN (:eligibleStates)
        ORDER BY
          CASE WHEN occurrence.retry_at IS NOT NULL THEN occurrence.retry_at ELSE occurrence.target_at END,
          occurrence.id
        LIMIT 1
        """,
    )
    suspend fun earliestEligible(
        nowEpochMillis: Long,
        activeScheduleState: ScheduleState,
        eligibleStates: List<OccurrenceState>,
    ): OccurrenceEntity?

    @Query(
        """
        SELECT * FROM occurrence
        WHERE state = :armedState
        ORDER BY target_at, id
        LIMIT 1
        """,
    )
    suspend fun currentlyArmed(armedState: OccurrenceState): OccurrenceEntity?

    @Query(
        """
        UPDATE occurrence
        SET state = :plannedState, updated_at = :updatedAtEpochMillis
        WHERE state = :armedState
          AND (:keepOccurrenceId IS NULL OR id != :keepOccurrenceId)
        """,
    )
    suspend fun disarmOthers(
        keepOccurrenceId: String?,
        armedState: OccurrenceState,
        plannedState: OccurrenceState,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE occurrence
        SET state = :armedState,
            alarm_generation = alarm_generation + 1,
            updated_at = :updatedAtEpochMillis
        WHERE id = :occurrenceId
          AND alarm_generation = :expectedGeneration
          AND state IN (:eligibleStates)
        """,
    )
    suspend fun armAndIncrementGeneration(
        occurrenceId: String,
        expectedGeneration: Long,
        armedState: OccurrenceState,
        eligibleStates: List<OccurrenceState>,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        SELECT * FROM occurrence
        WHERE target_at <= :nowEpochMillis
          AND deadline_at >= :nowEpochMillis
          AND state IN (:states)
        ORDER BY target_at, id
        LIMIT :limit
        """,
    )
    suspend fun listDue(
        nowEpochMillis: Long,
        states: List<OccurrenceState>,
        limit: Int,
    ): List<OccurrenceEntity>

    @Query(
        """
        UPDATE occurrence
        SET state = :claimedState,
            claim_owner = :owner,
            claim_until = :leaseUntilEpochMillis,
            updated_at = :nowEpochMillis
        WHERE id = :occurrenceId
          AND deadline_at >= :nowEpochMillis
          AND state IN (:claimableStates)
          AND (claim_owner IS NULL OR claim_owner = :owner OR claim_until <= :nowEpochMillis)
        """,
    )
    suspend fun claim(
        occurrenceId: String,
        owner: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
        claimedState: OccurrenceState,
        claimableStates: List<OccurrenceState>,
    ): Int

    @Query(
        """
        UPDATE occurrence
        SET state = :claimedState,
            claim_owner = :owner,
            claim_until = :leaseUntilEpochMillis,
            updated_at = :nowEpochMillis
        WHERE id = :occurrenceId
          AND alarm_generation = :expectedAlarmGeneration
          AND deadline_at >= :nowEpochMillis
          AND state IN (:claimableStates)
          AND (claim_owner IS NULL OR claim_owner = :owner OR claim_until <= :nowEpochMillis)
        """,
    )
    suspend fun claimExpectedGeneration(
        occurrenceId: String,
        expectedAlarmGeneration: Long,
        owner: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
        claimedState: OccurrenceState,
        claimableStates: List<OccurrenceState>,
    ): Int

    @Query(
        """
        UPDATE occurrence
        SET state = :nextState,
            claim_owner = NULL,
            claim_until = NULL,
            updated_at = :updatedAtEpochMillis
        WHERE id = :occurrenceId AND claim_owner = :owner
        """,
    )
    suspend fun releaseClaim(
        occurrenceId: String,
        owner: String,
        nextState: OccurrenceState,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE occurrence
        SET state = :newState, updated_at = :updatedAtEpochMillis
        WHERE id = :occurrenceId AND state IN (:expectedStates)
        """,
    )
    suspend fun compareAndSetState(
        occurrenceId: String,
        expectedStates: List<OccurrenceState>,
        newState: OccurrenceState,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE occurrence
        SET alarm_generation = :newGeneration, updated_at = :updatedAtEpochMillis
        WHERE id = :occurrenceId AND alarm_generation = :expectedGeneration
        """,
    )
    suspend fun compareAndSetAlarmGeneration(
        occurrenceId: String,
        expectedGeneration: Long,
        newGeneration: Long,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE occurrence
        SET state = CASE WHEN :newState IS NULL THEN state ELSE :newState END,
            send_outcome = CASE WHEN :sendOutcome IS NULL THEN send_outcome ELSE :sendOutcome END,
            delivery_outcome = CASE WHEN :deliveryOutcome IS NULL THEN delivery_outcome ELSE :deliveryOutcome END,
            active_attempt_id = CASE
              WHEN :replaceActiveAttemptId THEN :activeAttemptId ELSE active_attempt_id END,
            retry_at = CASE WHEN :replaceRetryAt THEN :retryAtEpochMillis ELSE retry_at END,
            claim_owner = NULL,
            claim_until = NULL,
            updated_at = :updatedAtEpochMillis
        WHERE id = :occurrenceId AND claim_owner = :owner
        """,
    )
    suspend fun transitionAfterClaim(
        occurrenceId: String,
        owner: String,
        newState: OccurrenceState?,
        sendOutcome: SendOutcome?,
        deliveryOutcome: DeliveryOutcome?,
        activeAttemptId: String?,
        replaceActiveAttemptId: Boolean,
        retryAtEpochMillis: Long?,
        replaceRetryAt: Boolean,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE occurrence
        SET state = CASE WHEN :newState IS NULL THEN state ELSE :newState END,
            send_outcome = CASE WHEN :sendOutcome IS NULL THEN send_outcome ELSE :sendOutcome END,
            delivery_outcome = CASE WHEN :deliveryOutcome IS NULL THEN delivery_outcome ELSE :deliveryOutcome END,
            active_attempt_id = CASE
              WHEN :replaceActiveAttemptId THEN :replacementAttemptId ELSE active_attempt_id END,
            retry_at = CASE WHEN :replaceRetryAt THEN :retryAtEpochMillis ELSE retry_at END,
            updated_at = :updatedAtEpochMillis
        WHERE id = :occurrenceId AND active_attempt_id = :expectedAttemptId
        """,
    )
    suspend fun applyAttemptProjection(
        occurrenceId: String,
        expectedAttemptId: String,
        newState: OccurrenceState?,
        sendOutcome: SendOutcome?,
        deliveryOutcome: DeliveryOutcome?,
        replacementAttemptId: String?,
        replaceActiveAttemptId: Boolean,
        retryAtEpochMillis: Long?,
        replaceRetryAt: Boolean,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE occurrence
        SET content_revision_id = :contentRevisionId, updated_at = :updatedAtEpochMillis
        WHERE schedule_id = :scheduleId
          AND target_at >= :atOrAfterEpochMillis
          AND claim_owner IS NULL
          AND state IN (:replaceableStates)
        """,
    )
    suspend fun updateUnclaimedContent(
        scheduleId: String,
        contentRevisionId: String,
        atOrAfterEpochMillis: Long,
        replaceableStates: List<OccurrenceState>,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        DELETE FROM occurrence
        WHERE schedule_id = :scheduleId
          AND target_at >= :atOrAfterEpochMillis
          AND claim_owner IS NULL
          AND state IN (:replaceableStates)
        """,
    )
    suspend fun deleteUnclaimedFuture(
        scheduleId: String,
        atOrAfterEpochMillis: Long,
        replaceableStates: List<OccurrenceState>,
    ): Int

    @Query(
        """
        UPDATE occurrence
        SET state = :cancelledState, updated_at = :updatedAtEpochMillis
        WHERE schedule_id = :scheduleId
          AND claim_owner IS NULL
          AND state IN (:replaceableStates)
        """,
    )
    suspend fun cancelUnclaimed(
        scheduleId: String,
        cancelledState: OccurrenceState,
        replaceableStates: List<OccurrenceState>,
        updatedAtEpochMillis: Long,
    ): Int
}

@Dao
interface AttemptDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(entity: SendAttemptEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertParts(entities: List<AttemptPartEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCallbackTokens(entities: List<CallbackTokenEntity>)

    @Transaction
    @Query("SELECT * FROM send_attempt WHERE id = :id")
    suspend fun getAggregate(id: String): AttemptAggregate?

    @Query("SELECT COUNT(*) FROM send_attempt WHERE occurrence_id = :occurrenceId")
    suspend fun countForOccurrence(occurrenceId: String): Int

    @Transaction
    @Query(
        """
        SELECT * FROM send_attempt
        WHERE occurrence_id = :occurrenceId
        ORDER BY attempt_number DESC
        LIMIT 1
        """,
    )
    suspend fun latestForOccurrence(occurrenceId: String): AttemptAggregate?

    @Update
    suspend fun updateAttempt(entity: SendAttemptEntity): Int

    @Update
    suspend fun updatePart(entity: AttemptPartEntity): Int

    @Query("SELECT * FROM callback_token WHERE token = :token")
    suspend fun getCallbackToken(token: String): CallbackTokenEntity?

    @Query("SELECT * FROM attempt_part WHERE attempt_id = :attemptId AND part_index = :partIndex")
    suspend fun getPart(attemptId: String, partIndex: Int): AttemptPartEntity?

    @Query(
        """
        UPDATE callback_token
        SET state = :consumedState, consumed_at = :consumedAtEpochMillis
        WHERE token = :token
          AND state = :readyState
          AND expires_at >= :consumedAtEpochMillis
        """,
    )
    suspend fun consumeCallbackToken(
        token: String,
        readyState: CallbackTokenState,
        consumedState: CallbackTokenState,
        consumedAtEpochMillis: Long,
    ): Int
}

@Dao
interface OccurrenceEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: OccurrenceEventEntity)

    @Query("SELECT * FROM occurrence_event WHERE occurrence_id = :occurrenceId ORDER BY happened_at, id")
    suspend fun listForOccurrence(occurrenceId: String): List<OccurrenceEventEntity>
}

@Dao
interface DraftDao {
    @Upsert
    suspend fun upsert(entity: ComposerDraftEntity)

    @Query("SELECT * FROM composer_draft WHERE id = :id")
    suspend fun get(id: String): ComposerDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun attach(entity: DraftAttachmentEntity)

    @Query("SELECT * FROM draft_attachment WHERE draft_id = :draftId")
    suspend fun getAttachmentLink(draftId: String): DraftAttachmentEntity?

    @Query(
        """
        SELECT attachment_asset.* FROM attachment_asset
        INNER JOIN draft_attachment ON draft_attachment.attachment_asset_id = attachment_asset.id
        WHERE draft_attachment.draft_id = :draftId
        """,
    )
    suspend fun getAttachment(draftId: String): AttachmentAssetEntity?

    @Query("DELETE FROM draft_attachment WHERE draft_id = :draftId")
    suspend fun detach(draftId: String): Int

    @Query("DELETE FROM composer_draft WHERE id = :draftId")
    suspend fun delete(draftId: String): Int
}

@Dao
interface NotificationRecordDao {
    @Upsert
    suspend fun upsert(entity: NotificationRecordEntity)

    @Query("SELECT * FROM notification_record WHERE id = :id")
    suspend fun get(id: String): NotificationRecordEntity?

    @Query("SELECT * FROM notification_record WHERE android_notification_id = :androidNotificationId")
    suspend fun findByAndroidId(androidNotificationId: Int): NotificationRecordEntity?

    @Query(
        """
        UPDATE notification_record
        SET state = :state, updated_at = :updatedAtEpochMillis
        WHERE id = :id
        """,
    )
    suspend fun setState(id: String, state: NotificationRecordState, updatedAtEpochMillis: Long): Int
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<SideEffectOutboxEntity>): List<Long>

    @Query(
        """
        SELECT * FROM side_effect_outbox
        WHERE available_at <= :nowEpochMillis
          AND (
            state = :readyState
            OR (state = :claimedState AND claim_until <= :nowEpochMillis)
          )
        ORDER BY available_at, created_at, id
        LIMIT :limit
        """,
    )
    suspend fun selectReady(
        nowEpochMillis: Long,
        readyState: OutboxState,
        claimedState: OutboxState,
        limit: Int,
    ): List<SideEffectOutboxEntity>

    @Query(
        """
        UPDATE side_effect_outbox
        SET state = :claimedState,
            claim_owner = :owner,
            claim_until = :leaseUntilEpochMillis,
            attempt_count = attempt_count + 1,
            updated_at = :nowEpochMillis
        WHERE id = :id
          AND available_at <= :nowEpochMillis
          AND (
            state = :readyState
            OR (state = :claimedState AND claim_until <= :nowEpochMillis)
            OR (state = :claimedState AND claim_owner = :owner)
          )
        """,
    )
    suspend fun claimOne(
        id: String,
        owner: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
        readyState: OutboxState,
        claimedState: OutboxState,
    ): Int

    @Query("SELECT * FROM side_effect_outbox WHERE id IN (:ids) ORDER BY available_at, created_at, id")
    suspend fun getByIds(ids: List<String>): List<SideEffectOutboxEntity>

    @Query(
        """
        UPDATE side_effect_outbox
        SET state = :completedState,
            claim_owner = NULL,
            claim_until = NULL,
            updated_at = :completedAtEpochMillis
        WHERE id = :id AND state = :claimedState AND claim_owner = :owner
        """,
    )
    suspend fun complete(
        id: String,
        owner: String,
        completedAtEpochMillis: Long,
        claimedState: OutboxState,
        completedState: OutboxState,
    ): Int

    @Query(
        """
        UPDATE side_effect_outbox
        SET state = :readyState,
            available_at = :availableAtEpochMillis,
            claim_owner = NULL,
            claim_until = NULL,
            last_error = :error,
            updated_at = :updatedAtEpochMillis
        WHERE id = :id AND state = :claimedState AND claim_owner = :owner
        """,
    )
    suspend fun retry(
        id: String,
        owner: String,
        availableAtEpochMillis: Long,
        error: String,
        updatedAtEpochMillis: Long,
        claimedState: OutboxState,
        readyState: OutboxState,
    ): Int
}
