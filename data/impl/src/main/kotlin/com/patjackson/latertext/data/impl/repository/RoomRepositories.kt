package com.patjackson.latertext.data.impl.repository

import androidx.room.withTransaction
import com.patjackson.latertext.data.api.AttemptBundle
import com.patjackson.latertext.data.api.AttemptRepository
import com.patjackson.latertext.data.api.AttachmentRepository
import com.patjackson.latertext.data.api.AttachmentStorageClass
import com.patjackson.latertext.data.api.CallbackKind
import com.patjackson.latertext.data.api.ClaimedOccurrence
import com.patjackson.latertext.data.api.CreateScheduleCommand
import com.patjackson.latertext.data.api.DraftRepository
import com.patjackson.latertext.data.api.NotificationRecord
import com.patjackson.latertext.data.api.NotificationRecordRepository
import com.patjackson.latertext.data.api.OccurrenceEventRecord
import com.patjackson.latertext.data.api.OccurrenceRecord
import com.patjackson.latertext.data.api.OccurrenceRepository
import com.patjackson.latertext.data.api.OccurrenceState
import com.patjackson.latertext.data.api.OutboxRepository
import com.patjackson.latertext.data.api.OutboxState
import com.patjackson.latertext.data.api.PartOutcome
import com.patjackson.latertext.data.api.RecentRecipientRecord
import com.patjackson.latertext.data.api.RecipientEndpointRecord
import com.patjackson.latertext.data.api.RecipientRepository
import com.patjackson.latertext.data.api.ScheduleGraph
import com.patjackson.latertext.data.api.ScheduleRepository
import com.patjackson.latertext.data.api.ScheduleState
import com.patjackson.latertext.data.api.SideEffectOutboxRecord
import com.patjackson.latertext.data.api.UpdateScheduleContentCommand
import com.patjackson.latertext.data.api.UpdateScheduleRuleCommand
import com.patjackson.latertext.data.impl.db.AttachmentAssetEntity
import com.patjackson.latertext.data.impl.db.LaterTextDatabase
import com.patjackson.latertext.data.impl.db.ScheduleAggregate
import com.patjackson.latertext.data.impl.db.toEntity
import com.patjackson.latertext.data.impl.db.toRecord

