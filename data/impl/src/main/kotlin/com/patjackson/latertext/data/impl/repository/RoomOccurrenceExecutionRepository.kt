package com.patjackson.latertext.data.impl.repository

import androidx.room.withTransaction
import com.patjackson.latertext.data.api.AlarmIdentity
import com.patjackson.latertext.data.api.AlarmSelectionChange
import com.patjackson.latertext.data.api.ClaimedOccurrence
import com.patjackson.latertext.data.api.DeliveryOutcome
import com.patjackson.latertext.data.api.ExecutionClaimResult
import com.patjackson.latertext.data.api.OccurrenceExecutionRepository
import com.patjackson.latertext.data.api.OccurrenceRecord
import com.patjackson.latertext.data.api.OccurrenceState
import com.patjackson.latertext.data.api.ScheduleState
import com.patjackson.latertext.data.api.SendOutcome
import com.patjackson.latertext.data.impl.db.LaterTextDatabase
import com.patjackson.latertext.data.impl.db.OccurrenceEntity
import com.patjackson.latertext.data.impl.db.toRecord

class RoomOccurrenceExecutionRepository(
    private val database: LaterTextDatabase,
) : OccurrenceExecutionRepository {
    override suspend fun earliestEligible(nowEpochMillis: Long): OccurrenceRecord? =
        database.occurrenceDao().earliestEligible(
            nowEpochMillis,
            ScheduleState.ACTIVE,
            ALARM_ELIGIBLE_STATES,
        )?.toRecord()

    override suspend fun selectAlarm(
        candidateId: String?,
        expectedGeneration: Long?,
        nowEpochMillis: Long,
    ): AlarmSelectionChange = database.withTransaction {
        val dao = database.occurrenceDao()
        val previous = dao.currentlyArmed(OccurrenceState.ARMED)?.toAlarmIdentity()
        if (candidateId == null) {
            dao.disarmOthers(
                keepOccurrenceId = null,
                armedState = OccurrenceState.ARMED,
                plannedState = OccurrenceState.PLANNED,
                updatedAtEpochMillis = nowEpochMillis,
            )
            return@withTransaction AlarmSelectionChange(previous, null, applied = true)
        }

        val candidate = dao.get(candidateId)
            ?: return@withTransaction AlarmSelectionChange(previous, previous, applied = false)
        val schedule = database.scheduleDao().get(candidate.scheduleId)
        val eligible = schedule?.state == ScheduleState.ACTIVE &&
            schedule.deletedAtEpochMillis == null &&
            candidate.deadlineAtEpochMillis >= nowEpochMillis &&
            candidate.state in ALARM_ELIGIBLE_STATES
        if (!eligible || expectedGeneration?.let { it != candidate.alarmGeneration } == true) {
            return@withTransaction AlarmSelectionChange(previous, previous, applied = false)
        }

        dao.disarmOthers(
            keepOccurrenceId = candidate.id,
            armedState = OccurrenceState.ARMED,
            plannedState = OccurrenceState.PLANNED,
            updatedAtEpochMillis = nowEpochMillis,
        )
        if (
            dao.armAndIncrementGeneration(
                occurrenceId = candidate.id,
                expectedGeneration = candidate.alarmGeneration,
                armedState = OccurrenceState.ARMED,
                eligibleStates = ALARM_ELIGIBLE_STATES,
                updatedAtEpochMillis = nowEpochMillis,
            ) != 1
        ) {
            error("Alarm candidate changed inside a serialized Room transaction")
        }
        val current = requireNotNull(dao.get(candidate.id)).toAlarmIdentity()
        AlarmSelectionChange(previous, current, applied = true)
    }

    override suspend fun claimExpected(
        occurrenceId: String,
        generation: Long,
        owner: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
    ): ExecutionClaimResult {
        require(owner.isNotBlank())
        require(generation >= 0)
        require(leaseUntilEpochMillis > nowEpochMillis)
        return database.withTransaction {
            val occurrence = database.occurrenceDao().get(occurrenceId)
                ?: return@withTransaction ExecutionClaimResult.Rejected(null, "not_found")
            val record = occurrence.toRecord()
            if (occurrence.alarmGeneration != generation) {
                return@withTransaction ExecutionClaimResult.StaleGeneration(record)
            }
            val schedule = requireNotNull(database.scheduleDao().get(occurrence.scheduleId))
            if (occurrence.deadlineAtEpochMillis < nowEpochMillis) {
                return@withTransaction ExecutionClaimResult.Expired(record, schedule.state)
            }
            if (schedule.state == ScheduleState.PAUSED) {
                return@withTransaction ExecutionClaimResult.Paused(record)
            }
            if (schedule.state != ScheduleState.ACTIVE || schedule.deletedAtEpochMillis != null) {
                return@withTransaction ExecutionClaimResult.Rejected(record, "schedule_inactive")
            }
            if (
                database.occurrenceDao().claimExpectedGeneration(
                    occurrenceId = occurrenceId,
                    expectedAlarmGeneration = generation,
                    owner = owner,
                    nowEpochMillis = nowEpochMillis,
                    leaseUntilEpochMillis = leaseUntilEpochMillis,
                    claimedState = OccurrenceState.CLAIMED,
                    claimableStates = CLAIMABLE_STATES,
                ) != 1
            ) {
                return@withTransaction ExecutionClaimResult.Rejected(record, "not_claimable_or_lease_held")
            }

            val claimed = requireNotNull(database.occurrenceDao().get(occurrenceId))
            val recipient = requireNotNull(database.recipientDao().get(schedule.recipientEndpointId))
            val content = requireNotNull(database.contentDao().get(claimed.contentRevisionId))
            val attachment = database.contentDao().getWithAttachment(content.id)?.attachments?.singleOrNull()
            val rule = requireNotNull(database.ruleDao().get(claimed.ruleRevisionId))
            ExecutionClaimResult.Claimed(
                ClaimedOccurrence(
                    occurrence = claimed.toRecord(),
                    schedule = schedule.toRecord(),
                    recipient = recipient.toRecord(),
                    content = content.toRecord(),
                    attachment = attachment?.toRecord(),
                    rule = rule.toRecord(),
                ),
            )
        }
    }

    override suspend fun transitionClaimed(
        occurrenceId: String,
        owner: String,
        newState: OccurrenceState,
        sendOutcome: SendOutcome?,
        deliveryOutcome: DeliveryOutcome?,
        activeAttemptId: String?,
        retryAtEpochMillis: Long?,
        nowEpochMillis: Long,
    ): Boolean = database.occurrenceDao().transitionAfterClaim(
        occurrenceId = occurrenceId,
        owner = owner,
        newState = newState,
        sendOutcome = sendOutcome,
        deliveryOutcome = deliveryOutcome,
        activeAttemptId = activeAttemptId,
        replaceActiveAttemptId = activeAttemptId != null,
        retryAtEpochMillis = retryAtEpochMillis,
        replaceRetryAt = true,
        updatedAtEpochMillis = nowEpochMillis,
    ) == 1

    override suspend fun applyAttemptProjection(
        occurrenceId: String,
        expectedAttemptId: String,
        newState: OccurrenceState,
        sendOutcome: SendOutcome?,
        deliveryOutcome: DeliveryOutcome?,
        retryAtEpochMillis: Long?,
        nowEpochMillis: Long,
    ): Boolean = database.occurrenceDao().applyAttemptProjection(
        occurrenceId = occurrenceId,
        expectedAttemptId = expectedAttemptId,
        newState = newState,
        sendOutcome = sendOutcome,
        deliveryOutcome = deliveryOutcome,
        replacementAttemptId = null,
        replaceActiveAttemptId = false,
        retryAtEpochMillis = retryAtEpochMillis,
        replaceRetryAt = true,
        updatedAtEpochMillis = nowEpochMillis,
    ) == 1

    override suspend fun attemptCount(occurrenceId: String): Int =
        database.attemptDao().countForOccurrence(occurrenceId)

    override suspend fun latestAttempt(occurrenceId: String) =
        database.attemptDao().latestForOccurrence(occurrenceId)?.toRecord()

    private fun OccurrenceEntity.toAlarmIdentity() = AlarmIdentity(
        occurrenceId = id,
        generation = alarmGeneration,
        targetAtEpochMillis = retryAtEpochMillis ?: targetAtEpochMillis,
    )

    companion object {
        private val ALARM_ELIGIBLE_STATES = listOf(
            OccurrenceState.PLANNED,
            OccurrenceState.ARMED,
            OccurrenceState.DUE,
            OccurrenceState.RETRY_WAIT,
        )
        private val CLAIMABLE_STATES = listOf(
            OccurrenceState.ARMED,
            OccurrenceState.DUE,
            OccurrenceState.RETRY_WAIT,
            OccurrenceState.CLAIMED,
        )
    }
}
