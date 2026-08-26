package com.patjackson.latertext.platform.android.execution

import android.util.Log
import com.patjackson.latertext.data.api.OccurrenceExecutionRepository
import com.patjackson.latertext.data.api.OccurrenceRecord
import com.patjackson.latertext.data.api.OccurrenceState
import com.patjackson.latertext.data.api.ScheduleGraph
import com.patjackson.latertext.data.api.ScheduleRepository
import com.patjackson.latertext.data.api.ScheduleState
import com.patjackson.latertext.platform.api.AlarmDriver
import com.patjackson.latertext.platform.api.AlarmPrecision
import com.patjackson.latertext.platform.api.AlarmRequest
import com.patjackson.latertext.platform.api.AppClock
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ReconciliationCause {
    APP_START,
    OCCURRENCE_CHANGED,
    CALLBACK_APPLIED,
    BOOT_COMPLETED,
    PACKAGE_REPLACED,
    WALL_CLOCK_CHANGED,
    TIME_ZONE_CHANGED,
    EXACT_ALARM_ACCESS_CHANGED,
    WATCHDOG,
}

sealed interface AlarmCoordinationResult {
    data object NothingEligible : AlarmCoordinationResult

    data class Armed(
        val occurrenceId: String,
        val generation: Long,
        val triggerAt: Instant,
        val precision: AlarmPrecision,
        /** True when a concurrent database mutation changed the initially computed winner. */
        val databaseWinnerChanged: Boolean,
    ) : AlarmCoordinationResult

    data class ArmFailed(
        val occurrenceId: String,
        val generation: Long,
        val error: Throwable,
    ) : AlarmCoordinationResult
}

/**
 * Maintains one process-wide AlarmManager alarm for the earliest durable occurrence.
 * The repository performs the generation increment and winner selection atomically;
 * this coordinator owns only the external AlarmManager side effect.
 */
class AlarmCoordinator(
    private val schedules: ScheduleRepository,
    private val executions: OccurrenceExecutionRepository,
    private val alarms: AlarmDriver,
    private val clock: AppClock,
    private val queryLimit: Int = 500,
) {
    private val mutex = Mutex()

    init {
        require(queryLimit > 0)
    }

    suspend fun reconcile(
        cause: ReconciliationCause = ReconciliationCause.OCCURRENCE_CHANGED,
    ): AlarmCoordinationResult = mutex.withLock {
        // Cause is intentionally part of the public call contract for diagnostics,
        // even though all causes currently use the same idempotent reconciliation.
        @Suppress("UNUSED_VARIABLE") val reconciliationCause = cause
        val now = clock.now()
        val graphs = schedules.listUpcoming(queryLimit)
        val computed = AlarmCandidateSelector.earliest(graphs, now)
        Log.d(TAG, "reconcile cause=$cause schedules=${graphs.size} candidate=${computed?.id}")
        // The selection CAS repeats eligibility validation inside its Room
        // transaction, increments the generation, and disarms the prior row.
        val selection = executions.selectAlarm(
            candidateId = computed?.id,
            expectedGeneration = computed?.alarmGeneration,
            nowEpochMillis = now.toEpochMilli(),
        )
        Log.d(
            TAG,
            "selection applied=${selection.applied} previous=${selection.previous?.occurrenceId} " +
                "current=${selection.current?.occurrenceId}",
        )
        selection.previous?.let { prior -> alarms.cancel(prior.occurrenceId, prior.generation) }
        val current = selection.current ?: return@withLock AlarmCoordinationResult.NothingEligible

        val triggerAt = Instant.ofEpochMilli(maxOf(current.targetAtEpochMillis, now.toEpochMilli()))
        val precision = if (alarms.canScheduleExactAlarms()) {
            AlarmPrecision.EXACT
        } else {
            AlarmPrecision.INEXACT
        }
        val request = AlarmRequest(
            occurrenceId = current.occurrenceId,
            generation = current.generation,
            triggerAt = triggerAt,
            precision = precision,
        )
        try {
            alarms.arm(request)
            AlarmCoordinationResult.Armed(
                occurrenceId = current.occurrenceId,
                generation = current.generation,
                triggerAt = triggerAt,
                precision = precision,
                databaseWinnerChanged = !selection.applied || current.occurrenceId != computed?.id,
            )
        } catch (error: Throwable) {
            AlarmCoordinationResult.ArmFailed(current.occurrenceId, current.generation, error)
        }
    }

    private companion object {
        const val TAG = "LaterTextAlarm"
    }
}

internal object AlarmCandidateSelector {
    private val eligibleStates = setOf(
        OccurrenceState.PLANNED,
        OccurrenceState.ARMED,
        OccurrenceState.DUE,
        OccurrenceState.RETRY_WAIT,
    )

    fun earliest(graphs: List<ScheduleGraph>, now: Instant): OccurrenceRecord? {
        val nowMillis = now.toEpochMilli()
        return graphs.asSequence()
            .filter { it.schedule.state == ScheduleState.ACTIVE && it.schedule.deletedAtEpochMillis == null }
            .flatMap { it.occurrences.asSequence() }
            .filter { it.state in eligibleStates && it.deadlineAtEpochMillis >= nowMillis }
            .minWithOrNull(
                compareBy<OccurrenceRecord> { it.retryAtEpochMillis ?: it.targetAtEpochMillis }
                    .thenBy { it.id },
            )
    }
}
