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
import com.patjackson.latertext.platform.android.execution.AndroidSmsProviderReader
import com.patjackson.latertext.platform.android.execution.SmsAttemptWorkScheduler
import com.patjackson.latertext.platform.android.execution.SmsProviderChangeObserver
import com.patjackson.latertext.platform.android.execution.SmsProviderReader
import com.patjackson.latertext.platform.android.execution.SmsProviderReconciler
import com.patjackson.latertext.platform.android.sharing.RecipientShareShortcutPublisher
import com.patjackson.latertext.data.api.AttemptRepository
import com.patjackson.latertext.data.api.AttachmentRepository
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
        attachments: AttachmentRepository,
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
        attachments,
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
        attempts: AttemptRepository,
        dueProcessor: DueOccurrenceProcessor,
        callbackProcessor: SmsCallbackProcessor,
        alarmCoordinator: AlarmCoordinator,
        settings: SettingsRepository,
        notifications: NotificationPublisher,
        clock: AppClock,
        materialization: OccurrenceMaterializationCoordinator,
        providerReconciler: SmsProviderReconciler,
        attemptWorkScheduler: SmsAttemptWorkScheduler,
    ): ExecutionRecoveryCoordinator = ExecutionRecoveryCoordinator(
        schedules,
        occurrences,
        attempts,
        dueProcessor,
        callbackProcessor,
        alarmCoordinator,
        settings,
        notifications,
        clock,
        materialization,
        providerReconciler,
        attemptWorkScheduler,
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
    fun smsAttemptWorkScheduler(
        @ApplicationContext context: Context,
    ): SmsAttemptWorkScheduler = SmsAttemptWorkScheduler(WorkManager.getInstance(context))

    @Provides
    @Singleton
    fun smsProviderReader(
        @ApplicationContext context: Context,
    ): SmsProviderReader = AndroidSmsProviderReader(context)

    @Provides
    @Singleton
    fun smsProviderReconciler(
        @ApplicationContext context: Context,
        reader: SmsProviderReader,
        schedules: ScheduleRepository,
        occurrences: OccurrenceRepository,
        executions: OccurrenceExecutionRepository,
        attempts: AttemptRepository,
        settings: SettingsRepository,
        notifications: NotificationPublisher,
        clock: AppClock,
        workScheduler: SmsAttemptWorkScheduler,
    ): SmsProviderReconciler = SmsProviderReconciler(
        context.packageName,
        reader,
        schedules,
        occurrences,
        executions,
        attempts,
        settings,
        notifications,
        clock,
        workScheduler,
    )

    @Provides
    @Singleton
    fun smsProviderChangeObserver(
        @ApplicationContext context: Context,
        reader: SmsProviderReader,
        reconciler: SmsProviderReconciler,
    ): SmsProviderChangeObserver = SmsProviderChangeObserver(context, reader, reconciler)

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
        AndroidAutomaticSmsGateway(context, SmsCallbackDispatchReceiver::class.java.name)

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
