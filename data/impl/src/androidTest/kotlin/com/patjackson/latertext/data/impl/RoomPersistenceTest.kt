package com.patjackson.latertext.data.impl

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patjackson.latertext.data.api.AttachmentInput
import com.patjackson.latertext.data.api.AttachmentIntakeSource
import com.patjackson.latertext.data.api.AttachmentStorageClass
import com.patjackson.latertext.data.api.AttachmentWriteRequest
import com.patjackson.latertext.data.api.AttemptBundle
import com.patjackson.latertext.data.api.AttemptPartRecord
import com.patjackson.latertext.data.api.ContentRevisionRecord
import com.patjackson.latertext.data.api.CorroboratedAttemptProjection
import com.patjackson.latertext.data.api.CreateScheduleCommand
import com.patjackson.latertext.data.api.DeliveryOutcome
import com.patjackson.latertext.data.api.DstResolution
import com.patjackson.latertext.data.api.EndCondition
import com.patjackson.latertext.data.api.ExecutionClaimResult
import com.patjackson.latertext.data.api.MissedPolicy
import com.patjackson.latertext.data.api.MonthlyEdgePolicy
import com.patjackson.latertext.data.api.OccurrenceRecord
import com.patjackson.latertext.data.api.OccurrenceEventRecord
import com.patjackson.latertext.data.api.OccurrenceState
import com.patjackson.latertext.data.api.OutboxState
import com.patjackson.latertext.data.api.PartOutcome
import com.patjackson.latertext.data.api.RecipientEndpointRecord
import com.patjackson.latertext.data.api.RecipientSource
import com.patjackson.latertext.data.api.RecurrenceFrequency
import com.patjackson.latertext.data.api.RuleRevisionRecord
import com.patjackson.latertext.data.api.ScheduleRecord
import com.patjackson.latertext.data.api.ScheduleState
import com.patjackson.latertext.data.api.SendOutcome
import com.patjackson.latertext.data.api.SendAttemptRecord
import com.patjackson.latertext.data.api.SideEffectOutboxRecord
import com.patjackson.latertext.data.api.TransportMode
import com.patjackson.latertext.data.api.ZonePolicy
import com.patjackson.latertext.data.impl.attachment.PrivateAttachmentStore
import com.patjackson.latertext.data.impl.db.LaterTextDatabase
import com.patjackson.latertext.data.impl.repository.RoomAttachmentRepository
import com.patjackson.latertext.data.impl.repository.RoomOccurrenceRepository
import com.patjackson.latertext.data.impl.repository.RoomOccurrenceExecutionRepository
import com.patjackson.latertext.data.impl.repository.RoomOutboxRepository
import com.patjackson.latertext.data.impl.repository.RoomScheduleRepository
import com.patjackson.latertext.data.impl.repository.RoomAttemptRepository
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomPersistenceTest {
    private lateinit var context: Context
    private lateinit var database: LaterTextDatabase
    private lateinit var attachments: RoomAttachmentRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LaterTextDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        attachments = RoomAttachmentRepository(database, PrivateAttachmentStore(context))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun scheduleRepositoryEmitsRoomInvalidationsForExecutionState() = runBlocking {
        val now = 1_800_000_000_000L
        val scheduleRepository = RoomScheduleRepository(database, attachments)
        val emissions = async(Dispatchers.Default) {
            withTimeout(5_000) { scheduleRepository.observeChanges().take(2).toList() }
        }
        delay(100)

        scheduleRepository.create(textScheduleCommand(now))

        assertEquals(2, emissions.await().size)
    }

    @Test
    fun scheduleGraphReloadsAndOccurrenceClaimIsExclusive() = runBlocking {
        val now = 1_800_000_000_000L
        val scheduleRepository = RoomScheduleRepository(database, attachments)
        val occurrenceRepository = RoomOccurrenceRepository(database)
        val command = textScheduleCommand(now)

        scheduleRepository.create(command)
        val reloaded = scheduleRepository.get(command.schedule.id)

        assertNotNull(reloaded)
        assertEquals("hello later", reloaded?.activeContent?.text)
        assertEquals(command.occurrences.single().jitterOffsetMinutes, reloaded?.occurrences?.single()?.jitterOffsetMinutes)

        val firstClaim = occurrenceRepository.claim("occurrence-1", "worker-a", now, now + 60_000)
        val competingClaim = occurrenceRepository.claim("occurrence-1", "worker-b", now, now + 60_000)

        assertNotNull(firstClaim)
        assertNull(competingClaim)
        assertTrue(
            occurrenceRepository.releaseClaim(
                "occurrence-1", "worker-a", OccurrenceState.PLANNED, now + 1,
            ),
        )
        assertNotNull(occurrenceRepository.claim("occurrence-1", "worker-b", now + 2, now + 60_002))
    }

    @Test
    fun outboxClaimLeaseAndCompletionAreOwnerChecked() = runBlocking {
        val now = 1_800_000_000_000L
        val repository = RoomOutboxRepository(database)
        repository.enqueue(
            listOf(
                SideEffectOutboxRecord(
                    id = "effect-1",
                    aggregateType = "occurrence",
                    aggregateId = "occurrence-1",
                    effectType = "ARM_ALARM",
                    payloadJson = "{}",
                    deduplicationKey = "arm:occurrence-1:1",
                    state = OutboxState.READY,
                    availableAtEpochMillis = now,
                    claimOwner = null,
                    claimUntilEpochMillis = null,
                    attemptCount = 0,
                    lastError = null,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            ),
        )

        val first = repository.claimReady("worker-a", now, now + 60_000, 10)
        val competing = repository.claimReady("worker-b", now, now + 60_000, 10)

        assertEquals(listOf("effect-1"), first.map { it.id })
        assertTrue(competing.isEmpty())
        assertFalse(repository.complete("effect-1", "worker-b", now + 1))
        assertTrue(repository.complete("effect-1", "worker-a", now + 1))
    }

    @Test
    fun alarmGenerationClaimAndOutcomeProjectionAreCompareAndSet() = runBlocking {
        val now = 1_800_000_000_000L
        val scheduleRepository = RoomScheduleRepository(database, attachments)
        val execution = RoomOccurrenceExecutionRepository(database)
        val command = textScheduleCommand(now)
        scheduleRepository.create(command)

        assertEquals("occurrence-1", execution.earliestEligible(now)?.id)
        val selection = execution.selectAlarm("occurrence-1", expectedGeneration = 0, now)
        assertTrue(selection.applied)
        assertEquals(1L, selection.current?.generation)
        assertTrue(
            execution.claimExpected(
                "occurrence-1", generation = 0, "stale-worker", now, now + 60_000,
            ) is ExecutionClaimResult.StaleGeneration,
        )
        val claim = execution.claimExpected(
            "occurrence-1", generation = 1, "worker", now, now + 60_000,
        )
        assertTrue(claim is ExecutionClaimResult.Claimed)
        assertEquals(
            "rule-1",
            (claim as ExecutionClaimResult.Claimed).value.rule.id,
        )
        assertTrue(
            execution.transitionClaimed(
                occurrenceId = "occurrence-1",
                owner = "worker",
                newState = OccurrenceState.SENDING,
                sendOutcome = SendOutcome.PENDING,
                activeAttemptId = "attempt-1",
                nowEpochMillis = now + 1,
            ),
        )
        assertTrue(
            execution.applyAttemptProjection(
                occurrenceId = "occurrence-1",
                expectedAttemptId = "attempt-1",
                newState = OccurrenceState.SENT_TO_CARRIER,
                sendOutcome = SendOutcome.SENT_TO_CARRIER,
                nowEpochMillis = now + 2,
            ),
        )
        assertEquals(
            SendOutcome.SENT_TO_CARRIER,
            RoomOccurrenceRepository(database).get("occurrence-1")?.sendOutcome,
        )
        assertEquals(0, execution.attemptCount("occurrence-1"))
    }

    @Test
    fun staleProviderProjectionCannotRegressANewerCallback() = runBlocking {
        val now = 1_800_000_000_000L
        val schedules = RoomScheduleRepository(database, attachments)
        val executions = RoomOccurrenceExecutionRepository(database)
        val attempts = RoomAttemptRepository(database)
        schedules.create(textScheduleCommand(now))
        val selected = executions.selectAlarm("occurrence-1", expectedGeneration = 0, now)
        assertTrue(
            executions.claimExpected(
                "occurrence-1",
                requireNotNull(selected.current).generation,
                "worker",
                now,
                now + 60_000,
            ) is ExecutionClaimResult.Claimed,
        )
        assertTrue(
            executions.transitionClaimed(
                occurrenceId = "occurrence-1",
                owner = "worker",
                newState = OccurrenceState.SENDING,
                sendOutcome = SendOutcome.PENDING,
                activeAttemptId = "attempt-race",
                nowEpochMillis = now + 1,
            ),
        )
        val initialAttempt = SendAttemptRecord(
            id = "attempt-race",
            occurrenceId = "occurrence-1",
            attemptNumber = 1,
            transportMode = TransportMode.AUTOMATIC_SMS,
            sendOutcome = SendOutcome.PENDING,
            deliveryOutcome = DeliveryOutcome.PENDING,
            failureCode = null,
            failureDetail = null,
            startedAtEpochMillis = now,
            finishedAtEpochMillis = null,
            deliveryDeadlineAtEpochMillis = now + 86_400_000,
        )
        val initialPart = AttemptPartRecord(
            attemptId = initialAttempt.id,
            partIndex = 0,
            totalParts = 1,
            sendOutcome = PartOutcome.PENDING,
            deliveryOutcome = PartOutcome.PENDING,
            sentResultCode = null,
            deliveryResultCode = null,
            sentAtEpochMillis = null,
            deliveredAtEpochMillis = null,
        )
        attempts.create(AttemptBundle(initialAttempt, listOf(initialPart), emptyList()))

        val callbackAttempt = initialAttempt.copy(
            sendOutcome = SendOutcome.SENT_TO_CARRIER,
            deliveryOutcome = DeliveryOutcome.DELIVERED,
            finishedAtEpochMillis = now + 2,
        )
        val callbackPart = initialPart.copy(
            sendOutcome = PartOutcome.ACCEPTED,
            deliveryOutcome = PartOutcome.ACCEPTED,
            sentAtEpochMillis = now + 2,
            deliveredAtEpochMillis = now + 2,
        )
        assertTrue(attempts.updatePart(callbackPart))
        assertTrue(attempts.updateAttempt(callbackAttempt))

        val staleProviderApplied = executions.applyCorroboratedProjection(
            CorroboratedAttemptProjection(
                expectedOccurrenceStates = setOf(OccurrenceState.SENDING),
                expectedAttempt = initialAttempt,
                expectedParts = listOf(initialPart),
                occurrenceState = OccurrenceState.SENT_TO_CARRIER,
                sendOutcome = SendOutcome.SENT_TO_CARRIER,
                deliveryOutcome = DeliveryOutcome.PENDING,
                attempt = initialAttempt.copy(
                    sendOutcome = SendOutcome.SENT_TO_CARRIER,
                    providerMessageId = 42,
                ),
                parts = listOf(initialPart.copy(sendOutcome = PartOutcome.ACCEPTED)),
                event = OccurrenceEventRecord(
                    id = "event-stale-provider",
                    occurrenceId = "occurrence-1",
                    attemptId = initialAttempt.id,
                    type = "SMS_PROVIDER_RECONCILED",
                    detailJson = null,
                    happenedAtEpochMillis = now + 3,
                    createdAtEpochMillis = now + 3,
                ),
            ),
        )

        assertFalse(staleProviderApplied)
        assertEquals(DeliveryOutcome.DELIVERED, attempts.get(initialAttempt.id)?.attempt?.deliveryOutcome)
        assertEquals(PartOutcome.ACCEPTED, attempts.get(initialAttempt.id)?.parts?.single()?.deliveryOutcome)
        assertTrue(database.occurrenceEventDao().listForOccurrence("occurrence-1").isEmpty())
    }

    @Test
    fun stagedAttachmentSurvivesRepositoryRecreationAndPromotes() = runBlocking {
        val id = "test-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        )
        val staged = attachments.stage(
            AttachmentWriteRequest(
                id = id,
                input = AttachmentInput { ByteArrayInputStream(pngHeader) },
                declaredMimeType = "image/png",
                intakeSource = AttachmentIntakeSource.KEYBOARD,
                createdAtEpochMillis = now,
                stagingExpiresAtEpochMillis = now + 86_400_000,
                maxBytes = 1_024,
            ),
        )

        val recreated = RoomAttachmentRepository(database, PrivateAttachmentStore(context))
        assertEquals(pngHeader.size.toLong(), recreated.open(id).use { it.readBytes().size.toLong() })
        val durable = recreated.promoteToDurable(id, now + 1)

        assertEquals(AttachmentStorageClass.STAGING, staged.storageClass)
        assertEquals(AttachmentStorageClass.DURABLE, durable.storageClass)
        assertTrue(recreated.removeIfUnreferenced(id))
    }

    private fun textScheduleCommand(now: Long): CreateScheduleCommand {
        val recipient = RecipientEndpointRecord(
            id = "recipient-1",
            rawAddress = "+1 415 555 0100",
            normalizedAddress = "+14155550100",
            displayName = "Ada",
            source = RecipientSource.MANUAL,
            contactLookupKey = null,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        val schedule = ScheduleRecord(
            id = "schedule-1",
            recipientEndpointId = recipient.id,
            activeContentRevisionId = "content-1",
            activeRuleRevisionId = "rule-1",
            state = ScheduleState.ACTIVE,
            transportMode = TransportMode.AUTOMATIC_SMS,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        val content = ContentRevisionRecord("content-1", schedule.id, 1, "hello later", now)
        val rule = RuleRevisionRecord(
            id = "rule-1",
            scheduleId = schedule.id,
            revisionNumber = 1,
            frequency = RecurrenceFrequency.DAILY,
            interval = 1,
            startEpochDay = 20_000,
            secondsOfDay = 17 * 60 * 60,
            daysOfWeekMask = 0,
            monthlyDayOfMonth = null,
            monthlyEdgePolicy = MonthlyEdgePolicy.LAST_DAY_OF_MONTH,
            zonePolicy = ZonePolicy.FIXED_ZONE,
            zoneId = "America/Los_Angeles",
            endCondition = EndCondition.NEVER,
            endCount = null,
            endEpochDay = null,
            jitterRangeMinutes = 5,
            missedPolicy = MissedPolicy.SEND_AS_SOON_AS_POSSIBLE,
            gracePeriodMinutes = 240,
            createdAtEpochMillis = now,
        )
        val occurrence = OccurrenceRecord(
            id = "occurrence-1",
            scheduleId = schedule.id,
            ruleRevisionId = rule.id,
            contentRevisionId = content.id,
            logicalRecurrenceKey = "schedule-1:rule-1:0",
            nominalEpochDay = 20_000,
            nominalSecondsOfDay = 17 * 60 * 60,
            selectedZoneId = "America/Los_Angeles",
            selectedOffsetSeconds = -28_800,
            dstResolution = DstResolution.EXACT,
            jitterOffsetMinutes = -3,
            targetAtEpochMillis = now - 1,
            deadlineAtEpochMillis = now + 14_400_000,
            state = OccurrenceState.DUE,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        return CreateScheduleCommand(recipient, schedule, content, null, rule, listOf(occurrence))
    }
}
