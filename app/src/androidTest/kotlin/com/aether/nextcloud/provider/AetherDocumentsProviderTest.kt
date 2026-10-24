package com.aether.nextcloud.provider

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.awaitTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The SAF provider driven through [android.content.ContentResolver], as DocumentsUI and other apps use it. */
@RunWith(AndroidJUnit4::class)
class AetherDocumentsProviderTest {
    @get:Rule val env = AetherTestRule()

    private fun listNames(path: String): List<String> =
        env.resolver.query(env.childrenUri(path), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun awaitChildren(path: String, expected: Set<String>) =
        awaitTrue("children of $path to be $expected") { listNames(path).toSet() == expected }

    @Test
    fun queryRootsListsTheSignedInAccount() {
        val rootsUri = DocumentsContract.buildRootsUri(AetherConstants.AUTHORITY)
        env.resolver.query(rootsUri, null, null, null, null)!!.use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(env.accountName, c.getString(c.getColumnIndexOrThrow(Root.COLUMN_ROOT_ID)))
            assertEquals(env.docId("/"), c.getString(c.getColumnIndexOrThrow(Root.COLUMN_DOCUMENT_ID)))
            val flags = c.getInt(c.getColumnIndexOrThrow(Root.COLUMN_FLAGS))
            assertTrue(flags and Root.FLAG_SUPPORTS_CREATE != 0)
            assertTrue(flags and Root.FLAG_SUPPORTS_IS_CHILD != 0)
            // A single account shows only the host, never the login name.
            assertFalse(c.getString(c.getColumnIndexOrThrow(Root.COLUMN_SUMMARY)).contains("alice"))
        }
    }

    @Test
    fun listChildrenFillsInFromTheServer() {
        env.nextcloud.putFile("/Docs/a.txt", "a")
        env.nextcloud.putFile("/Docs/b.txt", "bb")
        env.nextcloud.mkdirs("/Docs/Sub")

        // The first query answers from the empty cache and refreshes in the background.
        listNames("/Docs")
        awaitChildren("/Docs", setOf("a.txt", "b.txt", "Sub"))

        env.resolver.query(env.childrenUri("/Docs"), arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE), null, null, null)!!.use { c ->
            val byName = buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1) to c.getLong(2)) }
            assertEquals(Document.MIME_TYPE_DIR, byName.getValue("Sub").first)
            assertEquals("text/plain" to 2L, byName.getValue("b.txt"))
        }
    }

    @Test
    fun openForReadStreamsTheRemoteBytes() {
        env.nextcloud.putFile("/hello.txt", "hello from the server")
        listNames("/")
        awaitChildren("/", setOf("hello.txt"))

        val text = env.resolver.openInputStream(env.documentUri("/hello.txt"))!!.use { it.readBytes().decodeToString() }

        assertEquals("hello from the server", text)
    }

    @Test
    fun openForWriteUploadsWhenTheDescriptorCloses() {
        env.nextcloud.putFile("/note.txt", "old")
        listNames("/")
        awaitChildren("/", setOf("note.txt"))
        val oldEtag = env.nextcloud.etag("/note.txt")

        env.resolver.openOutputStream(env.documentUri("/note.txt"), "wt")!!.use { it.write("new text".toByteArray()) }

        awaitTrue("the edit to reach the server") { env.nextcloud.text("/note.txt") == "new text" }
        val put = env.nextcloud.requestsMatching("PUT", "/note.txt").single()
        assertEquals("\"$oldEtag\"", put.getHeader("If-Match"))
    }

    @Test
    fun createDocumentMakesFilesAndFolders() {
        val folder = DocumentsContract.createDocument(env.resolver, env.documentUri("/"), Document.MIME_TYPE_DIR, "Photos")!!
        val file = DocumentsContract.createDocument(env.resolver, folder, "text/plain", "todo.txt")!!

        assertTrue(env.nextcloud.exists("/Photos"))
        assertEquals("", env.nextcloud.text("/Photos/todo.txt"))
        assertEquals(env.docId("/Photos/todo.txt"), DocumentsContract.getDocumentId(file))
    }

    @Test
    fun renameMoveAndDeleteReachTheServer() {
        env.nextcloud.putFile("/Inbox/draft.txt", "keep me")
        env.nextcloud.mkdirs("/Archive")
        listNames("/Inbox")
        awaitChildren("/Inbox", setOf("draft.txt"))
        listNames("/")
        awaitChildren("/", setOf("Inbox", "Archive"))

        val renamed = DocumentsContract.renameDocument(env.resolver, env.documentUri("/Inbox/draft.txt"), "final.txt")!!
        assertEquals("keep me", env.nextcloud.text("/Inbox/final.txt"))
        assertFalse(env.nextcloud.exists("/Inbox/draft.txt"))

        val moved = DocumentsContract.moveDocument(env.resolver, renamed, env.documentUri("/Inbox"), env.documentUri("/Archive"))!!
        assertEquals("keep me", env.nextcloud.text("/Archive/final.txt"))
        assertEquals(env.docId("/Archive/final.txt"), DocumentsContract.getDocumentId(moved))

        assertTrue(DocumentsContract.deleteDocument(env.resolver, moved))
        assertFalse(env.nextcloud.exists("/Archive/final.txt"))
    }

    @Test
    fun aLockedFileFailsTheDeleteWithoutPretendingItIsMissing() {
        env.nextcloud.putFile("/locked.txt", "x")
        listNames("/")
        awaitChildren("/", setOf("locked.txt"))
        env.nextcloud.locked += "/locked.txt"

        val error = runCatching { DocumentsContract.deleteDocument(env.resolver, env.documentUri("/locked.txt")) }.exceptionOrNull()

        assertTrue("expected a failure, got $error", error != null)
        assertTrue("a lock must not look like a missing file: $error", error !is java.io.FileNotFoundException)
        assertTrue(env.nextcloud.exists("/locked.txt"))
    }

    @Test
    fun aDocumentIdFromAnotherAccountYieldsNoRows() {
        val uri: Uri = DocumentsContract.buildDocumentUri(AetherConstants.AUTHORITY, "nobody@elsewhere.example:/x.txt")

        // Binder cannot carry FileNotFoundException for a query, so the resolver either throws or hands back no cursor.
        val cursor = runCatching { env.resolver.query(uri, null, null, null, null) }.getOrNull()
        try {
            assertTrue("an unknown account must not produce a row", cursor == null || cursor.count == 0)
        } finally {
            cursor?.close()
        }
    }
}
