package com.aether.nextcloud.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aether.nextcloud.data.local.dao.BackedUpMediaDao
import com.aether.nextcloud.data.local.dao.DocumentDao
import com.aether.nextcloud.data.local.dao.TransferDao
import com.aether.nextcloud.data.local.entity.BackedUpMediaEntity
import com.aether.nextcloud.data.local.entity.DocumentEntity
import com.aether.nextcloud.data.local.entity.TransferEntity

@Database(
    entities = [DocumentEntity::class, TransferEntity::class, BackedUpMediaEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AetherDatabase : RoomDatabase() {
    companion object {
        /** Camera backup records which album each upload came from. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE backed_up_media ADD COLUMN bucketId INTEGER")
                db.execSQL("ALTER TABLE backed_up_media ADD COLUMN bucketName TEXT")
            }
        }
    }


    abstract fun documentDao(): DocumentDao
    abstract fun transferDao(): TransferDao
    abstract fun backedUpMediaDao(): BackedUpMediaDao
}
