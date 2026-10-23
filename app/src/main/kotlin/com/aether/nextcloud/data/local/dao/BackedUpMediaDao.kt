package com.aether.nextcloud.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aether.nextcloud.data.local.entity.BackedUpMediaEntity
import kotlinx.coroutines.flow.Flow

/** Backed-up items of one kind (photos or videos) and their combined size. */
data class MediaTotals(val isVideo: Boolean, val count: Int, val bytes: Long)

@Dao
interface BackedUpMediaDao {
    @Query("SELECT * FROM backed_up_media WHERE accountName = :account AND mediaId = :mediaId AND isVideo = :isVideo")
    fun find(account: String, mediaId: Long, isVideo: Boolean): BackedUpMediaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: BackedUpMediaEntity)

    @Query("SELECT COUNT(*) FROM backed_up_media WHERE accountName = :account")
    fun observeCount(account: String): Flow<Int>

    @Query("SELECT isVideo, COUNT(*) AS count, COALESCE(SUM(size), 0) AS bytes FROM backed_up_media WHERE accountName = :account GROUP BY isVideo")
    fun observeTotals(account: String): Flow<List<MediaTotals>>

    @Query("SELECT * FROM backed_up_media WHERE accountName = :account ORDER BY backedUpAt DESC LIMIT :limit")
    fun observeRecent(account: String, limit: Int): Flow<List<BackedUpMediaEntity>>

    @Query("SELECT MAX(backedUpAt) FROM backed_up_media WHERE accountName = :account")
    fun observeLastBackupAt(account: String): Flow<Long?>

    @Query("DELETE FROM backed_up_media WHERE accountName = :account")
    fun deleteAccount(account: String)
}
