package com.insituledger.app.data.local.db.dao

import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
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
        AND (:to IS NULL OR SUBSTR(date, 1, 10) <= :to)
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
        WHERE deleted_at IS NULL AND date >= :from AND SUBSTR(date, 1, 10) <= :to
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

    @Query("""
        SELECT * FROM transactions
        WHERE deleted_at IS NULL
        AND description LIKE '%' || :query || '%' COLLATE NOCASE
        ORDER BY date DESC, id DESC
    """)
    fun search(query: String): Flow<List<TransactionEntity>>

    @RawQuery(observedEntities = [TransactionEntity::class])
    fun getSorted(query: SupportSQLiteQuery): Flow<List<TransactionEntity>>

    @Query("DELETE FROM transactions WHERE deleted_at IS NOT NULL")
    suspend fun purgeDeleted()

    // Hard-delete by id. Used by SyncRepository when the backend tells us a
    // CREATE we sent was actually a scheduled_transactions row — we drop the
    // local optimistic transactions row so it can't render as a phantom.
    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    // Hard-delete every transaction belonging to the given account. Used by
    // SyncRepository.pull when the server tombstones a shared-account grant
    // (revoked_account_ids).
    @Query("DELETE FROM transactions WHERE account_id = :accountId")
    suspend fun deleteByAccountId(accountId: Long)

    // Used by SyncRepository.pull to find transaction IDs whose pending ops
    // need to be purged before the rows are deleted.
    @Query("SELECT id FROM transactions WHERE account_id = :accountId")
    suspend fun selectIdsByAccountId(accountId: Long): List<Long>

    // Used by Tier B B4: pre-check before allowing a category soft-delete on
    // Android. Mirrors the server-side transactions check.
    @Query("SELECT COUNT(*) FROM transactions WHERE category_id = :categoryId AND deleted_at IS NULL")
    suspend fun countByCategoryId(categoryId: Long): Int

    @Query("SELECT MIN(id) FROM transactions")
    suspend fun getMinId(): Long?

    @Query("UPDATE transactions SET id = :newId WHERE id = :oldId")
    suspend fun updateId(oldId: Long, newId: Long)

    @Query("UPDATE transactions SET is_local_only = 0 WHERE id = :id")
    suspend fun clearLocalOnly(id: Long)

    @Query("UPDATE transactions SET account_id = :newId WHERE account_id = :oldId")
    suspend fun updateAccountId(oldId: Long, newId: Long)

    @Query("UPDATE transactions SET category_id = :newId WHERE category_id = :oldId")
    suspend fun updateCategoryId(oldId: Long, newId: Long)

    @Query("""
        SELECT * FROM transactions
        WHERE deleted_at IS NULL
        AND (:from IS NULL OR date >= :from)
        AND (:to IS NULL OR SUBSTR(date, 1, 10) <= :to)
        AND (:categoryId IS NULL OR category_id = :categoryId)
        ORDER BY date DESC, id DESC
    """)
    suspend fun getFilteredSync(from: String?, to: String?, categoryId: Long?): List<TransactionEntity>

    @Query("""
        SELECT category_id, type, SUM(amount) AS total, COUNT(*) AS count
        FROM transactions
        WHERE deleted_at IS NULL
        AND (:from IS NULL OR date >= :from)
        AND (:to IS NULL OR SUBSTR(date, 1, 10) <= :to)
        GROUP BY category_id, type
        ORDER BY total DESC
    """)
    suspend fun getCategoryBreakdown(from: String?, to: String?): List<CategoryBreakdownRow>
}

data class MonthlySummary(
    val income: Double,
    val expense: Double
)

data class LocalAutocompleteSuggestion(
    val description: String,
    val categoryId: Long
)

data class CategoryBreakdownRow(
    @ColumnInfo(name = "category_id") val categoryId: Long,
    val type: String,
    val total: Double,
    val count: Int
)