class RoomScheduleRepository(
    private val database: LaterTextDatabase,
    private val attachmentRepository: AttachmentRepository,
) : ScheduleRepository {
    override suspend fun create(command: CreateScheduleCommand): ScheduleGraph {
        validateCreate(command)
        promoteLinkedAttachment(command.contentAttachment?.attachmentAssetId, command.schedule.updatedAtEpochMillis)

        return database.withTransaction {
            val scheduleDao = database.scheduleDao()
            database.recipientDao().upsert(command.recipient.toEntity())
            scheduleDao.insert(
                command.schedule.toEntity(
                    activeContentRevisionId = null,
                    activeRuleRevisionId = null,
                ),
            )
            database.contentDao().insert(command.contentRevision.toEntity())
            command.contentAttachment?.let { database.contentDao().insertAttachment(it.toEntity()) }
            database.ruleDao().insert(command.ruleRevision.toEntity())
            database.occurrenceDao().insertAll(command.occurrences.map { it.toEntity() })
            database.outboxDao().insertAll(command.outboxEffects.map { it.toEntity() })
            check(
                scheduleDao.activateInitialRevisions(
                    scheduleId = command.schedule.id,
                    contentRevisionId = command.contentRevision.id,
                    ruleRevisionId = command.ruleRevision.id,
                    updatedAtEpochMillis = command.schedule.updatedAtEpochMillis,
                ) == 1,
            ) { "Schedule initial revision activation failed" }
            requireNotNull(loadGraph(command.schedule.id))
        }
    }

    override suspend fun updateContent(command: UpdateScheduleContentCommand): Boolean {
        require(command.contentRevision.scheduleId == command.scheduleId)
        require(command.contentRevision.id != command.expectedCurrentRevisionId)
        command.contentAttachment?.let {
            require(it.contentRevisionId == command.contentRevision.id)
        }
        promoteLinkedAttachment(command.contentAttachment?.attachmentAssetId, command.updatedAtEpochMillis)

        return database.withTransaction {
            val current = database.scheduleDao().get(command.scheduleId)
            if (current?.activeContentRevisionId != command.expectedCurrentRevisionId || current.deletedAtEpochMillis != null) {
                return@withTransaction false
            }
            database.contentDao().insert(command.contentRevision.toEntity())
            command.contentAttachment?.let { database.contentDao().insertAttachment(it.toEntity()) }
            check(
                database.scheduleDao().compareAndSetContentRevision(
                    command.scheduleId,
                    command.expectedCurrentRevisionId,
                    command.contentRevision.id,
                    command.updatedAtEpochMillis,
                ) == 1,
            )
            database.occurrenceDao().updateUnclaimedContent(
                scheduleId = command.scheduleId,
                contentRevisionId = command.contentRevision.id,
                atOrAfterEpochMillis = command.updateUnclaimedOccurrencesAtOrAfterEpochMillis,
                replaceableStates = REPLACEABLE_OCCURRENCE_STATES,
                updatedAtEpochMillis = command.updatedAtEpochMillis,
            )
            database.outboxDao().insertAll(command.outboxEffects.map { it.toEntity() })
            true
        }
    }

    override suspend fun updateRule(command: UpdateScheduleRuleCommand): Boolean {
        require(command.ruleRevision.scheduleId == command.scheduleId)
        require(command.ruleRevision.id != command.expectedCurrentRevisionId)
        command.replacementOccurrences.forEach {
            require(it.scheduleId == command.scheduleId)
            require(it.ruleRevisionId == command.ruleRevision.id)
        }

        return database.withTransaction {
            val current = database.scheduleDao().get(command.scheduleId)
            if (current?.activeRuleRevisionId != command.expectedCurrentRevisionId || current.deletedAtEpochMillis != null) {
                return@withTransaction false
            }
            database.ruleDao().insert(command.ruleRevision.toEntity())
            database.occurrenceDao().deleteUnclaimedFuture(
                scheduleId = command.scheduleId,
                atOrAfterEpochMillis = command.replaceUnclaimedOccurrencesAtOrAfterEpochMillis,
                replaceableStates = REPLACEABLE_OCCURRENCE_STATES,
            )
            database.occurrenceDao().insertAll(command.replacementOccurrences.map { it.toEntity() })
            check(
                database.scheduleDao().compareAndSetRuleRevision(
                    command.scheduleId,
                    command.expectedCurrentRevisionId,
                    command.ruleRevision.id,
                    command.updatedAtEpochMillis,
                ) == 1,
            )
            database.outboxDao().insertAll(command.outboxEffects.map { it.toEntity() })
            true
        }
    }

    override suspend fun get(scheduleId: String): ScheduleGraph? = database.withTransaction {
        loadGraph(scheduleId)
    }

    override suspend fun listUpcoming(limit: Int): List<ScheduleGraph> {
        require(limit > 0)
        return database.withTransaction {
            database.scheduleDao().listUpcoming(limit).map { aggregate ->
                aggregate.toRecord(loadActiveAttachment(aggregate))
            }
        }
    }

    override suspend fun listAll(limit: Int): List<ScheduleGraph> {
        require(limit > 0)
        return database.withTransaction {
            database.scheduleDao().listAll(limit).map { aggregate ->
                aggregate.toRecord(loadActiveAttachment(aggregate))
            }
        }
    }

    override suspend fun setPaused(
        scheduleId: String,
        paused: Boolean,
        updatedAtEpochMillis: Long,
    ): Boolean = database.scheduleDao().setState(
        scheduleId,
        if (paused) ScheduleState.PAUSED else ScheduleState.ACTIVE,
        updatedAtEpochMillis,
    ) == 1

    override suspend fun softDelete(scheduleId: String, deletedAtEpochMillis: Long): Boolean =
        database.withTransaction {
            val changed = database.scheduleDao().softDelete(
                scheduleId,
                ScheduleState.DELETED,
                deletedAtEpochMillis,
            )
            if (changed == 1) {
                database.occurrenceDao().cancelUnclaimed(
                    scheduleId,
                    OccurrenceState.CANCELLED,
                    REPLACEABLE_OCCURRENCE_STATES,
                    deletedAtEpochMillis,
                )
            }
            changed == 1
        }

    private suspend fun promoteLinkedAttachment(assetId: String?, updatedAtEpochMillis: Long) {
        if (assetId == null) return
        val asset = attachmentRepository.get(assetId)
            ?: error("Attachment asset $assetId has not been staged")
        if (asset.storageClass != AttachmentStorageClass.DURABLE) {
            attachmentRepository.promoteToDurable(assetId, updatedAtEpochMillis)
        }
    }

    private suspend fun loadGraph(scheduleId: String): ScheduleGraph? =
        database.scheduleDao().getAggregate(scheduleId)?.let { aggregate ->
            aggregate.toRecord(loadActiveAttachment(aggregate))
        }

    private suspend fun loadActiveAttachment(aggregate: ScheduleAggregate): AttachmentAssetEntity? {
        val contentId = aggregate.schedule.activeContentRevisionId ?: return null
        return database.contentDao().getWithAttachment(contentId)?.attachments?.singleOrNull()
    }

    private fun validateCreate(command: CreateScheduleCommand) {
        require(command.schedule.id.isNotBlank())
        require(command.schedule.recipientEndpointId == command.recipient.id)
        require(command.contentRevision.scheduleId == command.schedule.id)
        require(command.ruleRevision.scheduleId == command.schedule.id)
        require(
            command.schedule.activeContentRevisionId == null ||
                command.schedule.activeContentRevisionId == command.contentRevision.id,
        )
        require(
            command.schedule.activeRuleRevisionId == null ||
                command.schedule.activeRuleRevisionId == command.ruleRevision.id,
        )
        command.contentAttachment?.let { require(it.contentRevisionId == command.contentRevision.id) }
        command.occurrences.forEach {
            require(it.scheduleId == command.schedule.id)
            require(it.contentRevisionId == command.contentRevision.id)
            require(it.ruleRevisionId == command.ruleRevision.id)
        }
    }

    companion object {
        private val REPLACEABLE_OCCURRENCE_STATES = listOf(
            OccurrenceState.PLANNED,
            OccurrenceState.ARMED,
            OccurrenceState.DUE,
            OccurrenceState.RETRY_WAIT,
        )
    }
}

