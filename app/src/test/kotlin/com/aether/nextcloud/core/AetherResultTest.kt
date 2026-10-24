package com.aether.nextcloud.core

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherResultTest {
    private val ok: AetherResult<Int> = AetherResult.Success(2)
    private val failed: AetherResult<Int> = AetherResult.Failure(AetherError.NotFound)

    @Test
    fun `map transforms a success and passes a failure through untouched`() {
        assertEquals(AetherResult.Success("2"), ok.map { it.toString() })
        assertSame(failed, failed.map { it.toString() })
    }

    @Test
    fun `flatMap chains successes and stops at the first failure`() {
        assertEquals(AetherResult.Success(4), ok.flatMap { AetherResult.Success(it * 2) })
        assertSame(failed, failed.flatMap { AetherResult.Success(it * 2) })
        assertEquals(AetherResult.Failure(AetherError.Timeout), ok.flatMap { AetherResult.Failure(AetherError.Timeout) })
    }

    @Test
    fun `getOrNull returns the value only for a success`() {
        assertEquals(2, ok.getOrNull())
        assertNull(failed.getOrNull())
    }

    @Test
    fun `every error has a readable description`() {
        assertEquals("boom", AetherError.Network(IOException("boom")).describe())
        assertEquals("Network error", AetherError.Network(IOException()).describe())
        assertEquals("Server returned HTTP 500", AetherError.Http(500, null).describe())
        assertEquals("Server returned HTTP 502: Bad Gateway", AetherError.Http(502, "Bad Gateway").describe())
        assertEquals("Authentication failed", AetherError.Unauthorized.describe())
        assertEquals("Not found", AetherError.NotFound.describe())
        assertEquals("The file changed on the server", AetherError.PreconditionFailed.describe())
        assertTrue(AetherError.Locked.describe().contains("locked"))
        assertTrue(AetherError.InsufficientStorage.describe().contains("storage is full"))
        assertEquals("Timed out", AetherError.Timeout.describe())
        assertEquals("Unexpected server response: bad xml", AetherError.Malformed("bad xml").describe())
        assertEquals("Enter an address", AetherError.InvalidServer("Enter an address").describe())
    }
}
