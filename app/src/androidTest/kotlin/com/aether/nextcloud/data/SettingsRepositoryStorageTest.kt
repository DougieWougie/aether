package com.aether.nextcloud.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.data.prefs.BackupAlbum
import com.aether.nextcloud.data.prefs.BackupSettings
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.data.prefs.SyncSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The real DataStore on the device, put back the way it was found. */
@RunWith(AndroidJUnit4::class)
class SettingsRepositoryStorageTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val settings = SettingsRepository(context)
    private lateinit var originalBackup: BackupSettings
    private lateinit var originalSync: SyncSettings
    private var originalDirectShare = true
    private var originalFolders = emptyList<String>()

    @Before
    fun remember() = runBlocking {
        originalBackup = settings.backup.first()
        originalSync = settings.sync.first()
        originalDirectShare = settings.directShareEnabled.first()
        originalFolders = settings.recentFolders.first()
        settings.updateBackup { BackupSettings() }
        settings.forgetAccount("a@h"); settings.forgetAccount("b@h")
        Unit
    }

    @After
    fun restore() = runBlocking {
        settings.updateBackup { originalBackup }
        settings.updateSync { originalSync }
        settings.setDirectShareEnabled(originalDirectShare)
        settings.forgetAccount("a@h"); settings.forgetAccount("b@h")
        Unit
    }

    @Test
    fun aPauseIsStoredAndEndsWhenBackupIsSwitchedOffOrOnAgain() = runBlocking {
        settings.updateBackup { it.copy(enabled = true, accountName = "a@h") }

        assertTrue(settings.updateBackup { it.copy(paused = true) }.paused)
        assertTrue(settings.backup.first().paused)

        assertFalse("switching backup off ends the pause", settings.updateBackup { it.copy(enabled = false) }.paused)
        assertFalse("and it stays ended when backup is back on", settings.updateBackup { it.copy(enabled = true, accountName = "a@h") }.paused)
        Unit
    }

    @Test
    fun backupSettingsRoundTripIncludingAlbums() = runBlocking {
        val saved = settings.updateBackup {
            it.copy(accountName = "a@h", remoteFolder = "/Pics", unmeteredOnly = false, chargingOnly = true, includeVideos = false, albums = listOf(BackupAlbum(7, "Camera")))
        }

        val read = settings.backup.first()

        assertEquals(saved, read)
        assertEquals(listOf(BackupAlbum(7, "Camera")), read.albums)
    }

    @Test
    fun clearingTheAccountAndAlbumsRemovesThemFromStorage() = runBlocking {
        settings.updateBackup { it.copy(accountName = "a@h", albums = listOf(BackupAlbum(1, "x"))) }

        settings.updateBackup { it.copy(accountName = null, albums = null) }

        val read = settings.backup.first()
        assertNull(read.accountName)
        assertNull(read.albums)
    }

    @Test
    fun turningBackupOnStampsTheMomentSoOldPhotosAreNotUploaded() = runBlocking {
        val before = System.currentTimeMillis()

        val saved = settings.updateBackup { it.copy(enabled = true, accountName = "a@h") }

        assertTrue(saved.backupSinceMillis >= before)
        assertEquals("staying on keeps the stamp", saved.backupSinceMillis, settings.updateBackup { it.copy(chargingOnly = true) }.backupSinceMillis)
    }

    @Test
    fun theSyncConstraintIsStored() = runBlocking {
        settings.updateSync { it.copy(pinnedSyncUnmeteredOnly = false) }
        assertFalse(settings.sync.first().pinnedSyncUnmeteredOnly)

        settings.updateSync { it.copy(pinnedSyncUnmeteredOnly = true) }
        assertTrue(settings.sync.first().pinnedSyncUnmeteredOnly)
    }

    @Test
    fun directShareIsStored() = runBlocking {
        settings.setDirectShareEnabled(false)
        assertFalse(settings.directShareEnabled.first())

        settings.setDirectShareEnabled(true)
        assertTrue(settings.directShareEnabled.first())
    }

    @Test
    fun recentFoldersAreNewestFirstWithoutDuplicatesAndCapped() = runBlocking {
        listOf("a@h:/1", "a@h:/2", "a@h:/3", "a@h:/4", "a@h:/5").forEach { settings.rememberFolder(it) }
        settings.rememberFolder("a@h:/3")

        val folders = settings.recentFolders.first()

        assertEquals(SettingsRepository.MAX_RECENT_FOLDERS, folders.size)
        assertEquals("a@h:/3", folders.first())
        assertEquals(folders.distinct(), folders)
        assertFalse("the oldest fell off", "a@h:/1" in folders)
    }

    @Test
    fun anAuthIssueIsReportedChangedOnlyTheFirstTime() = runBlocking {
        assertTrue(settings.setAuthIssue("a@h", true))
        assertFalse(settings.setAuthIssue("a@h", true))
        assertEquals(setOf("a@h"), settings.authIssues.first().filter { it == "a@h" }.toSet())

        assertTrue(settings.setAuthIssue("a@h", false))
        assertFalse(settings.setAuthIssue("a@h", false))
    }

    @Test
    fun forgettingAnAccountClearsItsBackupFoldersAndIssue() = runBlocking {
        settings.updateBackup { it.copy(enabled = true, accountName = "a@h") }
        settings.rememberFolder("a@h:/Docs")
        settings.rememberFolder("b@h:/Docs")
        settings.setAuthIssue("a@h", true)

        val forgotten = settings.forgetAccount("a@h")

        assertFalse(forgotten.backup.enabled)
        assertNull(forgotten.backup.accountName)
        assertTrue("b@h:/Docs" in forgotten.recentFolders)
        assertFalse(forgotten.recentFolders.any { it.startsWith("a@h:") })
        assertFalse("a@h" in settings.authIssues.first())
    }
}
