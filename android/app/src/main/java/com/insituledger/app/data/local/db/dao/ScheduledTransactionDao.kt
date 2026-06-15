package com.insituledger.app.data.local.db.dao

import androidx.room.*
import com.insituledger.app.data.local.db.entity.ScheduledTransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledTransactionDao {
    @Query("SELECT * FROM scheduled_transactions WHERE deleted_at IS NULL ORDER BY next_occurrence ASC")
    fun getAll(): Flow<List<ScheduledTransactionEntity>>

    @Query("SELECT * FROM scheduled_transactions WHERE deleted_at IS NULL ORDER BY next_occurrence ASC")
    suspend fun getAllSync(): List<ScheduledTransactionEntity>

    @Query("SELECT * FROM scheduled_transactions WHERE id = :id")
    suspend fun getById(id: Long): ScheduledTransactionEntity?

    @Upsert
    suspend fun upsert(scheduled: ScheduledTransactionEntity)

    @Upsert
    suspend fun upsertAll(scheduled: List<ScheduledTransactionEntity>)

    @Query("DELETE FROM scheduled_transactions WHERE deleted_at IS NOT NULL")
    suspend fun purgeDeleted()

    // SUBSTR(...,1,16) yields "YYYY-MM-DDTHH:MM" for any datetime shape
    // (strips seconds and any TZ offset / Z suffix) and the bare date for
    // date-only rows. Both compare correctly against a naive "now" string —
    // unlike raw `next_occurrence`, which would sort an offset-bearing
    // due-this-minute row AFTER the naive now and miss the tick.
    @Query("SELECT * FROM scheduled_transactions WHERE active = 1 AND deleted_at IS NULL AND SUBSTR(next_occurrence, 1, 16) <= :now")
    suspend fun getDue(now: String): List<ScheduledTransactionEntity>

    @Query("SELECT MIN(id) FROM scheduled_transactions")
    suspend fun getMinId(): Long?

    @Query("UPDATE scheduled_transactions SET id = :newId WHERE id = :oldId")
    suspend fun updateId(oldId: Long, newId: Long)

    @Query("UPDATE scheduled_transactions SET is_local_only = 0 WHERE id = :id")
    suspend fun clearLocalOnly(id: Long)

    @Query("UPDATE scheduled_transactions SET account_id = :newId WHERE account_id = :oldId")
    suspend fun updateAccountId(oldId: Long, newId: Long)

    @Query("UPDATE scheduled_transactions SET category_id = :newId WHERE category_id = :oldId")
    suspend fun updateCategoryId(oldId: Long, newId: Long)

    @Query("DELETE FROM scheduled_transactions WHERE account_id = :accountId")
    suspend fun deleteByAccountId(accountId: Long)

    @Query("SELECT id FROM scheduled_transactions WHERE account_id = :accountId")
    suspend fun selectIdsByAccountId(accountId: Long): List<Long>

    // Used by Tier B B4: pre-check before allowing a category soft-delete on
    // Android. Mirrors the server-side scheduled-transactions check.
    @Query("SELECT COUNT(*) FROM scheduled_transactions WHERE category_id = :categoryId AND deleted_at IS NULL")
    suspend fun countByCategoryId(categoryId: Long): Int
}
