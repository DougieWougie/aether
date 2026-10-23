package com.aether.nextcloud.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aether.nextcloud.data.local.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE documentId = :id")
    fun get(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE parentId = :parentId")
    fun children(parentId: String): List<DocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: DocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(entities: List<DocumentEntity>)

    @Query("DELETE FROM documents WHERE documentId = :id")
    fun delete(id: String)

    @Query("DELETE FROM documents WHERE documentId IN (:ids)")
    fun deleteAll(ids: List<String>)

    /** The document at [path] plus everything below it. [likePattern] is `escapedPath/%`. */
    @Query("SELECT * FROM documents WHERE accountName = :account AND (path = :path OR path LIKE :likePattern ESCAPE '\\')")
    fun subtree(account: String, path: String, likePattern: String): List<DocumentEntity>

    @Query("DELETE FROM documents WHERE accountName = :account")
    fun deleteAccount(account: String)

    @Query("SELECT * FROM documents WHERE accountName = :account AND localCachePath IS NOT NULL")
    fun withLocalCopy(account: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE isPinned = 1 ORDER BY accountName, path")
    fun pinned(): List<DocumentEntity>

    /** Pinned items the user chose directly: a pinned folder's contents carry the flag too, but belong to the folder. */
    @Query(
        "SELECT * FROM documents WHERE isPinned = 1 " +
            "AND (parentId IS NULL OR parentId NOT IN (SELECT documentId FROM documents WHERE isPinned = 1)) ORDER BY accountName, path",
    )
    fun observePinnedRoots(): Flow<List<DocumentEntity>>

    @Query("UPDATE documents SET isPinned = :pinned WHERE documentId = :id")
    fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE documents SET localCachePath = NULL, cachedEtag = NULL WHERE documentId = :id")
    fun clearLocalCopy(id: String)

    @Query("UPDATE documents SET isPinned = 1 WHERE parentId = :parentId")
    fun pinChildren(parentId: String)

    @Query("UPDATE documents SET cachedEtag = :etag WHERE documentId = :id")
    fun setCachedEtag(id: String, etag: String?)

    @Query("UPDATE documents SET childrenSyncedAt = NULL WHERE documentId = :id")
    fun invalidateChildren(id: String)

    @Query("UPDATE documents SET lastAccessed = :now WHERE documentId = :id")
    fun touch(id: String, now: Long)
}
