package com.patjackson.latertext.platform.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.patjackson.latertext.platform.api.AppReadiness
import com.patjackson.latertext.platform.api.ReadinessGateway

class AndroidReadinessGateway(
    private val context: Context,
) : ReadinessGateway {
    @SuppressLint("MissingPermission")
    override fun snapshot(): AppReadiness {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val phonePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED

        return AppReadiness(
            canSendSms = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.SEND_SMS,
            ) == PackageManager.PERMISSION_GRANTED,
            canPostNotifications = notifications.areNotificationsEnabled(),
            canScheduleExactAlarms = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms(),
            hasActiveSmsSubscription = phonePermission &&
                runCatching { subscriptionManager.activeSubscriptionInfoCount > 0 }.getOrDefault(false),
            canReadSmsHistory = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_SMS,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
}
