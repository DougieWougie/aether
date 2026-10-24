package com.aether.nextcloud.support

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry

/** Creates and removes MediaStore items owned by the app, so camera backup has something real to scan. */
class MediaStoreFixtures(private val context: Context) {
    private val created = mutableListOf<Uri>()

    class Item(val uri: Uri, val name: String, val bucketId: Long, val bytes: ByteArray)

    fun grantMediaPermissions() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED").forEach {
            runCatching { automation.grantRuntimePermission(context.packageName, it) }
        }
    }

    fun photo(name: String, bytes: ByteArray, album: String = "AetherTest"): Item =
        insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, name, "image/jpeg", "Pictures/$album", bytes)

    fun video(name: String, bytes: ByteArray, album: String = "AetherTest"): Item =
        insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, name, "video/mp4", "Movies/$album", bytes)

    private fun insert(collection: Uri, name: String, mime: String, relativePath: String, bytes: ByteArray): Item {
        val resolver = context.contentResolver
        val uri = resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        )!!
        created += uri
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val bucket = resolver.query(uri, arrayOf(MediaStore.MediaColumns.BUCKET_ID), null, null, null)!!.use { it.moveToFirst(); it.getLong(0) }
        return Item(uri, name, bucket, bytes)
    }

    fun deleteAll() {
        created.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        created.clear()
    }
}
