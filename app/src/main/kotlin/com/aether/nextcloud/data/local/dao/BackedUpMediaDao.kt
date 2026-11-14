package com.aether.nextcloud.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aether.nextcloud.data.local.entity.BackedUpMediaEntity
import kotlinx.coroutines.flow.Flow

/** Backed-up items of one kind (photos or videos) and their combined size. */
data class MediaTotals(val isVideo: Boolean, val count: Int, val bytes: Long)

/** The identity of one backed-up MediaStore item. */
data class BackedUpKey(val mediaId: Long, val isVideo: Boolean)

/** Items uploaded from one album. */
data class AlbumUploads(val bucketId: Long, val count: Int)

@Dao
interface BackedUpMediaDao {
    /** Every item already backed up for [account], as one query instead of one lookup per MediaStore item on each scan. */
    @Query("SELECT mediaId, isVideo FROM backed_up_media WHERE accountName = :account")
    fun backedUpKeys(account: String): List<BackedUpKey>

    @Query("SELECT * FROM backed_up_media WHERE accountName = :account AND mediaId = :mediaId AND isVideo = :isVideo")
    fun find(account: String, mediaId: Long, isVideo: Boolean): BackedUpMediaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: BackedUpMediaEntity)

    @Query("SELECT COUNT(*) FROM backed_up_media WHERE accountName = :account")
    fun observeCount(account: String): Flow<Int>

    @Query("SELECT isVideo, COUNT(*) AS count, COALESCE(SUM(size), 0) AS bytes FROM backed_up_media WHERE accountName = :account GROUP BY isVideo")
    fun observeTotals(account: String): Flow<List<MediaTotals>>

    @Query("SELECT bucketId, COUNT(*) AS count FROM backed_up_media WHERE accountName = :account AND bucketId IS NOT NULL GROUP BY bucketId")
    fun observeAlbumUploads(account: String): Flow<List<AlbumUploads>>

    @Query("SELECT MAX(backedUpAt) FROM backed_up_media WHERE accountName = :account")
    fun observeLastBackupAt(account: String): Flow<Long?>

    @Query("DELETE FROM backed_up_media WHERE accountName = :account")
    fun deleteAccount(account: String)
}
