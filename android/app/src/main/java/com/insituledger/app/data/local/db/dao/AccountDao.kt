package com.insituledger.app.data.local.db.dao

import androidx.room.*
import com.insituledger.app.data.local.db.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE deleted_at IS NULL ORDER BY name ASC")
    fun getAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Upsert
    suspend fun upsertAll(accounts: List<AccountEntity>)

    @Query("DELETE FROM accounts WHERE deleted_at IS NOT NULL")
    suspend fun purgeDeleted()

    @Query("SELECT * FROM accounts WHERE deleted_at IS NULL ORDER BY name ASC")
    suspend fun getAllSync(): List<AccountEntity>

    @Query("SELECT MIN(id) FROM accounts")
    suspend fun getMinId(): Long?

    @Query("UPDATE accounts SET id = :newId WHERE id = :oldId")
    suspend fun updateId(oldId: Long, newId: Long)
}
