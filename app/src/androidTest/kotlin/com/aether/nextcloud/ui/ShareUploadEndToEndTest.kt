package com.aether.nextcloud.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.MediaStoreFixtures
import com.aether.nextcloud.support.awaitTrue
import com.aether.nextcloud.ui.share.SharePhase
import com.aether.nextcloud.ui.share.ShareUploadViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the share sheet queues really reaching the server through WorkManager and the upload worker. The view model's own logic
 * is tested on the JVM (`ShareUploadViewModelTest`); WorkManager does not run workers there, so these two stay on a device.
 */
@RunWith(AndroidJUnit4::class)
class ShareUploadEndToEndTest {
    @get:Rule val env = AetherTestRule()

    private val settings by lazy { SettingsRepository(env.context) }
    private val media by lazy { MediaStoreFixtures(env.context) }

    @Before fun grant() = media.grantMediaPermissions()

    @After fun clean() {
        media.deleteAll()
        runBlocking { settings.forgetAccount(env.accountName) }
    }

    private fun loaded(intent: Intent) = ShareUploadViewModel(env.context, env.graph.accounts(), settings, env.graph.documents(), env.graph.diskCache()).also {
        it.load(intent)
        awaitTrue("the share to load") { it.state.value.ready }
    }

    private fun text(body: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)

    @Test
    fun aSharedPhotoIsUploadedUnderItsOwnName() {
        val photo = media.photo("holiday.jpg", ByteArray(64) { it.toByte() })
        val vm = loaded(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, photo.uri))
        assertEquals("holiday.jpg", vm.state.value.items.single().name)

        vm.upload()

        awaitTrue("the photo to arrive on the server", timeoutMs = 30_000) { env.nextcloud.bytes("/holiday.jpg")?.size == 64 }
        assertEquals(SharePhase.Queued(1), vm.state.value.phase)
    }

    @Test
    fun anExistingNameIsNotOverwritten() {
        env.nextcloud.putFile("/Shared text.txt", "mine")
        val vm = loaded(text("theirs"))

        vm.upload()

        awaitTrue("the second copy to arrive", timeoutMs = 30_000) { env.nextcloud.text("/Shared text (1).txt") == "theirs" }
        assertEquals("mine", env.nextcloud.text("/Shared text.txt"))
    }
}
