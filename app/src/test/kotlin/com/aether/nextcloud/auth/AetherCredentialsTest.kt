package com.aether.nextcloud.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class AetherCredentialsTest {
    private val credentials = AetherCredentials(
        accountName = "alice@cloud.example.com",
        serverUrl = "https://cloud.example.com",
        loginName = "alice",
        appPassword = "pw",
        displayName = "Alice",
    )

    @Test
    fun `account name is loginName at host`() {
        assertEquals("alice@cloud.example.com", AccountRepository.accountNameFor("alice", "https://cloud.example.com"))
        assertEquals("alice@cloud.example.com:8443", AccountRepository.accountNameFor("alice", "https://cloud.example.com:8443"))
    }

    @Test
    fun `base urls point at dav endpoints`() {
        assertEquals("https://cloud.example.com/remote.php/dav/files/alice/", credentials.webDavBaseUrl)
        assertEquals("https://cloud.example.com/remote.php/dav/uploads/alice", credentials.uploadsBaseUrl)
    }

    @Test
    fun `file urls percent-encode segments and keep directory slash`() {
        assertEquals("https://cloud.example.com/remote.php/dav/files/alice/", credentials.fileUrl("/"))
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/alice/My%20Docs/n%C3%B6tes%231.md",
            credentials.fileUrl("/My Docs/nötes#1.md"),
        )
        assertEquals("https://cloud.example.com/remote.php/dav/files/alice/Docs/", credentials.fileUrl("/Docs/"))
    }

    @Test
    fun `subdirectory installs keep their prefix`() {
        val sub = credentials.copy(serverUrl = "https://example.com/nextcloud")
        assertEquals("https://example.com/nextcloud/remote.php/dav/files/alice/", sub.webDavBaseUrl)
    }
}
