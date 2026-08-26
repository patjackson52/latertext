package com.patjackson.latertext.platform.android.execution

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Periodic safety net for alarms/callbacks lost because of process or device lifecycle events. */
@HiltWorker
class ExecutionWatchdogWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val recovery: ExecutionRecoveryCoordinator,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = runCatching {
        recovery.onWatchdog()
    }.fold(
        onSuccess = { result ->
            if (result.alarmResult is AlarmCoordinationResult.ArmFailed) Result.retry()
            else Result.success()
        },
        onFailure = { Result.retry() },
    )
}

/** Installs one persistent watchdog; repeated app starts update rather than duplicate the work. */
class ExecutionWatchdogScheduler(
    private val workManager: WorkManager,
    private val repeatInterval: Duration = MINIMUM_INTERVAL,
) {
    init {
        require(repeatInterval >= MINIMUM_INTERVAL) {
            "Periodic watchdog interval must be at least ${MINIMUM_INTERVAL.toMinutes()} minutes"
        }
    }

    fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<ExecutionWatchdogWorker>(
            repeatInterval.toMinutes(),
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(false)
                    .setRequiresStorageNotLow(false)
                    .build(),
            )
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "latertext.execution.watchdog"
        const val WORK_TAG = "latertext.execution"
        val MINIMUM_INTERVAL: Duration = Duration.ofMinutes(15)
    }
}
