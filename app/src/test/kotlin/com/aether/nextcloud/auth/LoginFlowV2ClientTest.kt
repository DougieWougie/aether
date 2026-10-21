package com.aether.nextcloud.auth

import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LoginFlowV2ClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: LoginFlowV2Client

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = LoginFlowV2Client(OkHttpClient(), "Aether (Test; Android 36)", allowInsecure = true)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun initBody() = """
        {"poll":{"token":"tok123","endpoint":"${baseUrl()}/index.php/login/v2/poll"},
         "login":"${baseUrl()}/index.php/login/v2/flow/abc"}
    """.trimIndent()

    private fun authenticatedBody() =
        """{"server":"${baseUrl()}","loginName":"alice","appPassword":"s3cret","extra":1}"""

    @Test
    fun `start posts to login v2 with user agent and parses session`() = runBlocking {
        server.enqueue(MockResponse().setBody(initBody()))

        val result = client.start(baseUrl())

        val session = (result as AetherResult.Success).value
        assertEquals("tok123", session.pollToken)
        assertEquals("${baseUrl()}/index.php/login/v2/poll", session.pollEndpoint)
        assertEquals("${baseUrl()}/index.php/login/v2/flow/abc", session.loginUrl)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/index.php/login/v2", request.path)
        assertEquals("Aether (Test; Android 36)", request.getHeader("User-Agent"))
    }

    @Test
    fun `start tolerates trailing slash and index php in server input`() = runBlocking {
        server.enqueue(MockResponse().setBody(initBody()))

        client.start("${baseUrl()}/index.php/")

        assertEquals("/index.php/login/v2", server.takeRequest().path)
    }

    @Test
    fun `start reports http failures and malformed bodies`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("<html>not json</html>"))

        val http = client.start(baseUrl())
        val malformed = client.start(baseUrl())

        assertTrue((http as AetherResult.Failure).error is AetherError.Http)
        assertTrue((malformed as AetherResult.Failure).error is AetherError.Malformed)
    }

    @Test
    fun `poll returns pending on 404`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.poll("${baseUrl()}/index.php/login/v2/poll", "tok123")

        assertEquals(AetherResult.Success(PollOutcome.Pending), result)
        val request = server.takeRequest()
        assertEquals("token=tok123", request.body.readUtf8())
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
    }

    @Test
    fun `poll returns credentials on 200`() = runBlocking {
        server.enqueue(MockResponse().setBody(authenticatedBody()))

        val result = client.poll("${baseUrl()}/index.php/login/v2/poll", "tok123")

        val outcome = (result as AetherResult.Success).value as PollOutcome.Authenticated
        assertEquals(LoginCredentials(baseUrl(), "alice", "s3cret"), outcome.credentials)
    }

    @Test
    fun `awaitCredentials keeps polling through 404 until authenticated`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody(authenticatedBody()))

        val result = client.awaitCredentials("${baseUrl()}/index.php/login/v2/poll", "tok123", intervalMs = 5)

        assertEquals("s3cret", (result as AetherResult.Success).value.appPassword)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `awaitCredentials rides out transient network errors`() = runBlocking {
        val url = "${baseUrl()}/index.php/login/v2/poll"
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setBody(authenticatedBody()))

        val result = client.awaitCredentials(url, "tok123", intervalMs = 5)

        assertEquals("s3cret", (result as AetherResult.Success).value.appPassword)
    }

    @Test
    fun `awaitCredentials stops on unexpected status`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.awaitCredentials("${baseUrl()}/index.php/login/v2/poll", "tok123", intervalMs = 5)

        assertEquals(AetherResult.Failure(AetherError.Unauthorized), result)
    }

    @Test
    fun `awaitCredentials times out while the user never finishes`() = runBlocking {
        repeat(50) { server.enqueue(MockResponse().setResponseCode(404)) }

        val result = client.awaitCredentials("${baseUrl()}/index.php/login/v2/poll", "t", intervalMs = 5, timeoutMs = 60)

        assertEquals(AetherResult.Failure(AetherError.Timeout), result)
    }

    @Test
    fun `normalizeServerUrl defaults to https and upgrades plain http`() {
        fun normalize(input: String, insecure: Boolean = false) =
            (LoginFlowV2Client.normalizeServerUrl(input, insecure) as? AetherResult.Success)?.value

        assertEquals("https://cloud.example.com", normalize("cloud.example.com"))
        assertEquals("https://cloud.example.com", normalize("  http://cloud.example.com/ "))
        assertEquals("http://localhost:8080", normalize("http://localhost:8080", insecure = true))
        assertEquals("https://example.com/nextcloud", normalize("https://example.com/nextcloud/index.php/"))
    }

    @Test
    fun `normalizeServerUrl rejects blank and invalid input`() {
        assertTrue(LoginFlowV2Client.normalizeServerUrl("   ") is AetherResult.Failure)
        assertTrue(LoginFlowV2Client.normalizeServerUrl("https://exa mple.com") is AetherResult.Failure)
        assertTrue(LoginFlowV2Client.normalizeServerUrl("https://example.com?x=1") is AetherResult.Failure)
    }
}
