package com.aether.nextcloud.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TransferDirection { UPLOAD, DOWNLOAD }

enum class TransferState { QUEUED, RUNNING, COMPLETED, FAILED, CONFLICT }

/** An upload or download shown on the dashboard's Active Transfers list. */
@Entity(tableName = "transfers", indices = [Index("state")])
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountName: String,
    val remotePath: String,
    val direction: TransferDirection,
    val state: TransferState = TransferState.QUEUED,
    val bytesTransferred: Long = 0,
    val totalBytes: Long = -1,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
)
