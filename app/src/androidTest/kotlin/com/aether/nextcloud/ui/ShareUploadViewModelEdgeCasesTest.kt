package com.aether.nextcloud.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.auth.LoginCredentials
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.MediaStoreFixtures
import com.aether.nextcloud.support.awaitTrue
import com.aether.nextcloud.ui.share.SharePhase
import com.aether.nextcloud.ui.share.ShareUploadViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareUploadViewModelEdgeCasesTest {
    @get:Rule val env = AetherTestRule()

    private val settings by lazy { SettingsRepository(env.context) }
    private val media by lazy { MediaStoreFixtures(env.context) }
    private val secondAccount = "bob@other.example.com"

    @Before fun grant() = media.grantMediaPermissions()

    @After
    fun clean() {
        media.deleteAll()
        env.graph.accounts().removeAccount(secondAccount)
        runBlocking { settings.forgetAccount(env.accountName); settings.forgetAccount(secondAccount) }
    }

    private fun viewModel() = ShareUploadViewModel(env.context, env.graph.accounts(), settings, env.graph.documents(), env.graph.diskCache())

    private fun loaded(intent: Intent) = viewModel().also {
        it.load(intent)
        awaitTrue("the share to load") { it.state.value.ready }
    }

    private fun text(body: String = "hi") = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body).putExtra(Intent.EXTRA_SUBJECT, "Note")

    private fun addSecondAccount() {
        env.graph.accounts().addAccount(LoginCredentials("https://other.example.com", "bob", "pw"))
    }

    @Test
    fun switchingToAnotherAccountStartsAtItsRootOrItsLastFolder() {
        addSecondAccount()
        val recent = DocumentId(secondAccount, "/Work")
        runBlocking { settings.rememberFolder(recent.toString()) }
        val vm = loaded(text())

        vm.selectAccount(secondAccount)
        assertEquals(recent, vm.state.value.destination)

        vm.selectAccount(env.accountName)
        assertEquals(DocumentId.root(env.accountName), vm.state.value.destination)
    }

    @Test
    fun aFolderPickedInAetherBecomesTheDestination() {
        val vm = loaded(text())
        val tree = DocumentsContract.buildTreeDocumentUri(AetherConstants.AUTHORITY, env.docId("/Photos"))

        vm.onFolderPicked(tree)

        assertEquals(DocumentId(env.accountName, "/Photos"), vm.state.value.destination)
    }

    @Test
    fun picturesSharedTogetherAreAllListed() {
        val one = media.photo("one.jpg", ByteArray(32) { 1 })
        val two = media.photo("two.jpg", ByteArray(32) { 2 })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf<Uri>(one.uri, two.uri))

        val vm = loaded(intent)

        assertEquals(listOf("one.jpg", "two.jpg"), vm.state.value.items.map { it.name })
    }

    @Test
    fun picturesSharedAsClipDataAreListedToo() {
        val one = media.photo("clip.jpg", ByteArray(32) { 1 })
        val intent = Intent(Intent.ACTION_SEND).setType("image/*").apply { clipData = android.content.ClipData.newRawUri("pic", one.uri) }

        assertEquals(listOf("clip.jpg"), loaded(intent).state.value.items.map { it.name })
    }

    @Test
    fun anItemThatCanNoLongerBeReadFailsTheUploadWithAMessage() {
        val photo = media.photo("vanishing.jpg", ByteArray(32) { 1 })
        val vm = loaded(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, photo.uri))
        media.deleteAll()

        vm.upload()

        awaitTrue("the failure to show") { vm.state.value.phase is SharePhase.Failed }
        assertTrue(env.nextcloud.requestsMatching("PUT").isEmpty())
        vm.dismissError()
        assertEquals(SharePhase.Ready, vm.state.value.phase)
    }

    @Test
    fun anUnreachableServerStillQueuesTheUploadUnderTheOriginalName() {
        val vm = loaded(text("saved for later"))
        env.nextcloud.forcedStatus = 503

        vm.upload()

        awaitTrue("the upload to be queued") { vm.state.value.phase is SharePhase.Queued }
    }
}
