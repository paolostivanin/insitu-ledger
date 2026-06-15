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
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    // Client-generated UUID for idempotent CREATE. The same value is sent to
    // the backend (TransactionInput.clientId etc.) so a retry after a
    // transient failure dedupes server-side instead of inserting twice.
    // Null for UPDATE/DELETE ops, which don't need idempotency.
    @ColumnInfo(name = "client_id") val clientId: String? = null,
    // Permanent push failures move to a visible failed state so they cannot
    // block every future pull. Users can retry or discard them from Settings.
    val state: String = STATE_PENDING,
    @ColumnInfo(name = "last_error") val lastError: String? = null
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_FAILED = "failed"
    }
}
