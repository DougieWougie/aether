package com.aether.nextcloud.support

import android.net.Uri
import org.robolectric.Robolectric

/** [TreeFixtureProvider] registered under Robolectric, for JVM tests of picked-folder uploads. */
class FakeTreeProvider {
    init {
        TreeFixtureProvider.reset()
        Robolectric.buildContentProvider(TreeFixtureProvider::class.java).create(TreeFixtureProvider.AUTHORITY)
    }

    val tree: Uri = TreeFixtureProvider.tree

    fun folder(path: String) = TreeFixtureProvider.folder(path)

    fun file(path: String, bytes: ByteArray, mime: String = "application/octet-stream", displayName: String? = null) =
        TreeFixtureProvider.file(path, bytes, mime, displayName)

    companion object {
        fun clear() = TreeFixtureProvider.clear()
    }
}
