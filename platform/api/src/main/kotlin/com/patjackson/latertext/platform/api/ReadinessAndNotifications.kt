package com.patjackson.latertext.platform.api

data class AppReadiness(
    val canSendSms: Boolean,
    val canPostNotifications: Boolean,
    val canScheduleExactAlarms: Boolean,
    val hasActiveSmsSubscription: Boolean,
    val canReadSmsHistory: Boolean = false,
)

interface ReadinessGateway {
    fun snapshot(): AppReadiness
}

enum class NotificationKind {
    SEND_SUCCEEDED,
    SEND_FAILED,
    ACTION_REQUIRED,
    TIMING_DEGRADED,
}

data class AppNotification(
    val id: Int,
    val kind: NotificationKind,
    val title: String,
    val body: String,
    val occurrenceId: String? = null,
)

interface NotificationPublisher {
    fun ensureChannels()
    fun publish(notification: AppNotification)
    fun cancel(id: Int)
}
