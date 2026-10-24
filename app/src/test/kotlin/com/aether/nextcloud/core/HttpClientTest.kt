package com.aether.nextcloud.core

import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.dav.WebDavClient
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpClientTest {
    private lateinit var origin: MockWebServer
    private lateinit var other: MockWebServer
    private val client = newAetherHttpClient()

    @Before
    fun setUp() {
        origin = MockWebServer().apply { start() }
        other = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        origin.shutdown()
        other.shutdown()
    }

    private fun credentials() = AetherCredentials("alice@localhost", origin.url("/").toString().trimEnd('/'), "alice", "app-pw", "Alice")

    @Test
    fun `a redirect from http to https is not followed`() = runBlocking {
        // Following it would try a TLS handshake with this plain server and fail as a network error instead.
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://localhost:${other.port}/remote.php/dav/files/alice/"))
        val dav = WebDavClient(client, credentials(), "Aether (Test; Android 36)")

        val result = dav.propfind(credentials().webDavBaseUrl, depth = 0)

        assertEquals(302, ((result as AetherResult.Failure).error as AetherError.Http).code)
        assertEquals(0, other.requestCount)
    }

    @Test
    fun `the authorization header is dropped when a redirect leaves the origin`() {
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/landing").toString()))
        other.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(origin.url("/start")).header("Authorization", "Basic YWxpY2U6YXBwLXB3").build()).execute().use {
            assertTrue(it.isSuccessful)
        }

        assertEquals("Basic YWxpY2U6YXBwLXB3", origin.takeRequest().getHeader("Authorization"))
        assertNull(other.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `redirects within the same scheme are still followed`() {
        origin.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/moved"))
        origin.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(origin.url("/old")).build()).execute().use {
            assertTrue(it.isSuccessful)
        }

        assertEquals("/moved", origin.also { it.takeRequest() }.takeRequest().path)
    }

    @Test
    fun `the shared client does not follow ssl redirects`() {
        assertEquals(false, client.followSslRedirects)
        assertEquals(true, client.followRedirects)
    }
}