class RoomOccurrenceRepository(private val database: LaterTextDatabase) : OccurrenceRepository {
    override suspend fun get(occurrenceId: String): OccurrenceRecord? =
        database.occurrenceDao().get(occurrenceId)?.toRecord()

    override suspend fun nextActionable(): OccurrenceRecord? =
        database.occurrenceDao().nextActionable(ACTIONABLE_STATES)?.toRecord()

    override suspend fun listDue(nowEpochMillis: Long, limit: Int): List<OccurrenceRecord> {
        require(limit > 0)
        return database.occurrenceDao().listDue(nowEpochMillis, DUE_STATES, limit).map { it.toRecord() }
    }

    override suspend fun listForSchedule(scheduleId: String): List<OccurrenceRecord> =
        database.occurrenceDao().listForSchedule(scheduleId).map { it.toRecord() }

    override suspend fun insertMaterialized(occurrences: List<OccurrenceRecord>) {
        if (occurrences.isEmpty()) return
        database.occurrenceDao().insertAll(occurrences.map { it.toEntity() })
    }

    override suspend fun claim(
        occurrenceId: String,
        owner: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
    ): ClaimedOccurrence? {
        require(owner.isNotBlank())
        require(leaseUntilEpochMillis > nowEpochMillis)
        return database.withTransaction {
            if (
                database.occurrenceDao().claim(
                    occurrenceId,
                    owner,
                    nowEpochMillis,
                    leaseUntilEpochMillis,
                    OccurrenceState.CLAIMED,
                    CLAIMABLE_STATES,
                ) != 1
            ) return@withTransaction null

            val occurrence = requireNotNull(database.occurrenceDao().get(occurrenceId))
            val schedule = requireNotNull(database.scheduleDao().get(occurrence.scheduleId))
            if (schedule.deletedAtEpochMillis != null || schedule.state != ScheduleState.ACTIVE) {
                val releaseState = when {
                    schedule.deletedAtEpochMillis != null -> OccurrenceState.CANCELLED
                    schedule.state == ScheduleState.PAUSED &&
                        occurrence.deadlineAtEpochMillis <= nowEpochMillis -> OccurrenceState.SKIPPED_PAUSED
                    schedule.state == ScheduleState.CANCELLED ||
                        schedule.state == ScheduleState.DELETED ||
                        schedule.state == ScheduleState.COMPLETED -> OccurrenceState.CANCELLED
                    else -> OccurrenceState.PLANNED
                }
                database.occurrenceDao().releaseClaim(
                    occurrenceId,
                    owner,
                    releaseState,
                    nowEpochMillis,
                )
                return@withTransaction null
            }
            val recipient = requireNotNull(database.recipientDao().get(schedule.recipientEndpointId))
            val content = requireNotNull(database.contentDao().get(occurrence.contentRevisionId))
            val rule = requireNotNull(database.ruleDao().get(occurrence.ruleRevisionId))
            val attachment = database.contentDao().getWithAttachment(content.id)?.attachments?.singleOrNull()
            ClaimedOccurrence(
                occurrence.toRecord(),
                schedule.toRecord(),
                recipient.toRecord(),
                content.toRecord(),
                attachment?.toRecord(),
                rule.toRecord(),
            )
        }
    }

