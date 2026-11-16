package com.insituledger.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["date"]),
        Index(value = ["category_id"]),
        Index(value = ["account_id"]),
        Index(value = ["deleted_at"]),
        Index(value = ["deleted_at", "date"]),
        Index(value = ["deleted_at", "category_id"])
    ]
)
data class TransactionEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    // Legacy: mirrors the account owner (kept for index/sync compat). Use
    // createdByUserId for actual attribution.
    @ColumnInfo(name = "user_id") val userId: Long,
    val type: String,
    val amount: Double,
    val currency: String = "EUR",
    val description: String? = null,
    val note: String? = null,
    val date: String,
    @ColumnInfo(name = "created_at") val createdAt: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: String = "",
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
    @ColumnInfo(name = "sync_version") val syncVersion: Long = 0,
    @ColumnInfo(name = "is_local_only") val isLocalOnly: Boolean = false,
    // Authenticated creator; differs from userId when a co-owner adds an
    // entry to a shared account. Sticky across edits.
    @ColumnInfo(name = "created_by_user_id") val createdByUserId: Long? = null,
    @ColumnInfo(name = "created_by_name") val createdByName: String? = null,
    // Set only by a scheduler (backend, or ScheduledMaterializer in local
    // mode): the scheduled_transactions row that produced this entry, its
    // 1-based position in the series and the series length at that moment
    // (null = open-ended). Plain column, no FK: the schedule may be purged
    // while the entry stays. Never sent on create/update; kept on local edits.
    @ColumnInfo(name = "scheduled_id") val scheduledId: Long? = null,
    val occurrence: Int? = null,
    @ColumnInfo(name = "occurrence_total") val occurrenceTotal: Int? = null
)
