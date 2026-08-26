package com.patjackson.latertext.platform.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Converts an AlarmManager callback into an app-scoped broadcast. The app
 * composition root installs the durable occurrence processor as a receiver for
 * [ACTION_PROCESS_DUE_OCCURRENCE]. Keeping this receiver tiny makes duplicate
 * alarm delivery safe to reconcile against Room before any transport call.
 */
class OccurrenceAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val occurrenceId = intent.getStringExtra(AndroidAlarmDriver.EXTRA_OCCURRENCE_ID) ?: return
        val generation = intent.getLongExtra(AndroidAlarmDriver.EXTRA_GENERATION, -1L)
        if (generation < 0L) return

        context.sendBroadcast(
            Intent(ACTION_PROCESS_DUE_OCCURRENCE).apply {
                `package` = context.packageName
                putExtra(AndroidAlarmDriver.EXTRA_OCCURRENCE_ID, occurrenceId)
                putExtra(AndroidAlarmDriver.EXTRA_GENERATION, generation)
            },
        )
    }

    companion object {
        const val ACTION_PROCESS_DUE_OCCURRENCE =
            "com.patjackson.latertext.action.PROCESS_DUE_OCCURRENCE"
    }
}
