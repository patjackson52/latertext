package com.patjackson.latertext.data.impl.di

import android.content.Context
import com.patjackson.latertext.data.api.AttachmentRepository
import com.patjackson.latertext.data.api.AttachmentStore
import com.patjackson.latertext.data.api.AttemptRepository
import com.patjackson.latertext.data.api.DraftRepository
import com.patjackson.latertext.data.api.NotificationRecordRepository
import com.patjackson.latertext.data.api.OccurrenceRepository
import com.patjackson.latertext.data.api.OccurrenceExecutionRepository
import com.patjackson.latertext.data.api.OutboxRepository
import com.patjackson.latertext.data.api.RecipientRepository
import com.patjackson.latertext.data.api.ScheduleRepository
import com.patjackson.latertext.data.api.SettingsRepository
import com.patjackson.latertext.data.impl.attachment.PrivateAttachmentStore
import com.patjackson.latertext.data.impl.db.LaterTextDatabase
import com.patjackson.latertext.data.impl.repository.RoomAttachmentRepository
import com.patjackson.latertext.data.impl.repository.RoomAttemptRepository
import com.patjackson.latertext.data.impl.repository.RoomDraftRepository
import com.patjackson.latertext.data.impl.repository.RoomNotificationRecordRepository
import com.patjackson.latertext.data.impl.repository.RoomOccurrenceRepository
import com.patjackson.latertext.data.impl.repository.RoomOccurrenceExecutionRepository
import com.patjackson.latertext.data.impl.repository.RoomOutboxRepository
import com.patjackson.latertext.data.impl.repository.RoomRecipientRepository
import com.patjackson.latertext.data.impl.repository.RoomScheduleRepository
import com.patjackson.latertext.data.impl.settings.PreferencesSettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataPersistenceModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LaterTextDatabase =
        LaterTextDatabase.build(context)

    @Provides
    @Singleton
    fun provideAttachmentStore(@ApplicationContext context: Context): AttachmentStore =
        PrivateAttachmentStore(context)

    @Provides
    @Singleton
    fun provideAttachmentRepository(
        database: LaterTextDatabase,
        store: AttachmentStore,
    ): AttachmentRepository = RoomAttachmentRepository(database, store)

    @Provides
    @Singleton
    fun provideScheduleRepository(
        database: LaterTextDatabase,
        attachments: AttachmentRepository,
    ): ScheduleRepository = RoomScheduleRepository(database, attachments)

    @Provides
    @Singleton
    fun provideOccurrenceRepository(database: LaterTextDatabase): OccurrenceRepository =
        RoomOccurrenceRepository(database)

    @Provides
    @Singleton
    fun provideOccurrenceExecutionRepository(
        database: LaterTextDatabase,
    ): OccurrenceExecutionRepository = RoomOccurrenceExecutionRepository(database)

    @Provides
    @Singleton
    fun provideAttemptRepository(database: LaterTextDatabase): AttemptRepository =
        RoomAttemptRepository(database)

    @Provides
    @Singleton
    fun provideOutboxRepository(database: LaterTextDatabase): OutboxRepository =
        RoomOutboxRepository(database)

    @Provides
    @Singleton
    fun provideDraftRepository(database: LaterTextDatabase): DraftRepository =
        RoomDraftRepository(database)

    @Provides
    @Singleton
    fun provideRecipientRepository(database: LaterTextDatabase): RecipientRepository =
        RoomRecipientRepository(database)

    @Provides
    @Singleton
    fun provideNotificationRecordRepository(
        database: LaterTextDatabase,
    ): NotificationRecordRepository = RoomNotificationRecordRepository(database)

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        PreferencesSettingsRepository.create(context)
}
