package com.insituledger.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
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
    @ColumnInfo(name = "is_local_only") val isLocalOnly: Boolean = false
)
