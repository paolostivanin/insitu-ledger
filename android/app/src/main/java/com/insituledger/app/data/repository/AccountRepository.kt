package com.insituledger.app.data.repository

import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.local.db.entity.AccountEntity
import com.insituledger.app.data.local.db.entity.PendingOperationEntity
import com.insituledger.app.data.remote.api.AccountApi
import com.insituledger.app.data.remote.dto.AccountInput
import com.insituledger.app.domain.model.Account
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao,
    private val pendingOpDao: PendingOperationDao,
    private val accountApi: AccountApi,
    private val gson: Gson
) {
    fun getAll(): Flow<List<Account>> = accountDao.getAll().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun getById(id: Long): Account? = accountDao.getById(id)?.toDomain()

    suspend fun create(name: String, currency: String, balance: Double): Long {
        val minId = accountDao.getMinId() ?: 0
        val localId = if (minId >= 0) -1 else minId - 1
        val entity = AccountEntity(
            id = localId,
            userId = 0,
            name = name,
            currency = currency,
            balance = balance,
            isLocalOnly = true
        )
        accountDao.upsert(entity)

        val input = AccountInput(name, currency, balance)
        pendingOpDao.insert(PendingOperationEntity(
            entityType = "account",
            operation = "CREATE",
            entityId = localId,
            payloadJson = gson.toJson(input)
        ))
        return localId
    }

    suspend fun update(id: Long, name: String, currency: String, balance: Double) {
        val existing = accountDao.getById(id) ?: return
        accountDao.upsert(existing.copy(name = name, currency = currency, balance = balance))

        val input = AccountInput(name, currency, balance)
        pendingOpDao.insert(PendingOperationEntity(
            entityType = "account",
            operation = "UPDATE",
            entityId = id,
            serverId = if (id > 0) id else null,
            payloadJson = gson.toJson(input)
        ))
    }

    suspend fun delete(id: Long) {
        val existing = accountDao.getById(id) ?: return
        accountDao.upsert(existing.copy(deletedAt = "deleted"))

        pendingOpDao.insert(PendingOperationEntity(
            entityType = "account",
            operation = "DELETE",
            entityId = id,
            serverId = if (id > 0) id else null
        ))
    }

    private fun AccountEntity.toDomain() = Account(
        id = id, userId = userId, name = name,
        currency = currency, balance = balance,
        isLocalOnly = isLocalOnly
    )
}
