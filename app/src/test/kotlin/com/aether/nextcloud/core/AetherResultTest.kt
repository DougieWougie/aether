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
    fun `every error has a stable code that carries no English`() {
        assertEquals("network", AetherError.Network(IOException("boom")).code())
        assertEquals("http:500", AetherError.Http(500, null).code())
        assertEquals("http:502", AetherError.Http(502, "Bad Gateway").code())
        assertEquals("unauthorized", AetherError.Unauthorized.code())
        assertEquals("not_found", AetherError.NotFound.code())
        assertEquals("precondition_failed", AetherError.PreconditionFailed.code())
        assertEquals("locked", AetherError.Locked.code())
        assertEquals("insufficient_storage", AetherError.InsufficientStorage.code())
        assertEquals("timeout", AetherError.Timeout.code())
        assertEquals("malformed", AetherError.Malformed("bad xml").code())
        assertEquals("invalid_server", AetherError.InvalidServer("Enter an address").code())
    }
}
