package com.aether.nextcloud.provider

import android.content.ContentResolver
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.awaitTrue
import java.io.FileNotFoundException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The provider's less travelled paths: breadcrumbs, thumbnails, failures, permissions and quota. */
@RunWith(AndroidJUnit4::class)
class AetherDocumentsProviderEdgeCasesTest {
    @get:Rule val env = AetherTestRule()

    @After fun clearIssue() = runBlocking { env.graph.authIssues().resolve(env.accountName) }

    private fun tree(path: String): Uri = DocumentsContract.buildTreeDocumentUri(AetherConstants.AUTHORITY, env.docId(path))

    private fun inTree(treePath: String, path: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree(treePath), env.docId(path))

    private fun listed(path: String) {
        awaitTrue("$path to be listed") {
            env.resolver.query(env.childrenUri(path), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use { it.count > 0 }
        }
    }

    private fun thumbnail(path: String): ByteArray? = env.resolver.openTypedAssetFileDescriptor(
        env.documentUri(path), "image/*", Bundle().apply { putParcelable(ContentResolver.EXTRA_SIZE, Point(256, 256)) },
    )?.createInputStream()?.use { it.readBytes() }

    // region paths and ancestry

    @Test
    fun aTreeOnTheAccountRootGivesABreadcrumbDownToTheDocument() {
        val path = DocumentsContract.findDocumentPath(env.resolver, inTree("/", "/Docs/a.txt"))

        assertNotNull(path)
        assertNull(path!!.rootId)
        assertEquals(listOf(env.docId("/"), env.docId("/Docs"), env.docId("/Docs/a.txt")), path.path)
    }

    @Test
    fun insideATreeTheBreadcrumbStartsAtTheTreeRoot() {
        val path = DocumentsContract.findDocumentPath(env.resolver, inTree("/Docs", "/Docs/Inner/a.txt"))

        assertNull(path!!.rootId)
        assertEquals(listOf(env.docId("/Docs"), env.docId("/Docs/Inner"), env.docId("/Docs/Inner/a.txt")), path.path)
    }

    @Test
    fun isChildDocumentFollowsTheFolderHierarchy() {
        val docs = env.documentUri("/Docs")

        assertTrue(DocumentsContract.isChildDocument(env.resolver, docs, env.documentUri("/Docs/Inner/a.txt")))
        assertFalse("a folder is not its own child", DocumentsContract.isChildDocument(env.resolver, docs, docs))
        assertFalse(DocumentsContract.isChildDocument(env.resolver, docs, env.documentUri("/Elsewhere/a.txt")))
        assertFalse(DocumentsContract.isChildDocument(env.resolver, docs, DocumentsContract.buildDocumentUri(AetherConstants.AUTHORITY, "garbage")))
    }

    // endregion

    // region queries

    @Test
    fun theRootDocumentExistsBeforeAnythingHasBeenListed() {
        env.resolver.query(env.documentUri("/"), null, null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(env.accountName, c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)))
            assertEquals(Document.MIME_TYPE_DIR, c.getString(c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)))
        }
    }

    @Test
    fun aDocumentNotYetCachedIsFetchedOnDemand() {
        env.nextcloud.putFile("/fresh.txt", "12345")

        env.resolver.query(env.documentUri("/fresh.txt"), null, null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("fresh.txt", c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)))
            assertEquals(5L, c.getLong(c.getColumnIndexOrThrow(Document.COLUMN_SIZE)))
        }
    }

    @Test
    fun aMissingDocumentYieldsNothing() {
        val cursor = runCatching { env.resolver.query(env.documentUri("/nope.txt"), null, null, null, null) }.getOrNull()

        assertTrue(cursor == null || cursor.count == 0)
        cursor?.close()
    }

    @Test
    fun aMalformedDocumentIdYieldsNothing() {
        val uri = DocumentsContract.buildDocumentUri(AetherConstants.AUTHORITY, "not-a-document-id")

        val cursor = runCatching { env.resolver.query(uri, null, null, null, null) }.getOrNull()

        assertTrue(cursor == null || cursor.count == 0)
        cursor?.close()
    }

    @Test
    fun aFailedListingReachesDocumentsUiAsAnErrorAndIsNotRetriedStraightAway() {
        env.nextcloud.forcedStatus = 500
        env.resolver.query(env.childrenUri("/"), null, null, null, null)!!.close()

        awaitTrue("the error to be reported") {
            env.resolver.query(env.childrenUri("/"), null, null, null, null)!!.use { it.extras.containsKey(DocumentsContract.EXTRA_ERROR) }
        }
        val propfinds = env.nextcloud.requestsMatching("PROPFIND").size
        env.resolver.query(env.childrenUri("/"), null, null, null, null)!!.use {
            assertFalse("the error replaces the loading flag", it.extras.getBoolean(DocumentsContract.EXTRA_LOADING))
        }

        assertEquals("a recent failure backs off instead of hammering the server", propfinds, env.nextcloud.requestsMatching("PROPFIND").size)
    }

    @Test
    fun aRejectedPasswordWhileListingFlagsTheAccountForSignIn() {
        env.nextcloud.forcedStatus = 401
        env.resolver.query(env.childrenUri("/"), null, null, null, null)!!.close()

        awaitTrue("the account to be flagged") { runBlocking { env.graph.authIssues().accounts.first() }.contains(env.accountName) }
    }

    @Test
    fun theRootReportsCapacityAndFreeSpaceOnceTheQuotaArrives() {
        val roots = DocumentsContract.buildRootsUri(AetherConstants.AUTHORITY)
        env.nextcloud.quotaUsed = 25_000_000
        env.nextcloud.quotaTotal = 100_000_000

        awaitTrue("the quota to appear") {
            env.resolver.query(roots, null, null, null, null)!!.use { c ->
                c.moveToFirst() && c.getLong(c.getColumnIndexOrThrow(Root.COLUMN_CAPACITY_BYTES)) == 100_000_000L
            }
        }
        env.resolver.query(roots, null, null, null, null)!!.use { c ->
            c.moveToFirst()
            assertEquals(75_000_000L, c.getLong(c.getColumnIndexOrThrow(Root.COLUMN_AVAILABLE_BYTES)))
        }
    }

    // endregion

    // region content

    @Test
    fun aThumbnailIsDownloadedOnceThenServedFromTheCache() {
        env.nextcloud.putFile("/photo.jpg", "jpeg-bytes", mime = "image/jpeg")
        env.nextcloud.withPreview("/photo.jpg")
        listed("/")

        val first = thumbnail("/photo.jpg")
        val second = thumbnail("/photo.jpg")

        assertTrue(String(first!!).startsWith("PREVIEW-"))
        assertEquals(String(first), String(second!!))
        assertEquals(1, env.nextcloud.requests.count { it.path.orEmpty().startsWith("/index.php/core/preview") })
    }

    @Test
    fun aDocumentWithoutAPreviewHasNoThumbnail() {
        env.nextcloud.putFile("/plain.txt", "x")
        listed("/")

        try {
            thumbnail("/plain.txt")
            fail("expected no thumbnail")
        } catch (_: FileNotFoundException) {
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun aReadOnlyFileCannotBeOpenedForWriting() {
        env.nextcloud.putFile("/shared.txt", "look but do not touch")
        env.nextcloud.makeReadOnly("/shared.txt")
        listed("/")

        try {
            env.resolver.openOutputStream(env.documentUri("/shared.txt"), "wt")!!.close()
            fail("expected the write to be refused")
        } catch (_: SecurityException) {
        }
        assertTrue(env.nextcloud.requestsMatching("PUT").isEmpty())
    }

    @Test
    fun openingForReadWithARejectedPasswordAsksForSignIn() {
        env.nextcloud.putFile("/a.txt", "a")
        listed("/")
        env.nextcloud.forcedStatus = 401

        try {
            env.resolver.openInputStream(env.documentUri("/a.txt"))!!.close()
            fail("expected an authentication failure")
        } catch (_: SecurityException) {
            // AuthenticationRequiredException is a SecurityException
        }
        awaitTrue("the account to be flagged") { runBlocking { env.graph.authIssues().accounts.first() }.contains(env.accountName) }
    }

    @Test
    fun aServerFailureWhileReadingIsNotReportedAsAMissingFile() {
        env.nextcloud.putFile("/a.txt", "a")
        listed("/")
        env.nextcloud.forcedStatus = 500

        val error = runCatching { env.resolver.openInputStream(env.documentUri("/a.txt"))!!.close() }.exceptionOrNull()

        assertNotNull(error)
        assertFalse("$error", error is FileNotFoundException)
    }

    @Test
    fun aMissingFileCannotBeOpened() {
        try {
            env.resolver.openInputStream(env.documentUri("/missing.txt"))!!.close()
            fail("expected FileNotFoundException")
        } catch (_: FileNotFoundException) {
        }
    }

    @Test
    fun openingForReadWriteAndClosingWithoutChangesUploadsNothing() {
        env.nextcloud.putFile("/a.txt", "unchanged")
        listed("/")

        env.resolver.openFileDescriptor(env.documentUri("/a.txt"), "rw")!!.close()

        Thread.sleep(2_000)
        assertTrue("an untouched file must not be re-uploaded", env.nextcloud.requestsMatching("PUT").isEmpty())
        assertEquals("unchanged", env.nextcloud.text("/a.txt"))
    }

    @Test
    fun appendingKeepsTheExistingBytesAndAddsToThem() {
        env.nextcloud.putFile("/log.txt", "line1\n")
        listed("/")

        env.resolver.openOutputStream(env.documentUri("/log.txt"), "wa")!!.use { it.write("line2\n".toByteArray()) }

        awaitTrue("the appended file to arrive") { env.nextcloud.text("/log.txt") == "line1\nline2\n" }
    }

    // endregion

    // region mutations

    @Test
    fun anAccountRootCannotBeDeletedOrRenamed() {
        assertTrue(runCatching { DocumentsContract.deleteDocument(env.resolver, env.documentUri("/")) }.exceptionOrNull() is SecurityException)
        assertTrue(runCatching { DocumentsContract.renameDocument(env.resolver, env.documentUri("/"), "Renamed") }.exceptionOrNull() is SecurityException)
    }

    @Test
    fun aDocumentCannotBeMovedToAnotherAccount() {
        env.nextcloud.putFile("/a.txt", "a")
        listed("/")
        val elsewhere = DocumentsContract.buildDocumentUri(AetherConstants.AUTHORITY, "other@elsewhere:/")

        val error = runCatching { DocumentsContract.moveDocument(env.resolver, env.documentUri("/a.txt"), env.documentUri("/"), elsewhere) }.exceptionOrNull()

        assertTrue("expected a refusal, got $error", error is FileNotFoundException)
        assertTrue(env.nextcloud.exists("/a.txt"))
    }

    @Test
    fun creatingAFileThatExistsPicksAFreeName() {
        env.nextcloud.putFile("/notes.md", "old")
        listed("/")

        val created = DocumentsContract.createDocument(env.resolver, env.documentUri("/"), "text/markdown", "notes.md")

        assertEquals(env.docId("/notes (1).md"), DocumentsContract.getDocumentId(created!!))
        assertTrue(env.nextcloud.exists("/notes (1).md"))
        assertEquals("old", env.nextcloud.text("/notes.md"))
    }

    @Test
    fun renamingReturnsTheNewDocumentId() {
        env.nextcloud.putFile("/old.txt", "x")
        listed("/")

        val renamed = DocumentsContract.renameDocument(env.resolver, env.documentUri("/old.txt"), "new.txt")

        assertEquals(env.docId("/new.txt"), DocumentsContract.getDocumentId(renamed!!))
        assertTrue(env.nextcloud.exists("/new.txt"))
    }

    // endregion
}
