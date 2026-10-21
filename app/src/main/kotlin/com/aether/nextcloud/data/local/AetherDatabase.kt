package com.aether.nextcloud.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aether.nextcloud.data.local.dao.BackedUpMediaDao
import com.aether.nextcloud.data.local.dao.DocumentDao
import com.aether.nextcloud.data.local.dao.TransferDao
import com.aether.nextcloud.data.local.entity.BackedUpMediaEntity
import com.aether.nextcloud.data.local.entity.DocumentEntity
import com.aether.nextcloud.data.local.entity.TransferEntity

@Database(
    entities = [DocumentEntity::class, TransferEntity::class, BackedUpMediaEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AetherDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun transferDao(): TransferDao
    abstract fun backedUpMediaDao(): BackedUpMediaDao
}
