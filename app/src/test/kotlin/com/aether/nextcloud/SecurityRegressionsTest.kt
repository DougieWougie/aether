package com.aether.nextcloud

import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.auth.LoginCredentials
import com.aether.nextcloud.auth.LoginFlowV2Client
import com.aether.nextcloud.auth.LoginSession
import com.aether.nextcloud.auth.pollEndpointBelongsTo
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.data.cache.DocumentDiskCache
import com.aether.nextcloud.dav.WebDavXmlParser
import com.aether.nextcloud.sync.BackupPaths
import java.io.File
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecurityRegressionsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `thumbnailFor hashes fileId so path traversal cannot escape thumbnailsDir`() {
        val root = temp.newFolder("cache")
        val thumbs = File(root, "thumbs")
        val cache = DocumentDiskCache(File(root, "files"), File(root, "pinned"), File(root, "staging"), thumbnailsDir = thumbs)

        val file = cache.thumbnailFor("alice@h", "../../../../databases/aether.db", 256, "etag1")

        assertTrue(
            "thumbnail must stay inside thumbnailsDir: ${file.canonicalPath}",
            file.canonicalPath.startsWith(thumbs.canonicalPath + File.separator),
        )
        assertFalse(".." in file.name)
    }

    @Test
    fun `credential data classes redact secrets in toString`() {
        val creds = AetherCredentials("alice@h", "https://h", "alice", "super-secret-pw", "Alice")
        val login = LoginCredentials("https://h", "alice", "super-secret-pw")
        val session = LoginSession("https://h", "https://h/login", "https://h/poll", "super-secret-token")

        assertFalse("super-secret-pw" in creds.toString())
        assertFalse("super-secret-pw" in login.toString())
        assertFalse("super-secret-token" in session.toString())
    }

    @Test
    fun `pollEndpointBelongsTo enforces https and matching scheme`() {
        assertTrue(pollEndpointBelongsTo("https://cloud.example.com", "https://cloud.example.com/poll"))
        assertFalse(pollEndpointBelongsTo("https://cloud.example.com", "http://cloud.example.com:443/poll"))
        assertFalse(pollEndpointBelongsTo("http://cloud.example.com", "http://cloud.example.com/poll", allowInsecure = false))
        assertTrue(pollEndpointBelongsTo("http://cloud.example.com", "http://cloud.example.com/poll", allowInsecure = true))
    }

    @Test
    fun `normalizeServerUrl rejects embedded userinfo`() {
        val result = LoginFlowV2Client.normalizeServerUrl("https://user:pass@cloud.example.com")
        assertTrue(result is AetherResult.Failure && result.error is AetherError.InvalidServer)
    }

    @Test
    fun `poll refuses plain http endpoint when allowInsecure is false`() = runTest {
        val client = LoginFlowV2Client(OkHttpClient(), "Aether (Test)", allowInsecure = false)
        val result = client.poll("http://cloud.example.com:443/poll", "token", "https://cloud.example.com")
        assertTrue(result is AetherResult.Failure && result.error is AetherError.InvalidServer)
    }

    @Test
    fun `BackupPaths sanitizes traversal filenames`() {
        val path = BackupPaths.remotePath("/Camera", 1_704_067_200_000L, "..", ZoneOffset.UTC)
        assertEquals("/Camera/2024/01/file", path)
    }

    @Test
    fun `cleanEtag strips control characters and quotes`() {
        assertEquals("abc123", WebDavXmlParser.cleanEtag("W/\"abc\r\n123\""))
    }
}
