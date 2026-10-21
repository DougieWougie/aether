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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NextcloudOcsClientTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var credentials: AetherCredentials
    private lateinit var ocs: NextcloudOcsClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        credentials = AetherCredentials("alice@localhost", server.url("/").toString().trimEnd('/'), "alice", "pw", "Alice")
        ocs = NextcloudOcsClient(OkHttpClient(), "Aether (Test; Android 36)")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun userBody(quota: String) = """
        {"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},
         "data":{"id":"alice","enabled":true,"display-name":"Alice Liddell","email":"a@example.com","quota":$quota}}}
    """.trimIndent()

    @Test
    fun `userInfo parses limited quota and sends the ocs header`() = runBlocking {
        server.enqueue(MockResponse().setBody(userBody("""{"free":9000,"used":1000,"total":10000,"relative":10,"quota":10000}""")))

        val info = (ocs.userInfo(credentials) as AetherResult.Success).value

        assertEquals(UserInfo("Alice Liddell", 1000, 9000, 10000), info)
        val request = server.takeRequest()
        assertEquals("/ocs/v2.php/cloud/user?format=json", request.path)
        assertEquals("true", request.getHeader("OCS-APIRequest"))
        assertTrue(request.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test
    fun `userInfo treats -3 and none as unlimited`() = runBlocking {
        server.enqueue(MockResponse().setBody(userBody("""{"free":123456,"used":10,"total":123466,"relative":0,"quota":-3}""")))
        server.enqueue(MockResponse().setBody(userBody("""{"used":10,"quota":"none"}""")))

        val a = (ocs.userInfo(credentials) as AetherResult.Success).value
        val b = (ocs.userInfo(credentials) as AetherResult.Success).value

        assertTrue(a.isUnlimited)
        assertNull(a.totalBytes)
        assertTrue(b.isUnlimited)
        assertNull(b.freeBytes)
    }

    @Test
    fun `userInfo maps errors`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("{}"))

        assertEquals(AetherResult.Failure(AetherError.Unauthorized), ocs.userInfo(credentials))
        assertTrue((ocs.userInfo(credentials) as AetherResult.Failure).error is AetherError.Malformed)
    }

    @Test
    fun `revokeAppPassword issues a delete with the ocs header`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))

        assertTrue(ocs.revokeAppPassword(credentials) is AetherResult.Success)

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/ocs/v2.php/core/apppassword", request.path)
        assertEquals("true", request.getHeader("OCS-APIRequest"))
    }

    @Test
    fun `preview url follows the core preview endpoint`() {
        val url = ocs.previewUrl(credentials, "1050")
        assertTrue(url.endsWith("/index.php/core/preview?fileId=1050&x=256&y=256&a=1"))
    }

    @Test
    fun `downloadPreview saves the image bytes`() = runBlocking {
        server.enqueue(MockResponse().setBody("PNGDATA"))
        val target = File(tmp.root, "thumbs/1050.png")

        assertTrue(ocs.downloadPreview(credentials, "1050", target) is AetherResult.Success)

        assertEquals("PNGDATA", target.readText())
        assertTrue(server.takeRequest().path!!.contains("fileId=1050"))
    }
}
