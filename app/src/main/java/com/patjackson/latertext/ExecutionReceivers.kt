package com.patjackson.latertext

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.patjackson.latertext.data.api.AttemptRepository
import com.patjackson.latertext.data.api.CallbackKind
import com.patjackson.latertext.data.api.OccurrenceEventRecord
import com.patjackson.latertext.data.api.OccurrenceRepository
import com.patjackson.latertext.platform.android.AndroidAlarmDriver
import com.patjackson.latertext.platform.android.OccurrenceAlarmReceiver
import com.patjackson.latertext.platform.android.execution.AlarmCoordinator
import com.patjackson.latertext.platform.android.execution.DueOccurrenceProcessor
import com.patjackson.latertext.platform.android.execution.ExecutionRecoveryCoordinator
import com.patjackson.latertext.platform.android.execution.OccurrenceMaterializationCoordinator
import com.patjackson.latertext.platform.android.execution.ReconciliationCause
import com.patjackson.latertext.platform.android.execution.SmsCallbackProcessor
import com.patjackson.latertext.platform.android.execution.DueProcessResult
import com.patjackson.latertext.platform.android.execution.SmsAttemptWorkScheduler
import com.patjackson.latertext.platform.android.execution.SmsCallbackProcessResult
import com.patjackson.latertext.platform.android.execution.SmsProviderReconciler
import com.patjackson.latertext.platform.api.AutomaticSmsGateway
import com.patjackson.latertext.transport.automatic.AndroidAutomaticSmsGateway
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DueOccurrenceDispatchReceiver : BroadcastReceiver() {
    @Inject lateinit var processor: DueOccurrenceProcessor
    @Inject lateinit var alarms: AlarmCoordinator
    @Inject lateinit var materialization: OccurrenceMaterializationCoordinator
    @Inject lateinit var attempts: AttemptRepository
    @Inject lateinit var attemptWorkScheduler: SmsAttemptWorkScheduler
    @Inject lateinit var providerReconciler: SmsProviderReconciler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != OccurrenceAlarmReceiver.ACTION_PROCESS_DUE_OCCURRENCE) return
        val occurrenceId = intent.getStringExtra(AndroidAlarmDriver.EXTRA_OCCURRENCE_ID) ?: return
        val generation = intent.getLongExtra(AndroidAlarmDriver.EXTRA_GENERATION, -1)
        if (generation < 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val result = processor.process(occurrenceId, generation)
                if (result is DueProcessResult.AwaitingSmsCallbacks) {
                    attempts.get(result.attemptId)?.attempt?.let { attempt ->
                        attemptWorkScheduler.onEnqueued(
                            result.attemptId,
                            attempt.startedAtEpochMillis,
                        )
                    }
                    providerReconciler.reconcileAfterEnqueue(result.attemptId)
                }
                materialization.replenishAll()
                alarms.reconcile(ReconciliationCause.OCCURRENCE_CHANGED)
            } finally {
                pending.finish()
            }
        }
    }
}

@AndroidEntryPoint
class SmsCallbackDispatchReceiver : BroadcastReceiver() {
    @Inject lateinit var processor: SmsCallbackProcessor
    @Inject lateinit var attempts: AttemptRepository
    @Inject lateinit var occurrences: OccurrenceRepository
    @Inject lateinit var attemptWorkScheduler: SmsAttemptWorkScheduler
    @Inject lateinit var messagingGateway: AutomaticSmsGateway

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in CALLBACK_ACTIONS) return
        val attemptId = intent.getStringExtra(AndroidAutomaticSmsGateway.EXTRA_ATTEMPT_ID) ?: return
        val partIndex = intent.getIntExtra(AndroidAutomaticSmsGateway.EXTRA_PART_INDEX, -1)
        val kind = when (intent.action) {
            AndroidAutomaticSmsGateway.ACTION_SMS_SENT_RESULT,
            AndroidAutomaticSmsGateway.ACTION_MMS_SENT_RESULT,
            -> CallbackKind.SENT
            AndroidAutomaticSmsGateway.ACTION_SMS_DELIVERY_RESULT -> CallbackKind.DELIVERED
            else -> return
        }
        if (partIndex < 0) return
        val callbackResultCode = resultCode
        val isMms = intent.action == AndroidAutomaticSmsGateway.ACTION_MMS_SENT_RESULT
        Log.i(TAG, "Message callback received attempt=$attemptId part=$partIndex kind=$kind result=$callbackResultCode")
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                attempts.get(attemptId)?.attempt?.let { attempt ->
                    occurrences.appendEvent(
                        OccurrenceEventRecord(
                            id = UUID.randomUUID().toString(),
                            occurrenceId = attempt.occurrenceId,
                            attemptId = attemptId,
                            type = if (isMms) "MMS_CALLBACK_RECEIVED" else "SMS_CALLBACK_RECEIVED",
                            detailJson = "{\"kind\":\"${kind.name}\",\"partIndex\":$partIndex," +
                                "\"resultCode\":$callbackResultCode}",
                            happenedAtEpochMillis = System.currentTimeMillis(),
                            createdAtEpochMillis = System.currentTimeMillis(),
                        ),
                    )
                }
                val processed = processor.process(attemptId, partIndex, kind, callbackResultCode)
                val attempt = attempts.get(attemptId)?.attempt
                when (processed) {
                    is SmsCallbackProcessResult.CarrierAccepted ->
                        attemptWorkScheduler.onCarrierAccepted(
                            attemptId,
                            attempt?.deliveryDeadlineAtEpochMillis,
                        )
                    is SmsCallbackProcessResult.DeliveryCompleted,
                    is SmsCallbackProcessResult.RetryScheduled,
                    is SmsCallbackProcessResult.Terminal,
                    -> attemptWorkScheduler.onTerminal(attemptId)
                    else -> Unit
                }
                Log.i(TAG, "Message callback processed attempt=$attemptId outcome=$processed")
            } catch (error: Throwable) {
                Log.e(TAG, "Message callback processing failed attempt=$attemptId", error)
            } finally {
                if (isMms) messagingGateway.cleanupMmsPayload(attemptId)
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "LaterTextSmsCallback"
        private val CALLBACK_ACTIONS = setOf(
            AndroidAutomaticSmsGateway.ACTION_SMS_SENT_RESULT,
            AndroidAutomaticSmsGateway.ACTION_SMS_DELIVERY_RESULT,
            AndroidAutomaticSmsGateway.ACTION_MMS_SENT_RESULT,
        )
    }
}

@AndroidEntryPoint
class ScheduleReconciliationReceiver : BroadcastReceiver() {
    @Inject lateinit var recovery: ExecutionRecoveryCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        val cause = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> ReconciliationCause.BOOT_COMPLETED
            Intent.ACTION_MY_PACKAGE_REPLACED -> ReconciliationCause.PACKAGE_REPLACED
            Intent.ACTION_TIME_CHANGED -> ReconciliationCause.WALL_CLOCK_CHANGED
            Intent.ACTION_TIMEZONE_CHANGED -> ReconciliationCause.TIME_ZONE_CHANGED
            android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                ReconciliationCause.EXACT_ALARM_ACCESS_CHANGED
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                recovery.reconcile(cause)
            } finally {
                pending.finish()
            }
        }
    }
}
