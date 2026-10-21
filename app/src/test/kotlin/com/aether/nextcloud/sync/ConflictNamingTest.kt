package com.aether.nextcloud.sync

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Test

class ConflictNamingTest {
    private val now = 1_792_484_100_000L
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(now))

    @Test
    fun `inserts the timestamp before the extension`() {
        assertEquals("notes (Conflict $stamp).md", ConflictNaming.conflictName("notes.md", now))
    }

    @Test
    fun `only the last extension is kept`() {
        assertEquals("archive.tar (Conflict $stamp).gz", ConflictNaming.conflictName("archive.tar.gz", now))
    }

    @Test
    fun `files without extension and dotfiles`() {
        assertEquals("README (Conflict $stamp)", ConflictNaming.conflictName("README", now))
        assertEquals(".bashrc (Conflict $stamp)", ConflictNaming.conflictName(".bashrc", now))
    }
}
