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
        listOf(
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.ACCESS_MEDIA_LOCATION",
        ).forEach {
            runCatching { automation.grantRuntimePermission(context.packageName, it) }
        }
    }

    fun photo(name: String, bytes: ByteArray, album: String = "AetherTest"): Item =
        insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, name, "image/jpeg", "Pictures/$album", bytes)

    fun video(name: String, bytes: ByteArray, album: String = "AetherTest"): Item =
        insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, name, "video/mp4", "Movies/$album", bytes)

    private val sharedFiles = mutableListOf<String>()

    /** Runs [script] in a real `sh` (the plain executeShellCommand does no quoting or piping) and returns its output. */
    private fun shell(automation: android.app.UiAutomation, script: String): String {
        val descriptors = automation.executeShellCommandRw("sh")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { it.write((script + "\n").toByteArray()) }
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { it.readBytes().decodeToString() }
    }

    /**
     * A photo that another app (the shell) put on shared storage and that was then scanned into MediaStore, like one from the
     * camera app. MediaStore redacts EXIF location from files the caller does not own, so unlike [photo] this one is redacted
     * unless the caller holds ACCESS_MEDIA_LOCATION and asks for the original.
     */
    fun photoFromAnotherApp(name: String, bytes: ByteArray, album: String = "AetherTest"): Item {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = shell(automation, command)
        val directory = "/sdcard/Pictures/$album"
        val path = "$directory/$name"
        shell("mkdir -p '$directory'")
        // The shell writes the file, so the app is not its owner (and MediaStore will redact what the app may not see).
        shell("echo '${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}' | base64 -d > '$path'")
        sharedFiles += path
        // The app cannot scan a file it does not own, so the shell asks the media scanner to index it.
        shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file://$path'")
        val deadline = System.currentTimeMillis() + 10_000
        var found: Uri? = null
        while (found == null && System.currentTimeMillis() < deadline) {
            found = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(name, "Pictures/$album/"), null,
            )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }
            if (found == null) Thread.sleep(100)
        }
        val uri = checkNotNull(found) { "MediaStore did not index $path; ls: ${shell(automation, "ls -la '$directory' 2>&1")}" }
        created += uri
        val bucket = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.BUCKET_ID), null, null, null)!!.use { it.moveToFirst(); it.getLong(0) }
        return Item(uri, name, bucket, bytes)
    }

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
        if (sharedFiles.isNotEmpty()) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            sharedFiles.forEach { path -> runCatching { shell(automation, "rm -f '$path'") } }
            sharedFiles.clear()
        }
    }
}
