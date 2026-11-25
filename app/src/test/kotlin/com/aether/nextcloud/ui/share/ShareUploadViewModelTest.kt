package com.aether.nextcloud.ui.share

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.support.FakeTreeProvider
import com.aether.nextcloud.support.RobolectricAetherRule
import com.aether.nextcloud.support.SharedFileFixtures
import com.aether.nextcloud.support.awaitTrue
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The share sheet's logic with the app's real repositories, WorkManager and a fake server. */
@RunWith(AndroidJUnit4::class)
class ShareUploadViewModelTest {
    @get:Rule val env = RobolectricAetherRule()

    private val settings by lazy { SettingsRepository(env.context) }
    private val media by lazy { SharedFileFixtures(env.context) }

    private val tree by lazy { FakeTreeProvider() }

    @After fun clean() {
        media.deleteAll()
        FakeTreeProvider.clear()
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

    private fun folderIntent() = Intent(ShareUploadViewModel.ACTION_UPLOAD_FOLDER).setData(tree.tree)

    @Test
    fun aPickedFolderIsWalkedIntoRelativePathsAndEmptySubfolders() {
        tree.file("a.txt", "a".toByteArray(), "text/plain")
        tree.folder("Day 1")
        tree.file("Day 1/b.jpg", "b".toByteArray(), "image/jpeg")
        tree.folder("Day 1/Raw")
        tree.folder("Empty")

        val state = loaded(folderIntent()).state.value

        assertEquals("Holiday", state.folderName)
        assertEquals(2, state.fileCount)
        assertEquals(
            setOf("a.txt", "Day 1/b.jpg", "Day 1/Raw", "Empty"),
            state.items.map { it.relativePath }.toSet(),
        )
        assertEquals(setOf("Day 1/Raw", "Empty"), state.items.filter { it.isFolder }.map { it.relativePath }.toSet())
    }

    @Test
    fun uploadingAPickedFolderRecreatesItUnderTheDestinationWithoutMergingIntoAnExistingOne() {
        env.nextcloud.mkdirs("/Holiday")
        env.nextcloud.putFile("/Holiday/old.txt", "old")
        tree.file("a.txt", "alpha".toByteArray(), "text/plain")
        tree.folder("Day 1")
        tree.file("Day 1/b.txt", "beta".toByteArray(), "text/plain")
        tree.folder("Empty")
        val vm = loaded(folderIntent())

        vm.upload()

        // The files themselves are sent by WorkManager (covered by the instrumented tests); here the folders are made up front.
        awaitTrue("the uploads to be queued") { vm.state.value.phase is SharePhase.Queued }
        assertEquals(2, (vm.state.value.phase as SharePhase.Queued).count)
        assertTrue(env.nextcloud.exists("/Holiday (1)"))
        assertTrue(env.nextcloud.exists("/Holiday (1)/Empty"))
        assertEquals("old", env.nextcloud.text("/Holiday/old.txt"))
        assertTrue(env.nextcloud.paths().none { it.startsWith("/Holiday/") && it != "/Holiday/old.txt" })
    }

    @Test
    fun folderAndFileNamesFromTheProviderCannotEscapeTheFolder() {
        tree.file("evil", "x".toByteArray(), displayName = "../../etc/passwd")
        tree.file("dots", "y".toByteArray(), displayName = "..")

        val names = loaded(folderIntent()).state.value.items.map { it.relativePath }

        assertTrue(names.none { ".." in it || "etc" in it })
        assertEquals(2, names.size)
    }

    @Test
    fun aTreeFromAetherItselfIsNotUploaded() {
        val own = DocumentsContract.buildTreeDocumentUri(AetherConstants.AUTHORITY, DocumentId.root(env.accountName).toString())

        val state = loaded(Intent(ShareUploadViewModel.ACTION_UPLOAD_FOLDER).setData(own)).state.value

        assertTrue(state.items.isEmpty())
        assertNull(state.folderName)
    }
}
