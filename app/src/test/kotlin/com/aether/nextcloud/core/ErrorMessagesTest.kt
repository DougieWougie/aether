package com.aether.nextcloud.core

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.R
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Error text lives in strings.xml and is chosen when shown; what is stored is only the code. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class ErrorMessagesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun aLiveErrorKeepsItsDetailsWhileAStoredCodeShowsTheGenericText() {
        assertEquals("boom", AetherError.Network(IOException("boom")).describe(context))
        assertEquals(context.getString(R.string.error_network), AetherError.Network(IOException()).describe(context))
        assertEquals("Server returned HTTP 502: Bad Gateway", AetherError.Http(502, "Bad Gateway").describe(context))
        assertEquals("Server returned HTTP 502", errorCodeText(context, "http:502"))
        assertEquals("Unexpected server response: bad xml", AetherError.Malformed("bad xml").describe(context))
        assertEquals("Unexpected server response", errorCodeText(context, "malformed"))
        assertEquals("Enter an address", AetherError.InvalidServer("Enter an address").describe(context))
    }

    @Test
    fun everyErrorHasAStoredTextAndAUsefulOneForTheUser() {
        listOf(
            AetherError.Unauthorized, AetherError.NotFound, AetherError.PreconditionFailed, AetherError.Locked,
            AetherError.InsufficientStorage, AetherError.Timeout,
        ).forEach { error ->
            val text = errorCodeText(context, error.code())
            assertTrue("$error needs text", !text.isNullOrBlank())
            assertEquals(text, error.describe(context))
        }
        assertTrue(errorCodeText(context, "locked")!!.contains("locked"))
        assertTrue(errorCodeText(context, "insufficient_storage")!!.contains("storage is full"))
        assertEquals("Cancelled", errorCodeText(context, "cancelled"))
    }

    @Test
    fun noCodeMeansNoTextAndAnUnknownOneIsAGenericFailure() {
        assertNull(errorCodeText(context, null))
        assertEquals(context.getString(R.string.error_unknown), errorCodeText(context, "from_a_newer_version"))
        assertEquals(context.getString(R.string.error_unknown), errorCodeText(context, "http:abc"))
    }
}
