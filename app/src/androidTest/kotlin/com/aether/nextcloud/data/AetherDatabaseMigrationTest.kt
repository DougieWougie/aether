package com.aether.nextcloud.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.data.local.AetherDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AetherDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AetherDatabase::class.java)

    @Test
    fun upgradingFromVersionOneKeepsBackedUpPhotosAndAddsTheAlbumColumns() {
        helper.createDatabase("migration-test", 1).apply {
            execSQL("INSERT INTO backed_up_media (accountName, mediaId, isVideo, dateModified, size, remotePath, backedUpAt) VALUES ('a@h', 7, 0, 1, 2048, '/Photos/7.jpg', 99)")
            close()
        }

        val db = helper.runMigrationsAndValidate("migration-test", 2, true, AetherDatabase.MIGRATION_1_2)

        db.query("SELECT remotePath, size, bucketId, bucketName FROM backed_up_media").use { c ->
            assertEquals(true, c.moveToFirst())
            assertEquals("/Photos/7.jpg", c.getString(0))
            assertEquals(2048L, c.getLong(1))
            assertEquals(true, c.isNull(2))
            assertNull(c.getString(3))
        }
    }
}
