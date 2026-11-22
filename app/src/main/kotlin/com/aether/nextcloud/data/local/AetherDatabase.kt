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
    version = 5,
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

        /** Failed uploads remember their staged file so they can be retried; failed camera items remember their MediaStore key. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transfers ADD COLUMN sourcePath TEXT")
                db.execSQL("ALTER TABLE transfers ADD COLUMN mimeType TEXT")
                db.execSQL("ALTER TABLE transfers ADD COLUMN ifMatchEtag TEXT")
                db.execSQL("ALTER TABLE transfers ADD COLUMN mediaKey TEXT")
            }
        }

        /**
         * `transfers.error` held English prose; it now holds a code (see `AetherError.code`) that is turned into text when shown.
         * Existing rows are mapped from the sentences the app used to store; anything else (a network exception's own message,
         * say) becomes `unknown`. The schema is unchanged.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE transfers SET error = CASE
                        WHEN error IS NULL THEN NULL
                        WHEN error = 'Cancelled' THEN 'cancelled'
                        WHEN error = 'Authentication failed' THEN 'unauthorized'
                        WHEN error = 'Not found' THEN 'not_found'
                        WHEN error = 'The file changed on the server' THEN 'precondition_failed'
                        WHEN error LIKE 'This file is locked on the server%' THEN 'locked'
                        WHEN error LIKE 'Your Nextcloud storage is full%' THEN 'insufficient_storage'
                        WHEN error = 'Timed out' THEN 'timeout'
                        WHEN error LIKE 'Unexpected server response%' THEN 'malformed'
                        WHEN error GLOB 'Server returned HTTP [0-9][0-9][0-9]*' THEN 'http:' || substr(error, 22, 3)
                        ELSE 'unknown'
                    END
                    """.trimIndent(),
                )
            }
        }

        /** Composite indices for recent-file SAF queries, camera-backup failure replacement, and recent transfers sorting. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfers_accountName_mediaKey` ON `transfers` (`accountName`, `mediaKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfers_state_updatedAt` ON `transfers` (`state`, `updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_accountName_isDirectory_lastModified` ON `documents` (`accountName`, `isDirectory`, `lastModified`)")
            }
        }
    }

    abstract fun documentDao(): DocumentDao
    abstract fun transferDao(): TransferDao
    abstract fun backedUpMediaDao(): BackedUpMediaDao
}
