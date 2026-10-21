package com.aether.nextcloud.data.local.entity

import androidx.room.Entity

/** One MediaStore item already uploaded by camera backup; guarantees idempotent uploads. */
@Entity(tableName = "backed_up_media", primaryKeys = ["accountName", "mediaId", "isVideo"])
data class BackedUpMediaEntity(
    val accountName: String,
    /** `MediaStore.MediaColumns._ID` */
    val mediaId: Long,
    val isVideo: Boolean,
    val dateModified: Long,
    val size: Long,
    val remotePath: String,
    val backedUpAt: Long,
)
