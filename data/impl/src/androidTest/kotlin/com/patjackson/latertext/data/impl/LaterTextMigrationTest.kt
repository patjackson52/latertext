package com.patjackson.latertext.data.impl

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patjackson.latertext.data.impl.db.LaterTextDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaterTextMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LaterTextDatabase::class.java,
    )

    @Test
    fun migration1To2PreservesDatabaseAndAddsProviderAuditColumns() {
        helper.createDatabase(DATABASE_NAME, 1).close()

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            LaterTextDatabase.MIGRATION_1_2,
        )
        val columns = buildSet {
            migrated.query("PRAGMA table_info(send_attempt)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        assertEquals(
            setOf(
                "subscription_id",
                "provider_message_id",
                "provider_status",
                "provider_error_code",
                "provider_observed_at",
            ),
            columns.filterTo(mutableSetOf()) { it in EXPECTED_COLUMNS },
        )
        migrated.close()
    }

    companion object {
        private const val DATABASE_NAME = "latertext-migration-test"
        private val EXPECTED_COLUMNS = setOf(
            "subscription_id",
            "provider_message_id",
            "provider_status",
            "provider_error_code",
            "provider_observed_at",
        )
    }
}
