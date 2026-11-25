package com.aether.nextcloud.support

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract

/** Fills the test APK's `TreeFixtureProvider` (another process) and names the folder an `OpenDocumentTree` pick would return. */
class TreeFixtures(private val context: Context) {
    val tree: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "Holiday")

    fun reset() = call("reset")
    fun clear() = call("clear")
    fun folder(path: String) = call("folder", path)

    fun file(path: String, bytes: ByteArray, mime: String = "application/octet-stream", displayName: String? = null) =
        call("file", path, Bundle().apply { putByteArray("bytes", bytes); putString("mime", mime); putString("name", displayName) })

    private fun call(method: String, arg: String? = null, extras: Bundle? = null) {
        checkNotNull(context.contentResolver.call(AUTHORITY, method, arg, extras)) { "TreeFixtureProvider is not registered" }
    }

    private companion object { const val AUTHORITY = "com.aether.nextcloud.test.tree" }
}
