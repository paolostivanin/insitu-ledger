package com.insituledger.app.data.local.db.dao

import androidx.room.*
import com.insituledger.app.data.local.db.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE deleted_at IS NULL ORDER BY type ASC, name ASC")
    fun getAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE type = :type AND deleted_at IS NULL ORDER BY name ASC")
    fun getByType(type: String): Flow<List<CategoryEntity>>

    @Upsert
    suspend fun upsert(category: CategoryEntity)

    @Upsert
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE deleted_at IS NOT NULL")
    suspend fun purgeDeleted()

    @Query("SELECT * FROM categories WHERE deleted_at IS NULL ORDER BY type ASC, name ASC")
    suspend fun getAllSync(): List<CategoryEntity>

    @Query("SELECT MIN(id) FROM categories")
    suspend fun getMinId(): Long?

    @Query("UPDATE categories SET id = :newId WHERE id = :oldId")
    suspend fun updateId(oldId: Long, newId: Long)
}
