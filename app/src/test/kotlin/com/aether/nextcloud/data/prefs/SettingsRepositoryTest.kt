package com.aether.nextcloud.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun `backup aimed at a removed account is switched off and unlinked`() {
        val settings = BackupSettings(enabled = true, accountName = "alice@h", remoteFolder = "/Photos", includeVideos = false)

        val after = settings.withoutAccount("alice@h")

        assertEquals(false, after.enabled)
        assertNull(after.accountName)
        assertEquals("/Photos", after.remoteFolder)
        assertEquals(false, after.includeVideos)
    }

    @Test
    fun `backup for another account is left alone`() {
        val settings = BackupSettings(enabled = true, accountName = "bob@h")

        assertSame(settings, settings.withoutAccount("alice@h"))
    }

    @Test
    fun `recent folders inside the removed account are dropped and the order of the rest is kept`() {
        val folders = listOf("alice@h:/Docs", "bob@h:/Photos", "alice@h:8443:/Other", "alice@h:/", "bob@h:/Work")

        assertEquals(listOf("bob@h:/Photos", "alice@h:8443:/Other", "bob@h:/Work"), SettingsRepository.foldersWithout(folders, "alice@h"))
    }

    @Test
    fun `entries that are not document ids are kept rather than guessed at`() {
        assertEquals(listOf("garbage"), SettingsRepository.foldersWithout(listOf("garbage"), "alice@h"))
    }

    @Test
    fun `an auth issue is added once and cleared by name`() {
        val flagged = SettingsRepository.withAuthIssue(emptySet(), "alice@h", true)

        assertEquals(setOf("alice@h"), flagged)
        assertEquals(flagged, SettingsRepository.withAuthIssue(flagged, "alice@h", true))
        assertEquals(setOf("alice@h"), SettingsRepository.withAuthIssue(flagged + "bob@h", "bob@h", false))
        assertEquals(emptySet<String>(), SettingsRepository.withAuthIssue(flagged, "alice@h", false))
    }
}
