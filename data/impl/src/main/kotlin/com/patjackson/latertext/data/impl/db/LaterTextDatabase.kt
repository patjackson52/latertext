package com.patjackson.latertext.data.impl.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RecipientEndpointEntity::class,
        ScheduleEntity::class,
        ContentRevisionEntity::class,
        AttachmentAssetEntity::class,
        ContentAttachmentEntity::class,
        RuleRevisionEntity::class,
        OccurrenceEntity::class,
        SendAttemptEntity::class,
        AttemptPartEntity::class,
        CallbackTokenEntity::class,
        OccurrenceEventEntity::class,
        ComposerDraftEntity::class,
        DraftAttachmentEntity::class,
        RecentRecipientEntity::class,
        NotificationRecordEntity::class,
        SideEffectOutboxEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(LaterTextTypeConverters::class)
abstract class LaterTextDatabase : RoomDatabase() {
    abstract fun recipientDao(): RecipientDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun contentDao(): ContentDao
    abstract fun attachmentAssetDao(): AttachmentAssetDao
    abstract fun ruleDao(): RuleDao
    abstract fun occurrenceDao(): OccurrenceDao
    abstract fun attemptDao(): AttemptDao
    abstract fun occurrenceEventDao(): OccurrenceEventDao
    abstract fun draftDao(): DraftDao
    abstract fun notificationRecordDao(): NotificationRecordDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        const val DATABASE_NAME = "latertext.db"

        fun build(context: Context, name: String = DATABASE_NAME): LaterTextDatabase =
            Room.databaseBuilder(context.applicationContext, LaterTextDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .build()

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE send_attempt ADD COLUMN subscription_id INTEGER")
                db.execSQL("ALTER TABLE send_attempt ADD COLUMN provider_message_id INTEGER")
                db.execSQL("ALTER TABLE send_attempt ADD COLUMN provider_status INTEGER")
                db.execSQL("ALTER TABLE send_attempt ADD COLUMN provider_error_code INTEGER")
                db.execSQL("ALTER TABLE send_attempt ADD COLUMN provider_observed_at INTEGER")
            }
        }
    }
}
