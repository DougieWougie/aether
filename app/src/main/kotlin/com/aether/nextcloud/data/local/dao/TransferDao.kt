package com.aether.nextcloud.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aether.nextcloud.data.local.entity.TransferEntity
import com.aether.nextcloud.data.local.entity.TransferState
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Insert
    fun insert(transfer: TransferEntity): Long

    @Query("SELECT * FROM transfers WHERE id = :id")
    fun get(id: Long): TransferEntity?

    /** The row a retried upload left `QUEUED`, so the next attempt can pick it up instead of inserting another. */
    @Query("SELECT id FROM transfers WHERE direction = 'UPLOAD' AND state = 'QUEUED' AND sourcePath = :sourcePath ORDER BY id DESC LIMIT 1")
    fun queuedUploadFor(sourcePath: String): Long?

    @Query("UPDATE transfers SET state = :state, error = :error, updatedAt = :now WHERE id = :id")
    fun setState(id: Long, state: TransferState, error: String?, now: Long)

    @Query("UPDATE transfers SET bytesTransferred = :transferred, totalBytes = :total, updatedAt = :now WHERE id = :id")
    fun setProgress(id: Long, transferred: Long, total: Long, now: Long)

    @Query("SELECT * FROM transfers WHERE state IN ('QUEUED', 'RUNNING') ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE state NOT IN ('QUEUED', 'RUNNING') ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE accountName = :account AND state = 'FAILED' ORDER BY createdAt")
    fun failed(account: String): List<TransferEntity>

    @Query("DELETE FROM transfers WHERE id = :id")
    fun delete(id: Long)

    /** Failed rows outlive other finished ones so there is time to retry them; their staged files go with them. */
    @Query("SELECT sourcePath FROM transfers WHERE sourcePath IS NOT NULL AND ((state = 'FAILED' AND updatedAt < :failedBefore) OR (state NOT IN ('QUEUED', 'RUNNING', 'FAILED') AND updatedAt < :finishedBefore))")
    fun prunableSources(finishedBefore: Long, failedBefore: Long): List<String>

    @Query("DELETE FROM transfers WHERE (state = 'FAILED' AND updatedAt < :failedBefore) OR (state NOT IN ('QUEUED', 'RUNNING', 'FAILED') AND updatedAt < :finishedBefore)")
    fun pruneFinished(finishedBefore: Long, failedBefore: Long)

    /** Camera backup items that failed to upload; they stay until a retry gets them up. */
    @Query("SELECT COUNT(*) FROM transfers WHERE accountName = :account AND state = 'FAILED' AND mediaKey IS NOT NULL")
    fun observeFailedBackupCount(account: String): Flow<Int>

    @Query("DELETE FROM transfers WHERE accountName = :account AND mediaKey = :mediaKey")
    fun deleteForMedia(account: String, mediaKey: String)

    @Query("SELECT * FROM transfers WHERE accountName = :account AND state = 'COMPLETED'")
    fun completed(account: String): List<TransferEntity>

    /** Puts back rows removed by [clearCompleted]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun restore(rows: List<TransferEntity>)

    @Query("DELETE FROM transfers WHERE accountName = :account AND state = 'COMPLETED'")
    fun clearCompleted(account: String)

    @Query("DELETE FROM transfers WHERE accountName = :account")
    fun deleteAccount(account: String)
}
