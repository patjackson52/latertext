package com.patjackson.latertext.platform.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.patjackson.latertext.platform.api.AlarmDriver
import com.patjackson.latertext.platform.api.AlarmPrecision
import com.patjackson.latertext.platform.api.AlarmRequest

class AndroidAlarmDriver(
    private val context: Context,
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java),
) : AlarmDriver {
    override fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    override fun arm(request: AlarmRequest) {
        val operation = operation(request.occurrenceId, request.generation)
        val atMillis = request.triggerAt.toEpochMilli()
        if (request.precision == AlarmPrecision.EXACT && canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
        }
    }

    override fun cancel(occurrenceId: String, generation: Long) {
        alarmManager.cancel(operation(occurrenceId, generation))
    }

    private fun operation(occurrenceId: String, generation: Long): PendingIntent {
        val intent = Intent(context, OccurrenceAlarmReceiver::class.java).apply {
            action = ACTION_OCCURRENCE_DUE
            data = android.net.Uri.parse("latertext://occurrence/$occurrenceId/$generation")
            putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
            putExtra(EXTRA_GENERATION, generation)
        }
        return PendingIntent.getBroadcast(
            context,
            stableRequestCode(occurrenceId, generation),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stableRequestCode(occurrenceId: String, generation: Long): Int =
        31 * occurrenceId.hashCode() + generation.hashCode()

    companion object {
        const val ACTION_OCCURRENCE_DUE = "com.patjackson.latertext.action.OCCURRENCE_DUE"
        const val EXTRA_OCCURRENCE_ID = "occurrence_id"
        const val EXTRA_GENERATION = "generation"
    }
}
