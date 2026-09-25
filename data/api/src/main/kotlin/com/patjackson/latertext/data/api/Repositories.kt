package com.patjackson.latertext.data.api

import kotlinx.coroutines.flow.Flow

data class CreateScheduleCommand(
    val recipient: RecipientEndpointRecord,
    val schedule: ScheduleRecord,
    val contentRevision: ContentRevisionRecord,
    val contentAttachment: ContentAttachmentRecord? = null,
    val ruleRevision: RuleRevisionRecord,
    val occurrences: List<OccurrenceRecord>,
    val outboxEffects: List<SideEffectOutboxRecord> = emptyList(),
)

data class UpdateScheduleContentCommand(
    val scheduleId: String,
    val expectedCurrentRevisionId: String,
    val contentRevision: ContentRevisionRecord,
    val contentAttachment: ContentAttachmentRecord? = null,
    val updateUnclaimedOccurrencesAtOrAfterEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val outboxEffects: List<SideEffectOutboxRecord> = emptyList(),
)

data class UpdateScheduleRuleCommand(
    val scheduleId: String,
    val expectedCurrentRevisionId: String,
    val ruleRevision: RuleRevisionRecord,
    val replacementOccurrences: List<OccurrenceRecord>,
    val replaceUnclaimedOccurrencesAtOrAfterEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val outboxEffects: List<SideEffectOutboxRecord> = emptyList(),
)

data class ClaimedOccurrence(
    val occurrence: OccurrenceRecord,
    val schedule: ScheduleRecord,
    val recipient: RecipientEndpointRecord,
    val content: ContentRevisionRecord,
    val attachment: AttachmentAssetRecord?,
    val rule: RuleRevisionRecord,
)

data class AlarmIdentity(
    val occurrenceId: String,
    val generation: Long,
    val targetAtEpochMillis: Long,
)

data class AlarmSelectionChange(
    val previous: AlarmIdentity?,
    val current: AlarmIdentity?,
    val applied: Boolean,
)

sealed interface ExecutionClaimResult {
    data class Claimed(val value: ClaimedOccurrence) : ExecutionClaimResult
    data class Paused(val occurrence: OccurrenceRecord) : ExecutionClaimResult
    data class Expired(
        val occurrence: OccurrenceRecord,
        val scheduleState: ScheduleState,
    ) : ExecutionClaimResult
    data class StaleGeneration(val occurrence: OccurrenceRecord) : ExecutionClaimResult
    data class Rejected(val occurrence: OccurrenceRecord?, val reason: String) : ExecutionClaimResult
}

/**
 * An optimistic, all-or-nothing projection from a corroborating source such as the SMS provider.
 * The expected attempt and parts prevent an older provider read from replacing a callback that
 * committed while the provider query was in flight.
 */
data class CorroboratedAttemptProjection(
    val expectedOccurrenceStates: Set<OccurrenceState>,
    val expectedAttempt: SendAttemptRecord,
    val expectedParts: List<AttemptPartRecord>,
    val occurrenceState: OccurrenceState,
    val sendOutcome: SendOutcome,
    val deliveryOutcome: DeliveryOutcome,
    val attempt: SendAttemptRecord,
    val parts: List<AttemptPartRecord>,
    val event: OccurrenceEventRecord,
)

interface OccurrenceExecutionRepository {
    suspend fun earliestEligible(nowEpochMillis: Long): OccurrenceRecord?

    /** Atomically disarms the previous selection and increments the selected alarm generation. */
    suspend fun selectAlarm(
        candidateId: String?,
        expectedGeneration: Long?,
        nowEpochMillis: Long,
    ): AlarmSelectionChange

    /** Claims only the immutable PendingIntent generation that actually fired. */
    suspend fun claimExpected(
        occurrenceId: String,
        generation: Long,
        owner: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
    ): ExecutionClaimResult

    /** Null outcome dimensions preserve their existing values; retryAt null clears a prior retry. */
    suspend fun transitionClaimed(
        occurrenceId: String,
        owner: String,
        newState: OccurrenceState,
        sendOutcome: SendOutcome? = null,
        deliveryOutcome: DeliveryOutcome? = null,
        activeAttemptId: String? = null,
        retryAtEpochMillis: Long? = null,
        nowEpochMillis: Long,
    ): Boolean

    /** Applies a callback only to the current attempt, preserving unspecified dimensions. */
    suspend fun applyAttemptProjection(
        occurrenceId: String,
        expectedAttemptId: String,
        newState: OccurrenceState,
        sendOutcome: SendOutcome? = null,
        deliveryOutcome: DeliveryOutcome? = null,
        retryAtEpochMillis: Long? = null,
        nowEpochMillis: Long,
    ): Boolean

