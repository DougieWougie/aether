package com.aether.nextcloud.support

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

/**
 * A folder tree served with the document-tree URIs and columns a picked folder has, for tests of folder uploads. It is a plain
 * `ContentProvider` (`DocumentsProvider` is not usable under Robolectric). Document ids are slash-separated paths below [ROOT];
 * registered under Robolectric by `FakeTreeProvider`. (The instrumented tests have their own Java provider, as a provider in the test APK runs in another process.)
 */
class TreeFixtureProvider : ContentProvider() {
    private class Node(val bytes: ByteArray?, val mime: String, val displayName: String? = null)

    override fun onCreate() = true

    private fun row(cursor: MatrixCursor, id: String) {
        val node = nodes[id] ?: throw FileNotFoundException(id)
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, node.displayName ?: id.substringAfterLast('/'))
            .add(Document.COLUMN_MIME_TYPE, node.mime)
            .add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, node.bytes?.size?.toLong())
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val segments = uri.pathSegments
        val documentId = segments[segments.indexOf("document") + 1]
        val cursor = MatrixCursor(projection ?: DEFAULT)
        if (segments.last() == "children") {
            nodes.keys.filter { it.startsWith("$documentId/") && '/' !in it.removePrefix("$documentId/") }.sorted().forEach { row(cursor, it) }
        } else {
            row(cursor, documentId)
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = nodes[uri.pathSegments[uri.pathSegments.indexOf("document") + 1]]?.mime

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = uri.pathSegments[uri.pathSegments.indexOf("document") + 1]
        val bytes = nodes[id]?.bytes ?: throw FileNotFoundException(id)
        val file = File.createTempFile("tree", ".bin", context!!.cacheDir).apply { writeBytes(bytes); deleteOnExit() }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.aether.nextcloud.test.tree"
        const val ROOT = "Holiday"
        private val DEFAULT = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE)
        private val nodes = ConcurrentHashMap<String, Node>()

        /** The URI `OpenDocumentTree` would return for [ROOT]. */
        val tree: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT)

        /** Starts a fresh tree holding only the empty [ROOT] folder. */
        fun reset() {
            nodes.clear()
            nodes[ROOT] = Node(null, Document.MIME_TYPE_DIR)
        }

        fun folder(path: String) { nodes["$ROOT/$path"] = Node(null, Document.MIME_TYPE_DIR) }

        fun file(path: String, bytes: ByteArray, mime: String = "application/octet-stream", displayName: String? = null) {
            nodes["$ROOT/$path"] = Node(bytes, mime, displayName)
        }

        /** Forgets every node; safe to call whether or not the provider was ever used. */
        fun clear() = nodes.clear()
    }
}
