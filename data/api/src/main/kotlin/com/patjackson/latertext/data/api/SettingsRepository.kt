package com.patjackson.latertext.data.api

data class UserSettings(
    val notificationsEnabled: Boolean = true,
    val sendResultNotificationsEnabled: Boolean = true,
    val actionRequiredNotificationsEnabled: Boolean = true,
    val deliveryNotificationsEnabled: Boolean = false,
    val globalPaused: Boolean = false,
    val preferredSubscriptionId: Int? = null,
    val defaultZonePolicy: ZonePolicy = ZonePolicy.FOLLOW_DEVICE_ZONE,
    val fixedZoneId: String? = null,
    val defaultMonthlyEdgePolicy: MonthlyEdgePolicy = MonthlyEdgePolicy.LAST_DAY_OF_MONTH,
    val defaultMissedPolicy: MissedPolicy = MissedPolicy.SEND_AS_SOON_AS_POSSIBLE,
    val defaultGracePeriodMinutes: Int = 240,
    val historyRetentionDays: Int = 90,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

interface SettingsRepository {
    suspend fun get(): UserSettings
    suspend fun update(transform: (UserSettings) -> UserSettings): UserSettings
}