    override suspend fun releaseClaim(
        occurrenceId: String,
        owner: String,
        nextState: OccurrenceState,
        updatedAtEpochMillis: Long,
    ): Boolean = database.occurrenceDao().releaseClaim(
        occurrenceId,
        owner,
        nextState,
        updatedAtEpochMillis,
    ) == 1

    override suspend fun compareAndSetState(
        occurrenceId: String,
        expectedStates: Set<OccurrenceState>,
        newState: OccurrenceState,
        updatedAtEpochMillis: Long,
    ): Boolean {
        require(expectedStates.isNotEmpty())
        return database.occurrenceDao().compareAndSetState(
            occurrenceId,
            expectedStates.toList(),
            newState,
            updatedAtEpochMillis,
        ) == 1
    }

    override suspend fun compareAndSetAlarmGeneration(
        occurrenceId: String,
        expectedGeneration: Long,
        newGeneration: Long,
        updatedAtEpochMillis: Long,
    ): Boolean {
        require(expectedGeneration >= 0 && newGeneration > expectedGeneration)
        return database.occurrenceDao().compareAndSetAlarmGeneration(
            occurrenceId,
            expectedGeneration,
            newGeneration,
            updatedAtEpochMillis,
        ) == 1
    }

    override suspend fun appendEvent(event: OccurrenceEventRecord) {
        database.occurrenceEventDao().insert(event.toEntity())
    }

    companion object {
        private val ACTIONABLE_STATES = listOf(
            OccurrenceState.PLANNED,
            OccurrenceState.ARMED,
            OccurrenceState.DUE,
            OccurrenceState.RETRY_WAIT,
            OccurrenceState.READY_FOR_USER,
        )
        private val DUE_STATES = listOf(OccurrenceState.PLANNED, OccurrenceState.ARMED, OccurrenceState.DUE)
        private val CLAIMABLE_STATES = DUE_STATES + OccurrenceState.CLAIMED + OccurrenceState.RETRY_WAIT
    }
}

class RoomAttemptRepository(private val database: LaterTextDatabase) : AttemptRepository {
    override suspend fun create(bundle: AttemptBundle) {
        require(bundle.parts.isNotEmpty())
        require(bundle.parts.all { it.attemptId == bundle.attempt.id })
        require(bundle.callbackTokens.all { it.attemptId == bundle.attempt.id })
        database.withTransaction {
            database.attemptDao().insertAttempt(bundle.attempt.toEntity())
            database.attemptDao().insertParts(bundle.parts.map { it.toEntity() })
            database.attemptDao().insertCallbackTokens(bundle.callbackTokens.map { it.toEntity() })
        }
    }

    override suspend fun get(attemptId: String): AttemptBundle? =
        database.attemptDao().getAggregate(attemptId)?.toRecord()

    override suspend fun updatePart(part: com.patjackson.latertext.data.api.AttemptPartRecord): Boolean =
        database.attemptDao().updatePart(part.toEntity()) == 1

    override suspend fun updateAttempt(attempt: com.patjackson.latertext.data.api.SendAttemptRecord): Boolean =
        database.attemptDao().updateAttempt(attempt.toEntity()) == 1

    override suspend fun applyCallback(
        token: String,
        outcome: PartOutcome,
        platformResultCode: Int?,
        receivedAtEpochMillis: Long,
    ): AttemptBundle? =
        database.withTransaction {
            val callback = database.attemptDao().getCallbackToken(token) ?: return@withTransaction null
            if (
                database.attemptDao().consumeCallbackToken(
                    token,
                    com.patjackson.latertext.data.api.CallbackTokenState.READY,
                    com.patjackson.latertext.data.api.CallbackTokenState.CONSUMED,
                    receivedAtEpochMillis,
                ) != 1
            ) return@withTransaction null
            val currentPart = requireNotNull(
                database.attemptDao().getPart(callback.attemptId, callback.partIndex),
            )
            val updatedPart = when (callback.kind) {
                CallbackKind.SENT -> currentPart.copy(
                    sendOutcome = outcome,
                    sentResultCode = platformResultCode,
                    sentAtEpochMillis = receivedAtEpochMillis,
                )
                CallbackKind.DELIVERED -> currentPart.copy(
                    deliveryOutcome = outcome,
                    deliveryResultCode = platformResultCode,
                    deliveredAtEpochMillis = receivedAtEpochMillis,
                )
            }
            check(database.attemptDao().updatePart(updatedPart) == 1)
            database.attemptDao().getAggregate(callback.attemptId)?.toRecord()
        }
}

