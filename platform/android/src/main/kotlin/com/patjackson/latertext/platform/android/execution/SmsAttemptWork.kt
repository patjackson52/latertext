package com.patjackson.latertext.platform.android.execution

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

enum class SmsAttemptWorkKind {
    PROVIDER_RECONCILE_EARLY,
    PROVIDER_RECONCILE,
    SENT_CALLBACK_TIMEOUT,
    DELIVERY_TIMEOUT,
}

/**
 * Durable, attempt-specific recovery. Provider reconciliation always runs before a timeout so a
 * platform-persisted sent message wins over an otherwise ambiguous missing callback.
 */
@HiltWorker
class SmsAttemptWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val providerReconciler: SmsProviderReconciler,
    private val callbackProcessor: SmsCallbackProcessor,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = runCatching {
        val attemptId = inputData.getString(KEY_ATTEMPT_ID)
            ?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val kind = inputData.getString(KEY_KIND)
            ?.let { runCatching { SmsAttemptWorkKind.valueOf(it) }.getOrNull() }
            ?: return Result.failure()

        providerReconciler.reconcileAttempt(attemptId)
        when (kind) {
            SmsAttemptWorkKind.PROVIDER_RECONCILE_EARLY,
            SmsAttemptWorkKind.PROVIDER_RECONCILE -> Result.success()
            SmsAttemptWorkKind.SENT_CALLBACK_TIMEOUT ->
                callbackProcessor.processSentCallbackTimeout(attemptId).toWorkerResult()
            SmsAttemptWorkKind.DELIVERY_TIMEOUT ->
                callbackProcessor.processDeliveryTimeout(attemptId).toWorkerResult()
        }
    }.getOrElse { Result.retry() }

    private fun SmsCallbackProcessResult.toWorkerResult(): Result = when (this) {
        is SmsCallbackProcessResult.Ignored -> if (reason.endsWith("_not_due")) {
            Result.retry()
        } else {
            Result.success()
        }
        else -> Result.success()
    }

    companion object {
        const val KEY_ATTEMPT_ID = "attempt_id"
        const val KEY_KIND = "kind"
    }
}

/** Schedules exact logical deadlines with one-time work; the periodic watchdog remains a fallback. */
class SmsAttemptWorkScheduler(
    private val workManager: WorkManager,
    private val now: () -> Instant = Instant::now,
    private val earlyProviderDelay: Duration = Duration.ofSeconds(2),
    private val providerDelay: Duration = Duration.ofSeconds(30),
    private val sentCallbackTimeout: Duration = Duration.ofMinutes(15),
) {
    init {
        require(!earlyProviderDelay.isNegative)
        require(!providerDelay.isNegative)
        require(!sentCallbackTimeout.isNegative && !sentCallbackTimeout.isZero)
    }

    fun onEnqueued(attemptId: String, startedAtEpochMillis: Long) {
        enqueue(
            attemptId,
            SmsAttemptWorkKind.PROVIDER_RECONCILE_EARLY,
            now().plus(earlyProviderDelay),
        )
        enqueue(
            attemptId,
            SmsAttemptWorkKind.PROVIDER_RECONCILE,
            now().plus(providerDelay),
        )
        enqueue(
            attemptId,
            SmsAttemptWorkKind.SENT_CALLBACK_TIMEOUT,
            Instant.ofEpochMilli(startedAtEpochMillis).plus(sentCallbackTimeout),
        )
    }

    fun onCarrierAccepted(attemptId: String, deliveryDeadlineAtEpochMillis: Long?) {
        workManager.cancelUniqueWork(workName(attemptId, SmsAttemptWorkKind.PROVIDER_RECONCILE_EARLY))
        workManager.cancelUniqueWork(workName(attemptId, SmsAttemptWorkKind.PROVIDER_RECONCILE))
        workManager.cancelUniqueWork(workName(attemptId, SmsAttemptWorkKind.SENT_CALLBACK_TIMEOUT))
        deliveryDeadlineAtEpochMillis?.let {
            enqueue(attemptId, SmsAttemptWorkKind.DELIVERY_TIMEOUT, Instant.ofEpochMilli(it))
        }
    }

    fun onTerminal(attemptId: String) {
        SmsAttemptWorkKind.entries.forEach { workManager.cancelUniqueWork(workName(attemptId, it)) }
    }

    fun repair(
        attemptId: String,
        startedAtEpochMillis: Long,
        deliveryDeadlineAtEpochMillis: Long?,
        state: com.patjackson.latertext.data.api.OccurrenceState,
    ) {
        when (state) {
            com.patjackson.latertext.data.api.OccurrenceState.SENDING ->
                onEnqueued(attemptId, startedAtEpochMillis)
            com.patjackson.latertext.data.api.OccurrenceState.SENT_TO_CARRIER ->
                onCarrierAccepted(attemptId, deliveryDeadlineAtEpochMillis)
            else -> Unit
        }
    }

    private fun enqueue(attemptId: String, kind: SmsAttemptWorkKind, runAt: Instant) {
        require(attemptId.isNotBlank())
        val delayMillis = Duration.between(now(), runAt).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<SmsAttemptWorker>()
            .setInputData(
                Data.Builder()
                    .putString(SmsAttemptWorker.KEY_ATTEMPT_ID, attemptId)
                    .putString(SmsAttemptWorker.KEY_KIND, kind.name)
                    .build(),
            )
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .addTag(WORK_TAG)
            .addTag("$WORK_TAG.$attemptId")
            .build()
        workManager.enqueueUniqueWork(
            workName(attemptId, kind),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val WORK_TAG = "latertext.sms.attempt"
        fun workName(attemptId: String, kind: SmsAttemptWorkKind): String =
            "latertext.sms.${kind.name.lowercase()}.$attemptId"
    }
}
