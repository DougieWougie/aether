package com.aether.nextcloud.auth

import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses a canned client rather than a socket, so https URLs can be answered without certificates. */
class LoginFlowV2ClientEdgeCasesTest {
    private fun clientAnswering(code: Int = 200, body: String = "", failWith: IOException? = null): Pair<LoginFlowV2Client, MutableList<String>> {
        val urls = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            urls += chain.request().url.toString()
            failWith?.let { throw it }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("msg")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return LoginFlowV2Client(http, "Aether (Test)") to urls
    }

    private fun initBody(login: String, poll: String) = """{"poll":{"token":"t","endpoint":"$poll"},"login":"$login"}"""

    @Test
    fun `a blank address is rejected before any request`() = runTest {
        val (client, urls) = clientAnswering()

        val result = client.start("   ")

        assertTrue((result as AetherResult.Failure).error is AetherError.InvalidServer)
        assertTrue(urls.isEmpty())
    }

    @Test
    fun `a server that hands out plain http urls is refused`() = runTest {
        val (client, _) = clientAnswering(body = initBody("http://cloud.example.com/login/flow/x", "https://cloud.example.com/poll"))

        val error = (client.start("cloud.example.com") as AetherResult.Failure).error

        assertTrue(error is AetherError.InvalidServer)
        assertTrue((error as AetherError.InvalidServer).detail.contains("unencrypted"))
    }

    @Test
    fun `both urls over https are accepted`() = runTest {
        val (client, urls) = clientAnswering(body = initBody("https://cloud.example.com/login/flow/x", "https://cloud.example.com/poll"))

        val session = (client.start("cloud.example.com") as AetherResult.Success).value

        assertEquals("https://cloud.example.com", session.server)
        assertEquals(listOf("https://cloud.example.com/index.php/login/v2"), urls)
    }

    @Test
    fun `a non 200 answer to the first request is an error`() = runTest {
        val (client, _) = clientAnswering(code = 503)

        assertEquals(AetherError.Http(503, "msg"), (client.start("cloud.example.com") as AetherResult.Failure).error)
    }

    @Test
    fun `unparseable json from the first request is malformed`() = runTest {
        val (client, _) = clientAnswering(body = "<html>not json</html>")

        assertTrue((client.start("cloud.example.com") as AetherResult.Failure).error is AetherError.Malformed)
    }

    @Test
    fun `a poll answer that is not json is malformed`() = runTest {
        val (client, _) = clientAnswering(body = "oops")

        val result = client.poll("https://cloud.example.com/poll", "t", "https://cloud.example.com")

        assertTrue((result as AetherResult.Failure).error is AetherError.Malformed)
    }

    @Test
    fun `an unexpected poll status is an http error`() = runTest {
        val (client, _) = clientAnswering(code = 500)

        assertEquals(AetherError.Http(500, "msg"), (client.poll("https://cloud.example.com/poll", "t", "https://cloud.example.com") as AetherResult.Failure).error)
    }

    @Test
    fun `a pending poll is not an error`() = runTest {
        val (client, _) = clientAnswering(code = 404)

        assertEquals(AetherResult.Success(PollOutcome.Pending), client.poll("https://cloud.example.com/poll", "t", "https://cloud.example.com"))
    }

    @Test
    fun `network errors while waiting are retried until the limit and then reported`() = runBlocking {
        val (client, urls) = clientAnswering(failWith = IOException("offline"))

        val result = client.awaitCredentials("https://cloud.example.com/poll", "t", "https://cloud.example.com", intervalMs = 1, timeoutMs = 60_000)

        assertTrue((result as AetherResult.Failure).error is AetherError.Network)
        assertEquals(LoginFlowV2Client.MAX_CONSECUTIVE_NETWORK_ERRORS, urls.size)
    }

    @Test
    fun `waiting gives up when the flow expires`() = runBlocking {
        val (client, _) = clientAnswering(code = 404)

        val result = client.awaitCredentials("https://cloud.example.com/poll", "t", "https://cloud.example.com", intervalMs = 10, timeoutMs = 50)

        assertEquals(AetherResult.Failure(AetherError.Timeout), result)
    }

    @Test
    fun `an address with a query or fragment is rejected`() {
        assertTrue(LoginFlowV2Client.normalizeServerUrl("https://cloud.example.com/?a=1") is AetherResult.Failure)
        assertTrue(LoginFlowV2Client.normalizeServerUrl("https://cloud.example.com/#frag") is AetherResult.Failure)
    }

    @Test
    fun `an issued server on another port is refused and names the port`() {
        val result = LoginFlowV2Client.verifyIssuedServer("https://cloud.example.com", "https://cloud.example.com:8443")

        val detail = ((result as AetherResult.Failure).error as AetherError.InvalidServer).detail
        assertTrue(detail, detail.contains("cloud.example.com:8443"))
    }

    @Test
    fun `an issued server over http is refused unless insecure mode is on`() {
        assertTrue(LoginFlowV2Client.verifyIssuedServer("https://h", "http://h") is AetherResult.Failure)
        assertFalse(LoginFlowV2Client.verifyIssuedServer("http://h", "http://h", allowInsecure = true) is AetherResult.Failure)
    }

    @Test
    fun `usesOnlyHttps needs both urls to be https`() {
        assertTrue(LoginSession("https://h", "https://h/l", "https://h/p", "t").usesOnlyHttps())
        assertFalse(LoginSession("https://h", "http://h/l", "https://h/p", "t").usesOnlyHttps())
        assertFalse(LoginSession("https://h", "https://h/l", "http://h/p", "t").usesOnlyHttps())
    }
}
