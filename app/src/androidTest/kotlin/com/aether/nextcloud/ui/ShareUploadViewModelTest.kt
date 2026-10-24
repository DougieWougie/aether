package com.aether.nextcloud.ui

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.MediaStoreFixtures
import com.aether.nextcloud.support.awaitTrue
import com.aether.nextcloud.ui.share.SharePhase
import com.aether.nextcloud.ui.share.ShareUploadViewModel
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The share sheet's logic with the app's real repositories, WorkManager and a fake server. */
@RunWith(AndroidJUnit4::class)
class ShareUploadViewModelTest {
    @get:Rule val env = AetherTestRule()

    private val settings by lazy { SettingsRepository(env.context) }
    private val media by lazy { MediaStoreFixtures(env.context) }

    @Before fun grant() = media.grantMediaPermissions()

    @After fun clean() {
        media.deleteAll()
        runBlocking { settings.forgetAccount(env.accountName) }
    }

    private fun viewModel() = ShareUploadViewModel(env.context, env.graph.accounts(), settings, env.graph.documents(), env.graph.diskCache())

    private fun loaded(intent: Intent) = viewModel().also {
        it.load(intent)
        awaitTrue("the share to load") { it.state.value.ready }
    }

    private fun text(body: String, subject: String? = null) = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, body).apply { subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) } }

    @Test
    fun sharedTextBecomesATextFileNamedAfterTheSubject() {
        val vm = loaded(text("remember the milk", subject = "Shopping/list"))

        val item = vm.state.value.items.single()
        assertEquals("Shopping-list.txt", item.name)
        assertEquals("text/plain", item.mimeType)
        assertEquals("remember the milk", item.text)
    }

    @Test
    fun theDestinationDefaultsToTheFirstAccountsRoot() {
        val state = loaded(text("hi")).state.value

        assertEquals(DocumentId.root(env.accountName), state.destination)
        assertEquals(env.accountName, state.account?.accountName)
    }

    @Test
    fun aTappedDirectShareTargetNamesItsFolder() {
        val folder = DocumentId(env.accountName, "/Photos")

        val state = loaded(text("hi").putExtra(Intent.EXTRA_SHORTCUT_ID, folder.toString())).state.value

        assertEquals(folder, state.destination)
    }

    @Test
    fun aShortcutForAnAccountThatIsGoneFallsBackToTheRoot() {
        val state = loaded(text("hi").putExtra(Intent.EXTRA_SHORTCUT_ID, "ghost@nowhere:/Docs")).state.value

        assertEquals(DocumentId.root(env.accountName), state.destination)
    }

    @Test
    fun theLastUsedFolderIsOfferedAsRecentAndPreferred() {
        val recent = DocumentId(env.accountName, "/Work")
        runBlocking { settings.rememberFolder(recent.toString()) }

        val state = loaded(text("hi")).state.value

        assertEquals(recent, state.destination)
        assertEquals(listOf(recent), state.recentFolders)
    }

    @Test
    fun fileUrisAreRefusedSoASenderCannotMakeAetherReadItsPrivateStorage() {
        val private = File(env.context.filesDir, "secret.txt").apply { writeText("secret") }
        val intent = Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(private))

        assertTrue(loaded(intent).state.value.items.isEmpty())
    }

    @Test
    fun aUriFromAetherItselfIsNotShareable() {
        val own = Uri.parse("content://${AetherConstants.AUTHORITY}/document/x")
        val ownWithUserId = Uri.parse("content://0@${AetherConstants.AUTHORITY}/document/x")
        val shouty = Uri.parse("content://${AetherConstants.AUTHORITY.uppercase()}/document/x")

        listOf(own, ownWithUserId, shouty).forEach { uri ->
            val intent = Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, uri)
            assertTrue("$uri must not be accepted", loaded(intent).state.value.items.isEmpty())
        }
    }

    @Test
    fun anUnsupportedActionSharesNothing() {
        assertTrue(loaded(Intent(Intent.ACTION_VIEW)).state.value.items.isEmpty())
    }

    @Test
    fun loadingTwiceKeepsTheFirstResult() {
        val vm = loaded(text("first"))

        vm.load(text("second"))

        assertEquals("first", vm.state.value.items.single().text)
    }

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

    @Test
    fun uploadingRemembersTheFolderForNextTime() {
        val vm = loaded(text("note"))
        vm.selectFolder(DocumentId(env.accountName, "/Notes"))
        env.nextcloud.mkdirs("/Notes")

        vm.upload()

        awaitTrue("the folder to be remembered") { runBlocking { settings.recentFolders.first() }.any { it.endsWith(":/Notes") } }
    }

    @Test
    fun nothingHappensWithoutItemsOrADestination() {
        val vm = loaded(Intent(Intent.ACTION_VIEW))

        vm.upload()

        assertEquals(SharePhase.Ready, vm.state.value.phase)
        assertTrue(env.nextcloud.requestsMatching("PUT").isEmpty())
    }

    @Test
    fun selectingAnAccountMovesTheDestinationToItsRootAndUnknownOnesAreIgnored() {
        val vm = loaded(text("hi"))

        vm.selectAccount("ghost@nowhere")

        assertEquals(DocumentId.root(env.accountName), vm.state.value.destination)
    }

    @Test
    fun aFolderFromAnotherAppIsRejectedWithAnExplanation() {
        val vm = loaded(text("hi"))

        vm.onFolderPicked(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocs"))

        val phase = vm.state.value.phase
        assertTrue(phase is SharePhase.Failed)
        vm.dismissError()
        assertEquals(SharePhase.Ready, vm.state.value.phase)
    }

    @Test
    fun cancellingThePickerChangesNothing() {
        val vm = loaded(text("hi"))
        val before = vm.state.value

        vm.onFolderPicked(null)

        assertEquals(before, vm.state.value)
        assertNull((vm.state.value.phase as? SharePhase.Failed))
        assertNotNull(vm.state.value.destination)
    }
}
