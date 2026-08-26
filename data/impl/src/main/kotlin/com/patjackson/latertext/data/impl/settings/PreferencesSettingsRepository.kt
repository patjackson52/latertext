package com.patjackson.latertext.data.impl.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.patjackson.latertext.data.api.MissedPolicy
import com.patjackson.latertext.data.api.MonthlyEdgePolicy
import com.patjackson.latertext.data.api.SettingsRepository
import com.patjackson.latertext.data.api.ThemeMode
import com.patjackson.latertext.data.api.UserSettings
import com.patjackson.latertext.data.api.ZonePolicy
import kotlinx.coroutines.flow.first

class PreferencesSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    override suspend fun get(): UserSettings = dataStore.data.first().toSettings()

    override suspend fun update(transform: (UserSettings) -> UserSettings): UserSettings {
        lateinit var result: UserSettings
        dataStore.edit { preferences ->
            result = transform(preferences.toSettings()).validated()
            preferences.write(result)
        }
        return result
    }

    companion object {
        const val FILE_NAME = "latertext_settings.preferences_pb"

        fun create(context: Context): PreferencesSettingsRepository =
            PreferencesSettingsRepository(
                PreferenceDataStoreFactory.create(
                    produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
                ),
            )
    }
}

private object SettingsKeys {
    val notificationsEnabled = booleanPreferencesKey("notifications_enabled")
    val sendResultNotificationsEnabled = booleanPreferencesKey("send_result_notifications_enabled")
    val actionRequiredNotificationsEnabled = booleanPreferencesKey("action_required_notifications_enabled")
    val deliveryNotificationsEnabled = booleanPreferencesKey("delivery_notifications_enabled")
    val globalPaused = booleanPreferencesKey("global_paused")
    val preferredSubscriptionId = intPreferencesKey("preferred_subscription_id")
    val defaultZonePolicy = stringPreferencesKey("default_zone_policy")
    val fixedZoneId = stringPreferencesKey("fixed_zone_id")
    val defaultMonthlyEdgePolicy = stringPreferencesKey("default_monthly_edge_policy")
    val defaultMissedPolicy = stringPreferencesKey("default_missed_policy")
    val defaultGracePeriodMinutes = intPreferencesKey("default_grace_period_minutes")
    val historyRetentionDays = intPreferencesKey("history_retention_days")
    val themeMode = stringPreferencesKey("theme_mode")
}

private fun Preferences.toSettings() = UserSettings(
    notificationsEnabled = this[SettingsKeys.notificationsEnabled] ?: true,
    sendResultNotificationsEnabled = this[SettingsKeys.sendResultNotificationsEnabled] ?: true,
    actionRequiredNotificationsEnabled = this[SettingsKeys.actionRequiredNotificationsEnabled] ?: true,
    deliveryNotificationsEnabled = this[SettingsKeys.deliveryNotificationsEnabled] ?: false,
    globalPaused = this[SettingsKeys.globalPaused] ?: false,
    preferredSubscriptionId = this[SettingsKeys.preferredSubscriptionId],
    defaultZonePolicy = enumOrDefault(this[SettingsKeys.defaultZonePolicy], ZonePolicy.FOLLOW_DEVICE_ZONE),
    fixedZoneId = this[SettingsKeys.fixedZoneId],
    defaultMonthlyEdgePolicy = enumOrDefault(
        this[SettingsKeys.defaultMonthlyEdgePolicy],
        MonthlyEdgePolicy.LAST_DAY_OF_MONTH,
    ),
    defaultMissedPolicy = enumOrDefault(
        this[SettingsKeys.defaultMissedPolicy],
        MissedPolicy.SEND_AS_SOON_AS_POSSIBLE,
    ),
    defaultGracePeriodMinutes = this[SettingsKeys.defaultGracePeriodMinutes] ?: 240,
    historyRetentionDays = this[SettingsKeys.historyRetentionDays] ?: 90,
    themeMode = enumOrDefault(this[SettingsKeys.themeMode], ThemeMode.SYSTEM),
).validated()

private fun MutablePreferences.write(settings: UserSettings) {
    this[SettingsKeys.notificationsEnabled] = settings.notificationsEnabled
    this[SettingsKeys.sendResultNotificationsEnabled] = settings.sendResultNotificationsEnabled
    this[SettingsKeys.actionRequiredNotificationsEnabled] = settings.actionRequiredNotificationsEnabled
    this[SettingsKeys.deliveryNotificationsEnabled] = settings.deliveryNotificationsEnabled
    this[SettingsKeys.globalPaused] = settings.globalPaused
    settings.preferredSubscriptionId?.let { this[SettingsKeys.preferredSubscriptionId] = it }
        ?: remove(SettingsKeys.preferredSubscriptionId)
    this[SettingsKeys.defaultZonePolicy] = settings.defaultZonePolicy.name
    settings.fixedZoneId?.let { this[SettingsKeys.fixedZoneId] = it }
        ?: remove(SettingsKeys.fixedZoneId)
    this[SettingsKeys.defaultMonthlyEdgePolicy] = settings.defaultMonthlyEdgePolicy.name
    this[SettingsKeys.defaultMissedPolicy] = settings.defaultMissedPolicy.name
    this[SettingsKeys.defaultGracePeriodMinutes] = settings.defaultGracePeriodMinutes
    this[SettingsKeys.historyRetentionDays] = settings.historyRetentionDays
    this[SettingsKeys.themeMode] = settings.themeMode.name
}

private fun UserSettings.validated(): UserSettings = apply {
    require(defaultGracePeriodMinutes in 0..10_080)
    require(historyRetentionDays in 1..3_650)
    require(defaultZonePolicy != ZonePolicy.FIXED_ZONE || !fixedZoneId.isNullOrBlank()) {
        "A fixed zone policy requires a zone ID"
    }
}

private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T =
    raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
