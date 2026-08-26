package com.patjackson.latertext.platform.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.patjackson.latertext.platform.api.AppNotification
import com.patjackson.latertext.platform.api.NotificationKind
import com.patjackson.latertext.platform.api.NotificationPublisher

class AndroidNotificationPublisher(
    private val context: Context,
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java),
) : NotificationPublisher {
    override fun ensureChannels() {
        val channels = listOf(
            NotificationChannel(
                CHANNEL_RESULTS,
                "Send results",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Successful and unsuccessful automatic SMS results" },
            NotificationChannel(
                CHANNEL_ACTION_REQUIRED,
                "Action required",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Messages that need you to finish sending" },
            NotificationChannel(
                CHANNEL_STATUS,
                "Schedule status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Timing and schedule readiness changes" },
        )
        manager.createNotificationChannels(channels)
    }

    override fun publish(notification: AppNotification) {
        ensureChannels()
        val channel = when (notification.kind) {
            NotificationKind.SEND_SUCCEEDED,
            NotificationKind.SEND_FAILED,
            -> CHANNEL_RESULTS
            NotificationKind.ACTION_REQUIRED -> CHANNEL_ACTION_REQUIRED
            NotificationKind.TIMING_DEGRADED -> CHANNEL_STATUS
        }
        val icon = if (notification.kind == NotificationKind.SEND_FAILED) {
            android.R.drawable.stat_notify_error
        } else {
            android.R.drawable.stat_notify_chat
        }
        val contentIntent = notification.occurrenceId?.let { occurrenceId ->
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launchIntent ->
                launchIntent
                    .setAction(ACTION_OPEN_OCCURRENCE)
                    .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                PendingIntent.getActivity(
                    context,
                    notification.id,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        }
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(icon)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setAutoCancel(true)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        manager.notify(
            notification.id,
            builder.build(),
        )
    }

    override fun cancel(id: Int) = manager.cancel(id)

    companion object {
        const val ACTION_OPEN_OCCURRENCE = "com.patjackson.latertext.action.OPEN_OCCURRENCE"
        const val EXTRA_OCCURRENCE_ID = "latertext_occurrence_id"
        const val CHANNEL_RESULTS = "send_results"
        const val CHANNEL_ACTION_REQUIRED = "action_required"
        const val CHANNEL_STATUS = "schedule_status"
    }
}
