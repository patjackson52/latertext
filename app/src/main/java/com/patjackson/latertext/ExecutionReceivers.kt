package com.patjackson.latertext

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.patjackson.latertext.data.api.CallbackKind
import com.patjackson.latertext.platform.android.AndroidAlarmDriver
import com.patjackson.latertext.platform.android.OccurrenceAlarmReceiver
import com.patjackson.latertext.platform.android.execution.AlarmCoordinator
import com.patjackson.latertext.platform.android.execution.DueOccurrenceProcessor
import com.patjackson.latertext.platform.android.execution.ExecutionRecoveryCoordinator
import com.patjackson.latertext.platform.android.execution.OccurrenceMaterializationCoordinator
import com.patjackson.latertext.platform.android.execution.ReconciliationCause
import com.patjackson.latertext.platform.android.execution.SmsCallbackProcessor
import com.patjackson.latertext.transport.automatic.AndroidAutomaticSmsGateway
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DueOccurrenceDispatchReceiver : BroadcastReceiver() {
    @Inject lateinit var processor: DueOccurrenceProcessor
    @Inject lateinit var alarms: AlarmCoordinator
    @Inject lateinit var materialization: OccurrenceMaterializationCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != OccurrenceAlarmReceiver.ACTION_PROCESS_DUE_OCCURRENCE) return
        val occurrenceId = intent.getStringExtra(AndroidAlarmDriver.EXTRA_OCCURRENCE_ID) ?: return
        val generation = intent.getLongExtra(AndroidAlarmDriver.EXTRA_GENERATION, -1)
        if (generation < 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                processor.process(occurrenceId, generation)
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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidAutomaticSmsGateway.ACTION_PROCESS_SMS_CALLBACK) return
        val attemptId = intent.getStringExtra(AndroidAutomaticSmsGateway.EXTRA_ATTEMPT_ID) ?: return
        val partIndex = intent.getIntExtra(AndroidAutomaticSmsGateway.EXTRA_PART_INDEX, -1)
        val kind = intent.getStringExtra(AndroidAutomaticSmsGateway.EXTRA_CALLBACK_KIND)
            ?.let { runCatching { CallbackKind.valueOf(it) }.getOrNull() }
            ?: return
        if (partIndex < 0) return
        val resultCode = intent.getIntExtra(AndroidAutomaticSmsGateway.EXTRA_RESULT_CODE, Int.MIN_VALUE)
        if (resultCode == Int.MIN_VALUE) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                processor.process(attemptId, partIndex, kind, resultCode)
            } finally {
                pending.finish()
            }
        }
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
