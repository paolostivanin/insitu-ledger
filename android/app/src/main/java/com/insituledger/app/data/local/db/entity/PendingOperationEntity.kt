package com.insituledger.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_operations")
data class PendingOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entity_type") val entityType: String,
    val operation: String,
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "server_id") val serverId: Long? = null,
    @ColumnInfo(name = "payload_json") val payloadJson: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
