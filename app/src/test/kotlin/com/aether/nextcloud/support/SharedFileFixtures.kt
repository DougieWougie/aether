package com.aether.nextcloud.support

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import org.robolectric.Robolectric

/**
 * Stands in for the content another app shares (a photo from a gallery, say): `content://` items that answer the display name,
 * the type and the bytes, which is all the share sheet reads. Robolectric has no MediaStore, and the share sheet does not need one.
 */
class SharedFileFixtures(private val context: Context) {
    class Item(val uri: Uri, val name: String, val bytes: ByteArray)

    init {
        files.clear()
        Robolectric.buildContentProvider(Provider::class.java).create(AUTHORITY)
    }

    fun photo(name: String, bytes: ByteArray, mimeType: String = "image/jpeg"): Item {
        files[name] = bytes to mimeType
        return Item(Uri.parse("content://$AUTHORITY/$name"), name, bytes)
    }

    /** The item is gone for good, as when its owner deletes it after sharing. */
    fun deleteAll() = files.clear()

    class Provider : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            val name = uri.lastPathSegment ?: return null
            if (!files.containsKey(name)) return null
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
        }

        override fun getType(uri: Uri): String? = files[uri.lastPathSegment]?.second

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val (bytes, _) = files[uri.lastPathSegment] ?: throw FileNotFoundException("$uri is gone")
            val file = File.createTempFile("shared", ".bin", context!!.cacheDir).apply { writeBytes(bytes); deleteOnExit() }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private companion object {
        const val AUTHORITY = "com.aether.nextcloud.test.shared"
        val files = ConcurrentHashMap<String, Pair<ByteArray, String>>()
    }
}
