package com.aether.nextcloud.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A MediaStore album (bucket) on the phone and how many photos and videos it holds. */
data class MediaAlbum(val id: Long, val name: String, val count: Int)

/** Lists the albums camera backup can choose from. */
@Singleton
class MediaAlbumRepository @Inject constructor(@ApplicationContext private val context: Context) {

    /** Biggest album first. Empty without media permission; with partial access only the photos shared with Aether are counted. */
    suspend fun albums(includeVideos: Boolean): List<MediaAlbum> = withContext(Dispatchers.IO) {
        val names = HashMap<Long, String>()
        val counts = HashMap<Long, Int>()
        scan(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, names, counts)
        if (includeVideos) scan(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, names, counts)
        counts.map { (id, count) -> MediaAlbum(id, names[id] ?: "Unknown", count) }.sortedWith(compareByDescending<MediaAlbum> { it.count }.thenBy { it.name })
    }

    private fun scan(collection: Uri, names: MutableMap<Long, String>, counts: MutableMap<Long, Int>) {
        val projection = arrayOf(MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.SIZE} > 0"
        try {
            context.contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.isNull(0)) continue
                    val id = cursor.getLong(0)
                    counts[id] = (counts[id] ?: 0) + 1
                    if (id !in names) cursor.getString(1)?.let { names[id] = it }
                }
            }
        } catch (_: SecurityException) {
            // no media permission yet
        }
    }
}