class RoomOutboxRepository(private val database: LaterTextDatabase) : OutboxRepository {
    override suspend fun enqueue(records: List<SideEffectOutboxRecord>) {
        if (records.isNotEmpty()) database.outboxDao().insertAll(records.map { it.toEntity() })
    }

    override suspend fun claimReady(
        owner: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
        limit: Int,
    ): List<SideEffectOutboxRecord> {
        require(owner.isNotBlank())
        require(limit > 0)
        require(leaseUntilEpochMillis > nowEpochMillis)
        return database.withTransaction {
            val candidates = database.outboxDao().selectReady(
                nowEpochMillis,
                OutboxState.READY,
                OutboxState.CLAIMED,
                limit,
            )
            val ids = candidates.mapNotNull { candidate ->
                candidate.id.takeIf {
                    database.outboxDao().claimOne(
                        candidate.id,
                        owner,
                        nowEpochMillis,
                        leaseUntilEpochMillis,
                        OutboxState.READY,
                        OutboxState.CLAIMED,
                    ) == 1
                }
            }
            if (ids.isEmpty()) emptyList()
            else database.outboxDao().getByIds(ids).map { it.toRecord() }
        }
    }

    override suspend fun complete(id: String, owner: String, completedAtEpochMillis: Long): Boolean =
        database.outboxDao().complete(
            id, owner, completedAtEpochMillis, OutboxState.CLAIMED, OutboxState.COMPLETED,
        ) == 1

    override suspend fun retry(
        id: String,
        owner: String,
        availableAtEpochMillis: Long,
        error: String,
        updatedAtEpochMillis: Long,
    ): Boolean = database.outboxDao().retry(
        id,
        owner,
        availableAtEpochMillis,
        error,
        updatedAtEpochMillis,
        OutboxState.CLAIMED,
        OutboxState.READY,
    ) == 1
}

class RoomDraftRepository(private val database: LaterTextDatabase) : DraftRepository {
    override suspend fun save(draft: com.patjackson.latertext.data.api.ComposerDraftRecord) {
        database.draftDao().upsert(draft.toEntity())
    }

    override suspend fun get(draftId: String) = database.draftDao().get(draftId)?.toRecord()

    override suspend fun attach(draftAttachment: com.patjackson.latertext.data.api.DraftAttachmentRecord) {
        database.draftDao().attach(draftAttachment.toEntity())
    }

    override suspend fun detach(draftId: String) = database.withTransaction {
        val asset = database.draftDao().getAttachment(draftId)
        database.draftDao().detach(draftId)
        asset?.toRecord()
    }

    override suspend fun getAttachment(draftId: String) =
        database.draftDao().getAttachment(draftId)?.toRecord()

    override suspend fun delete(draftId: String) = database.withTransaction {
        val asset = database.draftDao().getAttachment(draftId)
        database.draftDao().delete(draftId)
        asset?.toRecord()
    }
}

class RoomRecipientRepository(private val database: LaterTextDatabase) : RecipientRepository {
    override suspend fun upsert(endpoint: RecipientEndpointRecord) {
        database.recipientDao().upsert(endpoint.toEntity())
    }

    override suspend fun get(endpointId: String) = database.recipientDao().get(endpointId)?.toRecord()

    override suspend fun recordRecent(recipient: RecentRecipientRecord) {
        database.recipientDao().upsertRecent(recipient.toEntity())
    }

    override suspend fun listRecent(limit: Int): List<RecentRecipientRecord> {
        require(limit > 0)
        return database.recipientDao().listRecent(limit).map { it.toRecord() }
    }
}

class RoomNotificationRecordRepository(
    private val database: LaterTextDatabase,
) : NotificationRecordRepository {
    override suspend fun upsert(record: NotificationRecord) {
        database.notificationRecordDao().upsert(record.toEntity())
    }

    override suspend fun get(id: String) = database.notificationRecordDao().get(id)?.toRecord()

    override suspend fun findByAndroidId(androidNotificationId: Int) =
        database.notificationRecordDao().findByAndroidId(androidNotificationId)?.toRecord()
}
