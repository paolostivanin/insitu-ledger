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

    @Query("SELECT MIN(id) FROM scheduled_transactions")
    suspend fun getMinId(): Long?

    @Query("UPDATE scheduled_transactions SET id = :newId WHERE id = :oldId")
    suspend fun updateId(oldId: Long, newId: Long)

    @Query("UPDATE scheduled_transactions SET account_id = :newId WHERE account_id = :oldId")
    suspend fun updateAccountId(oldId: Long, newId: Long)

    @Query("UPDATE scheduled_transactions SET category_id = :newId WHERE category_id = :oldId")
    suspend fun updateCategoryId(oldId: Long, newId: Long)
}
