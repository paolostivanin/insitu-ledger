package com.insituledger.app.data.local.db.dao

import androidx.room.*
import com.insituledger.app.data.local.db.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE deleted_at IS NULL ORDER BY date DESC, id DESC")
    fun getAll(): Flow<List<TransactionEntity>>

    @Query("""
        SELECT * FROM transactions
        WHERE deleted_at IS NULL
        AND (:from IS NULL OR date >= :from)
        AND (:to IS NULL OR date <= :to)
        AND (:categoryId IS NULL OR category_id = :categoryId)
        ORDER BY date DESC, id DESC
        LIMIT :limit OFFSET :offset
    """)
    fun getFiltered(
        from: String? = null,
        to: String? = null,
        categoryId: Long? = null,
        limit: Int = 100,
        offset: Int = 0
    ): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE deleted_at IS NULL ORDER BY date DESC LIMIT :limit")
    fun getRecent(limit: Int = 10): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE deleted_at IS NULL ORDER BY date DESC, id DESC")
    suspend fun getAllSync(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN type = 'income' THEN amount ELSE 0 END), 0) as income,
               COALESCE(SUM(CASE WHEN type = 'expense' THEN amount ELSE 0 END), 0) as expense
        FROM transactions
        WHERE deleted_at IS NULL AND date >= :from AND date <= :to
    """)
    suspend fun getMonthlySummary(from: String, to: String): MonthlySummary

    @Upsert
    suspend fun upsert(transaction: TransactionEntity)

    @Upsert
    suspend fun upsertAll(transactions: List<TransactionEntity>)

    @Query("""
        SELECT description, category_id AS categoryId FROM transactions
        WHERE deleted_at IS NULL AND description IS NOT NULL
          AND description LIKE :query || '%' COLLATE NOCASE
        GROUP BY description COLLATE NOCASE
        ORDER BY MAX(date) DESC
        LIMIT 10
    """)
    suspend fun autocomplete(query: String): List<LocalAutocompleteSuggestion>

    @Query("DELETE FROM transactions WHERE deleted_at IS NOT NULL")
    suspend fun purgeDeleted()

    @Query("SELECT MIN(id) FROM transactions")
    suspend fun getMinId(): Long?

    @Query("UPDATE transactions SET id = :newId WHERE id = :oldId")
    suspend fun updateId(oldId: Long, newId: Long)

    @Query("UPDATE transactions SET account_id = :newId WHERE account_id = :oldId")
    suspend fun updateAccountId(oldId: Long, newId: Long)

    @Query("UPDATE transactions SET category_id = :newId WHERE category_id = :oldId")
    suspend fun updateCategoryId(oldId: Long, newId: Long)
}

data class MonthlySummary(
    val income: Double,
    val expense: Double
)

data class LocalAutocompleteSuggestion(
    val description: String,
    val categoryId: Long
)
