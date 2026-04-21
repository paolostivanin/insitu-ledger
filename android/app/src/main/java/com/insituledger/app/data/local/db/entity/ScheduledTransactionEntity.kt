package com.insituledger.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_transactions")
data class ScheduledTransactionEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    // Legacy: mirrors the account owner. Use createdByUserId for attribution.
    @ColumnInfo(name = "user_id") val userId: Long,
    val type: String,
    val amount: Double,
    val currency: String = "EUR",
    val description: String? = null,
    val note: String? = null,
    val rrule: String,
    @ColumnInfo(name = "next_occurrence") val nextOccurrence: String,
    val active: Boolean = true,
    @ColumnInfo(name = "max_occurrences") val maxOccurrences: Int? = null,
    @ColumnInfo(name = "occurrence_count") val occurrenceCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: String = "",
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
    @ColumnInfo(name = "sync_version") val syncVersion: Long = 0,
    @ColumnInfo(name = "is_local_only") val isLocalOnly: Boolean = false,
    @ColumnInfo(name = "created_by_user_id") val createdByUserId: Long? = null
)
