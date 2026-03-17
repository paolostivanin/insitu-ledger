package com.insituledger.app.data.repository

import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import com.insituledger.app.data.local.db.entity.PendingOperationEntity
import com.insituledger.app.data.local.db.entity.TransactionEntity
import com.insituledger.app.data.remote.api.TransactionApi
import com.insituledger.app.data.remote.dto.TransactionInput
import com.insituledger.app.domain.model.Transaction
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val pendingOpDao: PendingOperationDao,
    private val transactionApi: TransactionApi,
    private val gson: Gson
) {
    fun getAll(): Flow<List<Transaction>> = transactionDao.getAll().map { list ->
        list.map { it.toDomain() }
    }

    fun getFiltered(
        from: String? = null,
        to: String? = null,
        categoryId: Long? = null,
        limit: Int = 100,
        offset: Int = 0
    ): Flow<List<Transaction>> = transactionDao.getFiltered(from, to, categoryId, limit, offset).map { list ->
        list.map { it.toDomain() }
    }

    fun getRecent(limit: Int = 10): Flow<List<Transaction>> = transactionDao.getRecent(limit).map { list ->
        list.map { it.toDomain() }
    }

    suspend fun getMonthlySummary(from: String, to: String) = transactionDao.getMonthlySummary(from, to)

    suspend fun getById(id: Long): Transaction? = transactionDao.getById(id)?.toDomain()

    suspend fun create(
        accountId: Long, categoryId: Long, type: String,
        amount: Double, currency: String, description: String?, date: String
    ): Long {
        val minId = transactionDao.getMinId() ?: 0
        val localId = if (minId >= 0) -1 else minId - 1
        val entity = TransactionEntity(
            id = localId,
            accountId = accountId,
            categoryId = categoryId,
            userId = 0,
            type = type,
            amount = amount,
            currency = currency,
            description = description,
            date = date,
            isLocalOnly = true
        )
        transactionDao.upsert(entity)

        val input = TransactionInput(accountId, categoryId, type, amount, currency, description, date)
        pendingOpDao.insert(PendingOperationEntity(
            entityType = "transaction",
            operation = "CREATE",
            entityId = localId,
            payloadJson = gson.toJson(input)
        ))
        return localId
    }

    suspend fun update(
        id: Long, accountId: Long, categoryId: Long, type: String,
        amount: Double, currency: String, description: String?, date: String
    ) {
        val existing = transactionDao.getById(id) ?: return
        transactionDao.upsert(existing.copy(
            accountId = accountId, categoryId = categoryId, type = type,
            amount = amount, currency = currency, description = description, date = date
        ))

        val input = TransactionInput(accountId, categoryId, type, amount, currency, description, date)
        pendingOpDao.insert(PendingOperationEntity(
            entityType = "transaction",
            operation = "UPDATE",
            entityId = id,
            serverId = if (id > 0) id else null,
            payloadJson = gson.toJson(input)
        ))
    }

    suspend fun delete(id: Long) {
        val existing = transactionDao.getById(id) ?: return
        transactionDao.upsert(existing.copy(deletedAt = "deleted"))

        pendingOpDao.insert(PendingOperationEntity(
            entityType = "transaction",
            operation = "DELETE",
            entityId = id,
            serverId = if (id > 0) id else null
        ))
    }

    private fun TransactionEntity.toDomain() = Transaction(
        id = id, accountId = accountId, categoryId = categoryId,
        userId = userId, type = type, amount = amount,
        currency = currency, description = description, date = date,
        isLocalOnly = isLocalOnly
    )
}
