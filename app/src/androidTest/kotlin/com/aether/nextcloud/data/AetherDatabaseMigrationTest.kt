package com.aether.nextcloud.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.data.local.AetherDatabase
import com.aether.nextcloud.support.Smoke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@Smoke
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

    @Test
    fun upgradingFromVersionTwoKeepsTransfersAndAddsTheRetryColumns() {
        helper.createDatabase("migration-test-3", 2).apply {
            execSQL("INSERT INTO transfers (accountName, remotePath, direction, state, bytesTransferred, totalBytes, error, createdAt, updatedAt) VALUES ('a@h', '/x.txt', 'UPLOAD', 'FAILED', 0, 5, 'boom', 1, 2)")
            close()
        }

        val db = helper.runMigrationsAndValidate("migration-test-3", 3, true, AetherDatabase.MIGRATION_1_2, AetherDatabase.MIGRATION_2_3)

        db.query("SELECT remotePath, error, sourcePath, mimeType, ifMatchEtag, mediaKey FROM transfers").use { c ->
            assertEquals(true, c.moveToFirst())
            assertEquals("/x.txt", c.getString(0))
            assertEquals("boom", c.getString(1))
            (2..5).forEach { assertEquals(true, c.isNull(it)) }
        }
    }

    @Test
    fun upgradingFromVersionThreeTurnsStoredMessagesIntoCodes() {
        val messages = listOf(
            "Cancelled" to "cancelled",
            "Authentication failed" to "unauthorized",
            "Not found" to "not_found",
            "The file changed on the server" to "precondition_failed",
            "This file is locked on the server. Close it in any editor that has it open and try again." to "locked",
            "Your Nextcloud storage is full. Free up space and try again." to "insufficient_storage",
            "Timed out" to "timeout",
            "Unexpected server response: bad xml" to "malformed",
            "Server returned HTTP 503" to "http:503",
            "Server returned HTTP 502: Bad Gateway" to "http:502",
            "Unable to resolve host \"cloud.example.com\"" to "unknown",
        )
        helper.createDatabase("migration-test-4", 3).apply {
            messages.forEachIndexed { i, (text, _) ->
                execSQL("INSERT INTO transfers (accountName, remotePath, direction, state, bytesTransferred, totalBytes, error, createdAt, updatedAt) VALUES ('a@h', '/f$i.txt', 'UPLOAD', 'FAILED', 0, 5, ?, 1, 2)", arrayOf(text))
            }
            execSQL("INSERT INTO transfers (accountName, remotePath, direction, state, bytesTransferred, totalBytes, createdAt, updatedAt) VALUES ('a@h', '/none.txt', 'UPLOAD', 'COMPLETED', 5, 5, 1, 2)")
            close()
        }

        val db = helper.runMigrationsAndValidate("migration-test-4", 4, true, AetherDatabase.MIGRATION_1_2, AetherDatabase.MIGRATION_2_3, AetherDatabase.MIGRATION_3_4)

        db.query("SELECT remotePath, error FROM transfers ORDER BY id").use { c ->
            messages.forEachIndexed { i, (text, code) ->
                assertEquals(true, c.moveToNext())
                assertEquals("/f$i.txt", c.getString(0))
                assertEquals("for: $text", code, c.getString(1))
            }
            assertEquals(true, c.moveToNext())
            assertNull("rows without an error stay without one", c.getString(1))
        }
    }
}