    /**
     * Atomically updates occurrence, attempt, parts, and audit event only if none of the expected
     * callback-owned records changed since they were read.
     */
    suspend fun applyCorroboratedProjection(projection: CorroboratedAttemptProjection): Boolean

    suspend fun attemptCount(occurrenceId: String): Int
    suspend fun latestAttempt(occurrenceId: String): AttemptBundle?
}

interface ScheduleRepository {
    suspend fun create(command: CreateScheduleCommand): ScheduleGraph
    suspend fun updateContent(command: UpdateScheduleContentCommand): Boolean
    suspend fun updateRule(command: UpdateScheduleRuleCommand): Boolean
    suspend fun get(scheduleId: String): ScheduleGraph?
    suspend fun listUpcoming(limit: Int = 100): List<ScheduleGraph>
    suspend fun listAll(limit: Int = 100): List<ScheduleGraph>
    /** Emits initially and whenever schedule execution state affecting UI projections changes. */
    fun observeChanges(): Flow<Unit>
    suspend fun contentRevision(contentRevisionId: String): ContentRevisionRecord?
    suspend fun setPaused(scheduleId: String, paused: Boolean, updatedAtEpochMillis: Long): Boolean
    suspend fun softDelete(scheduleId: String, deletedAtEpochMillis: Long): Boolean
}

interface OccurrenceRepository {
    suspend fun get(occurrenceId: String): OccurrenceRecord?
    suspend fun nextActionable(): OccurrenceRecord?
    suspend fun listDue(nowEpochMillis: Long, limit: Int): List<OccurrenceRecord>
    suspend fun listForSchedule(scheduleId: String): List<OccurrenceRecord>
    suspend fun insertMaterialized(occurrences: List<OccurrenceRecord>)

    /** Atomically claims one occurrence. An active lease owned by another worker wins. */
    suspend fun claim(
        occurrenceId: String,
        owner: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
    ): ClaimedOccurrence?

    suspend fun releaseClaim(
        occurrenceId: String,
        owner: String,
        nextState: OccurrenceState,
        updatedAtEpochMillis: Long,
    ): Boolean

    suspend fun compareAndSetState(
        occurrenceId: String,
        expectedStates: Set<OccurrenceState>,
        newState: OccurrenceState,
        updatedAtEpochMillis: Long,
    ): Boolean

    suspend fun compareAndSetAlarmGeneration(
        occurrenceId: String,
        expectedGeneration: Long,
        newGeneration: Long,
        updatedAtEpochMillis: Long,
    ): Boolean

    suspend fun appendEvent(event: OccurrenceEventRecord)
}

data class AttemptBundle(
    val attempt: SendAttemptRecord,
    val parts: List<AttemptPartRecord>,
    val callbackTokens: List<CallbackTokenRecord>,
)

interface AttemptRepository {
    suspend fun create(bundle: AttemptBundle)
    suspend fun get(attemptId: String): AttemptBundle?
    suspend fun updatePart(part: AttemptPartRecord): Boolean
    suspend fun updateAttempt(attempt: SendAttemptRecord): Boolean

    /** Consumes a callback token and applies its part result in the same transaction. */
    suspend fun applyCallback(
        token: String,
        outcome: PartOutcome,
        platformResultCode: Int?,
        receivedAtEpochMillis: Long,
    ): AttemptBundle?
}

interface OutboxRepository {
    suspend fun enqueue(records: List<SideEffectOutboxRecord>)

    /** Claims an ordered batch in one transaction, including leases abandoned by dead workers. */
    suspend fun claimReady(
        owner: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
        limit: Int,
    ): List<SideEffectOutboxRecord>

    suspend fun complete(id: String, owner: String, completedAtEpochMillis: Long): Boolean
    suspend fun retry(
        id: String,
        owner: String,
        availableAtEpochMillis: Long,
        error: String,
        updatedAtEpochMillis: Long,
    ): Boolean
}

interface DraftRepository {
    suspend fun save(draft: ComposerDraftRecord)
    suspend fun get(draftId: String): ComposerDraftRecord?
    suspend fun attach(draftAttachment: DraftAttachmentRecord)
    suspend fun detach(draftId: String): AttachmentAssetRecord?
    suspend fun getAttachment(draftId: String): AttachmentAssetRecord?
    suspend fun delete(draftId: String): AttachmentAssetRecord?
}

interface RecipientRepository {
    suspend fun upsert(endpoint: RecipientEndpointRecord)
    suspend fun get(endpointId: String): RecipientEndpointRecord?
    suspend fun recordRecent(recipient: RecentRecipientRecord)
    suspend fun listRecent(limit: Int = 20): List<RecentRecipientRecord>
}

interface NotificationRecordRepository {
    suspend fun upsert(record: NotificationRecord)
    suspend fun get(id: String): NotificationRecord?
    suspend fun findByAndroidId(androidNotificationId: Int): NotificationRecord?
}
