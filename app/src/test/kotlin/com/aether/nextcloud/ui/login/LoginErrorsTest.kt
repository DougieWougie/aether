package com.aether.nextcloud.ui.login

import com.aether.nextcloud.R
import com.aether.nextcloud.core.AetherError
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoginErrorsTest {
    @Test
    fun `an unreachable server gets a plain message instead of the exception text`() {
        assertEquals(R.string.login_unreachable, AetherError.Network(IOException("Failed to connect to /10.0.2.2:9")).loginMessageRes())
    }

    @Test
    fun `a host that is not Nextcloud is called out`() {
        assertEquals(R.string.login_not_nextcloud, AetherError.NotFound.loginMessageRes())
        assertEquals(R.string.login_not_nextcloud, AetherError.Http(403, "Forbidden").loginMessageRes())
        assertEquals(R.string.login_not_nextcloud, AetherError.Malformed("not json").loginMessageRes())
    }

    @Test
    fun `timeouts keep their message and server faults and validation errors fall back to describe`() {
        assertEquals(R.string.login_timed_out, AetherError.Timeout.loginMessageRes())
        assertNull(AetherError.Http(503, null).loginMessageRes())
        assertNull(AetherError.InvalidServer("Use an https:// address").loginMessageRes())
    }
}
