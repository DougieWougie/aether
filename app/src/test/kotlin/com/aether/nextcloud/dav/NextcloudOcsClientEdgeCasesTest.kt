package com.aether.nextcloud.dav

import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NextcloudOcsClientEdgeCasesTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var credentials: AetherCredentials
    private lateinit var ocs: NextcloudOcsClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        credentials = AetherCredentials("alice@localhost", server.url("/").toString().trimEnd('/'), "alice", "pw", "Alice")
        ocs = NextcloudOcsClient(OkHttpClient(), "Aether (Test)")
    }

    @After fun tearDown() = server.shutdown()

    private fun user(data: String) = """{"ocs":{"meta":{"status":"ok"},"data":$data}}"""

    private fun userInfo(data: String): UserInfo {
        server.enqueue(MockResponse().setBody(user(data)))
        return (runBlocking { ocs.userInfo(credentials) } as AetherResult.Success).value
    }

    @Test
    fun `the display name falls back to the user id`() {
        assertEquals("alice", userInfo("""{"id":"alice","quota":{"used":1,"total":10,"free":9}}""").displayName)
    }

    @Test
    fun `unlimited quota is recognised by none, minus three, or a missing total`() {
        assertTrue(userInfo("""{"id":"a","quota":{"used":1,"total":0,"free":-3,"quota":"none"}}""").isUnlimited)
        assertTrue(userInfo("""{"id":"a","quota":{"used":1,"total":100,"free":99,"quota":-3}}""").isUnlimited)
        assertTrue(userInfo("""{"id":"a","quota":{"used":1}}""").isUnlimited)
    }

    @Test
    fun `a negative free value is treated as unknown`() {
        val info = userInfo("""{"id":"a","quota":{"used":1,"total":100,"free":-3,"quota":100}}""")

        assertNull(info.freeBytes)
        assertEquals(100L, info.totalBytes)
    }

    @Test
    fun `a body that is not json is malformed`() {
        server.enqueue(MockResponse().setBody("<html>maintenance</html>"))

        assertTrue((runBlocking { ocs.userInfo(credentials) } as AetherResult.Failure).error is AetherError.Malformed)
    }

    @Test
    fun `a rejected password on the user lookup is unauthorized`() {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(AetherResult.Failure(AetherError.Unauthorized), runBlocking { ocs.userInfo(credentials) })
    }

    @Test
    fun `revoking the app password sends a delete and reports success`() {
        server.enqueue(MockResponse().setResponseCode(200))

        assertEquals(AetherResult.Success(Unit), runBlocking { ocs.revokeAppPassword(credentials) })

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/ocs/v2.php/core/apppassword", request.path)
        assertEquals("true", request.getHeader("OCS-APIRequest"))
    }

    @Test
    fun `a failed revocation is reported rather than thrown`() {
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(runBlocking { ocs.revokeAppPassword(credentials) } is AetherResult.Failure)
    }

    @Test
    fun `the preview url carries the file id and size`() {
        val url = ocs.previewUrl(credentials, "42", size = 128)

        assertEquals("${credentials.serverUrl}/index.php/core/preview?fileId=42&x=128&y=128&a=1", url)
    }

    @Test
    fun `a preview is saved into a folder that did not exist yet`() {
        server.enqueue(MockResponse().setBody("png-bytes"))
        val target = File(tmp.root, "a/b/preview.png")

        assertEquals(AetherResult.Success(Unit), runBlocking { ocs.downloadPreview(credentials, "42", target) })

        assertEquals("png-bytes", target.readText())
    }

    @Test
    fun `a missing preview is an error and writes nothing`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val target = File(tmp.root, "preview.png")

        assertEquals(AetherResult.Failure(AetherError.NotFound), runBlocking { ocs.downloadPreview(credentials, "42", target) })
        assertFalse(target.exists())
    }

    @Test
    fun `a preview that cannot be written is a network style error`() {
        server.enqueue(MockResponse().setBody("png-bytes"))
        val target = tmp.newFolder("occupied")

        val result = runBlocking { ocs.downloadPreview(credentials, "42", target) }

        assertTrue((result as AetherResult.Failure).error is AetherError.Network)
    }

    @Test
    fun `a custom avatar is saved when the server says so`() {
        server.enqueue(MockResponse().setBody("jpeg").setHeader("X-NC-IsCustomAvatar", "1"))
        val target = File(tmp.root, "x/avatar.jpg")

        assertEquals(AetherResult.Success(true), runBlocking { ocs.downloadAvatar(credentials, target) })

        assertEquals("jpeg", target.readText())
        assertEquals("/index.php/avatar/alice/192", server.takeRequest().path)
    }

    @Test
    fun `a generated avatar is not kept and removes a stale file`() {
        server.enqueue(MockResponse().setBody("letter").setHeader("X-NC-IsCustomAvatar", "0"))
        val target = tmp.newFile("avatar.jpg")

        assertEquals(AetherResult.Success(false), runBlocking { ocs.downloadAvatar(credentials, target) })

        assertFalse(target.exists())
    }

    @Test
    fun `older servers mark a custom avatar with status 200 and a generated one with 201`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("jpeg"))
        server.enqueue(MockResponse().setResponseCode(201).setBody("letter"))

        assertEquals(AetherResult.Success(true), runBlocking { ocs.downloadAvatar(credentials, File(tmp.root, "one.jpg")) })
        assertEquals(AetherResult.Success(false), runBlocking { ocs.downloadAvatar(credentials, File(tmp.root, "two.jpg")) })
    }

    @Test
    fun `no avatar at all removes the stale file`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val target = tmp.newFile("avatar.jpg")

        assertEquals(AetherResult.Success(false), runBlocking { ocs.downloadAvatar(credentials, target) })
        assertFalse(target.exists())
    }

    @Test
    fun `a server error on the avatar is an error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(runBlocking { ocs.downloadAvatar(credentials, File(tmp.root, "a.jpg")) } is AetherResult.Failure)
    }

    @Test
    fun `an avatar that cannot be written is an error`() {
        server.enqueue(MockResponse().setBody("jpeg").setHeader("X-NC-IsCustomAvatar", "1"))

        val result = runBlocking { ocs.downloadAvatar(credentials, tmp.newFolder("occupied")) }

        assertTrue((result as AetherResult.Failure).error is AetherError.Network)
    }
}
