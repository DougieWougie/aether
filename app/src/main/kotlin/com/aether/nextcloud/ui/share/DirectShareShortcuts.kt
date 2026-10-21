package com.aether.nextcloud.ui.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.aether.nextcloud.R
import com.aether.nextcloud.provider.DocumentId

/**
 * Publishes the most recently used upload folders as Direct Share targets (ADR-0007). The shortcut id is the folder's
 * document id; the system hands it back as `Intent.EXTRA_SHORTCUT_ID` when the user picks the target.
 */
object DirectShareShortcuts {
    const val CATEGORY = "com.aether.nextcloud.category.SHARE_TARGET"

    fun publish(context: Context, folders: List<DocumentId>) {
        val activity = Intent(context, ShareUploadActivity::class.java).setAction(Intent.ACTION_SEND)
        val shortcuts = folders.mapIndexed { rank, folder ->
            ShortcutInfoCompat.Builder(context, folder.toString())
                .setShortLabel(folder.label())
                .setLongLabel("${folder.label()} · ${folder.accountName}")
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_folder))
                .setIntent(activity)
                .setCategories(setOf(CATEGORY))
                .setLongLived(true)
                .setRank(rank)
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    fun DocumentId.label(): String = name.ifEmpty { "Nextcloud" }
}
