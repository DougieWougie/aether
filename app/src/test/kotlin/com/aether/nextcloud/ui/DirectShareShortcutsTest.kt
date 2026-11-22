package com.aether.nextcloud.ui

import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.ui.share.DirectShareShortcuts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectShareShortcutsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val photos = DocumentId("alice@h", "/Photos")
    private val root = DocumentId.root("alice@h")

    @After fun clear() = DirectShareShortcuts.clear(context)

    private fun published() = ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }

    @Test
    fun foldersArePublishedInOrderWithTheDocumentIdAsShortcutId() {
        DirectShareShortcuts.publish(context, listOf(photos, root))

        assertEquals(listOf(photos.toString(), root.toString()), published().map { it.id })
        assertEquals("Photos", published()[0].shortLabel)
        assertEquals("Nextcloud", published()[1].shortLabel)
        assertTrue(published().all { DirectShareShortcuts.CATEGORY in it.categories.orEmpty() })
    }

    @Test
    fun publishingAgainReplacesWhatWasThere() {
        DirectShareShortcuts.publish(context, listOf(photos, root))

        DirectShareShortcuts.publish(context, listOf(root))

        assertEquals(listOf(root.toString()), published().map { it.id })
    }

    @Test
    fun syncWithTheSettingOffRemovesEveryFolderName() {
        DirectShareShortcuts.publish(context, listOf(photos))

        DirectShareShortcuts.sync(context, enabled = false, folders = listOf(photos))

        assertTrue(published().isEmpty())
    }

    @Test
    fun syncWithTheSettingOnPublishes() {
        DirectShareShortcuts.sync(context, enabled = true, folders = listOf(photos))

        assertEquals(1, published().size)
    }
}
