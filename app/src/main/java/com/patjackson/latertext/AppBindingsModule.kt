package com.patjackson.latertext

import android.content.Context
import com.patjackson.latertext.platform.android.AndroidAlarmDriver
import com.patjackson.latertext.platform.android.AndroidIncomingContentImporter
import com.patjackson.latertext.platform.android.AndroidNotificationPublisher
import com.patjackson.latertext.platform.android.AndroidReadinessGateway
import com.patjackson.latertext.platform.android.SecureRandomIntSource
import com.patjackson.latertext.platform.android.SystemAppClock
import com.patjackson.latertext.platform.android.SystemDeviceZoneProvider
import com.patjackson.latertext.platform.android.execution.AlarmCoordinator
import com.patjackson.latertext.platform.android.execution.DueOccurrenceProcessor
import com.patjackson.latertext.platform.android.execution.ExecutionRecoveryCoordinator
import com.patjackson.latertext.platform.android.execution.ExecutionWatchdogScheduler
import com.patjackson.latertext.platform.android.execution.OccurrenceMaterializationCoordinator
import com.patjackson.latertext.platform.android.execution.SmsCallbackProcessor
import com.patjackson.latertext.platform.android.sharing.RecipientShareShortcutPublisher
import com.patjackson.latertext.data.api.AttemptRepository
import com.patjackson.latertext.data.api.OccurrenceExecutionRepository
import com.patjackson.latertext.data.api.OccurrenceRepository
import com.patjackson.latertext.data.api.ScheduleRepository
import com.patjackson.latertext.data.api.SettingsRepository
import com.patjackson.latertext.platform.api.AlarmDriver
import com.patjackson.latertext.platform.api.AppClock
import com.patjackson.latertext.platform.api.AssistedMessagingGateway
import com.patjackson.latertext.platform.api.AutomaticSmsGateway
import com.patjackson.latertext.platform.api.DeviceZoneProvider
import com.patjackson.latertext.platform.api.IncomingContentImporter
import com.patjackson.latertext.platform.api.NotificationPublisher
import com.patjackson.latertext.platform.api.RandomIntSource
import com.patjackson.latertext.platform.api.ReadinessGateway
import com.patjackson.latertext.platform.api.SubscriptionGateway
import com.patjackson.latertext.transport.assisted.AndroidAssistedMessagingGateway
import com.patjackson.latertext.transport.automatic.AndroidAutomaticSmsGateway
import com.patjackson.latertext.transport.automatic.AndroidSubscriptionGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import androidx.work.WorkManager

@Module
@InstallIn(SingletonComponent::class)
object AppBindingsModule {
    @Provides
    @Singleton
    fun alarmDriver(@ApplicationContext context: Context): AlarmDriver = AndroidAlarmDriver(context)

    @Provides
    @Singleton
    fun alarmCoordinator(
        schedules: ScheduleRepository,
        executions: OccurrenceExecutionRepository,
        alarms: AlarmDriver,
        clock: AppClock,
    ): AlarmCoordinator = AlarmCoordinator(schedules, executions, alarms, clock)

    @Provides
    fun dueOccurrenceProcessor(
        occurrences: OccurrenceRepository,
        executions: OccurrenceExecutionRepository,
        attempts: AttemptRepository,
        settings: SettingsRepository,
        readiness: ReadinessGateway,
        subscriptions: SubscriptionGateway,
        automaticSms: AutomaticSmsGateway,
        notifications: NotificationPublisher,
        clock: AppClock,
    ): DueOccurrenceProcessor = DueOccurrenceProcessor(
        occurrences,
        executions,
        attempts,
        settings,
        readiness,
        subscriptions,
        automaticSms,
        notifications,
        clock,
    )

    @Provides
    fun smsCallbackProcessor(
        attempts: AttemptRepository,
        occurrences: OccurrenceRepository,
        executions: OccurrenceExecutionRepository,
        settings: SettingsRepository,
        notifications: NotificationPublisher,
        alarmCoordinator: AlarmCoordinator,
        clock: AppClock,
    ): SmsCallbackProcessor = SmsCallbackProcessor(
        attempts,
        occurrences,
        executions,
        settings,
        notifications,
        alarmCoordinator,
        clock,
    )

    @Provides
    @Singleton
    fun executionRecoveryCoordinator(
        schedules: ScheduleRepository,
        occurrences: OccurrenceRepository,
        dueProcessor: DueOccurrenceProcessor,
        callbackProcessor: SmsCallbackProcessor,
        alarmCoordinator: AlarmCoordinator,
        settings: SettingsRepository,
        notifications: NotificationPublisher,
        clock: AppClock,
        materialization: OccurrenceMaterializationCoordinator,
    ): ExecutionRecoveryCoordinator = ExecutionRecoveryCoordinator(
        schedules,
        occurrences,
        dueProcessor,
        callbackProcessor,
        alarmCoordinator,
        settings,
        notifications,
        clock,
        materialization,
    )

    @Provides
    @Singleton
    fun occurrenceMaterializationCoordinator(
        schedules: ScheduleRepository,
        occurrences: OccurrenceRepository,
        clock: AppClock,
        deviceZone: DeviceZoneProvider,
    ): OccurrenceMaterializationCoordinator = OccurrenceMaterializationCoordinator(
        schedules,
        occurrences,
        clock,
        deviceZone,
    )

    @Provides
    @Singleton
    fun executionWatchdogScheduler(
        @ApplicationContext context: Context,
    ): ExecutionWatchdogScheduler = ExecutionWatchdogScheduler(WorkManager.getInstance(context))

    @Provides
    @Singleton
    fun recipientShareShortcutPublisher(
        @ApplicationContext context: Context,
    ): RecipientShareShortcutPublisher = RecipientShareShortcutPublisher(
        context,
        MainActivity::class.java,
        RecipientShareShortcutPublisher.DEFAULT_SHARE_TARGET_CATEGORY,
    )

    @Provides
    @Singleton
    fun smsGateway(@ApplicationContext context: Context): AutomaticSmsGateway =
        AndroidAutomaticSmsGateway(context)

    @Provides
    @Singleton
    fun subscriptionGateway(@ApplicationContext context: Context): SubscriptionGateway =
        AndroidSubscriptionGateway(context)

    @Provides
    @Singleton
    fun assistedGateway(@ApplicationContext context: Context): AssistedMessagingGateway =
        AndroidAssistedMessagingGateway(context)

    @Provides
    @Singleton
    fun readinessGateway(@ApplicationContext context: Context): ReadinessGateway =
        AndroidReadinessGateway(context)

    @Provides
    @Singleton
    fun notificationPublisher(@ApplicationContext context: Context): NotificationPublisher =
        AndroidNotificationPublisher(context)

    @Provides
    @Singleton
    fun contentImporter(@ApplicationContext context: Context): IncomingContentImporter =
        AndroidIncomingContentImporter(context)

    @Provides fun clock(): AppClock = SystemAppClock()
    @Provides fun zoneProvider(): DeviceZoneProvider = SystemDeviceZoneProvider()
    @Provides fun randomSource(): RandomIntSource = SecureRandomIntSource()
}
