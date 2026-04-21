package com.insituledger.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "accounts",
    indices = [Index(value = ["deleted_at"])]
)
data class AccountEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "user_id") val userId: Long,
    val name: String,
    val currency: String = "EUR",
    val balance: Double = 0.0,
    @ColumnInfo(name = "created_at") val createdAt: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: String = "",
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
    @ColumnInfo(name = "sync_version") val syncVersion: Long = 0,
    @ColumnInfo(name = "is_local_only") val isLocalOnly: Boolean = false,
    // Cached owner display name + shared flag (since v1.24.0). Powers the
    // "Shared by [name]" badge offline. Empty/false for local-only accounts.
    @ColumnInfo(name = "owner_name") val ownerName: String = "",
    @ColumnInfo(name = "is_shared") val isShared: Boolean = false
)
