package com.insituledger.app.data.repository

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.CategoryBreakdownRow
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import com.insituledger.app.data.local.db.entity.PendingOperationEntity
import com.insituledger.app.data.local.db.entity.TransactionEntity
import com.insituledger.app.data.remote.api.TransactionApi
import com.insituledger.app.data.remote.dto.TransactionInput
import com.insituledger.app.domain.model.Transaction
import com.insituledger.app.data.sync.SyncManager
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepository @Inject constructor(
    private val database: AppDatabase,
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val pendingOpDao: PendingOperationDao,
    private val transactionApi: TransactionApi,
    private val gson: Gson,
    private val syncManager: SyncManager,
    private val prefs: UserPreferences
) {
    private fun isSyncEnabled() = prefs.getSyncModeImmediate() == "webapp"

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

    fun getSorted(
        from: String? = null,
        to: String? = null,
        categoryId: Long? = null,
        sortBy: String = "date",
        sortDir: String = "desc",
        limit: Int = 100,
        offset: Int = 0
    ): Flow<List<Transaction>> {
        val columnMap = mapOf(
            "date" to "date",
            "amount" to "amount",
            "description" to "description"
        )
        val column = columnMap[sortBy] ?: "date"
        val dir = if (sortDir == "asc") "ASC" else "DESC"

        val sb = StringBuilder("SELECT * FROM transactions WHERE deleted_at IS NULL")
        val args = mutableListOf<Any>()

        if (from != null) {
            sb.append(" AND date >= ?")
            args.add(from)
        }
        if (to != null) {
            sb.append(" AND SUBSTR(date, 1, 10) <= ?")
            args.add(to)
        }
        if (categoryId != null) {
            sb.append(" AND category_id = ?")
            args.add(categoryId)
        }

        sb.append(" ORDER BY $column $dir, id DESC LIMIT ? OFFSET ?")
        args.add(limit)
        args.add(offset)

        return transactionDao.getSorted(SimpleSQLiteQuery(sb.toString(), args.toTypedArray())).map { list ->
            list.map { it.toDomain() }
        }
    }

    suspend fun listFromServer(
        ownerId: Long,
        from: String? = null,
        to: String? = null,
        categoryId: Long? = null,
        sortBy: String? = null,
        sortDir: String? = null,
        limit: Int = 100
    ): List<Transaction> {
        val response = transactionApi.list(
            from = from, to = to, categoryId = categoryId,
            limit = limit, sortBy = sortBy, sortDir = sortDir,
            ownerId = ownerId
        )
        if (!response.isSuccessful) return emptyList()
        return response.body()?.map { dto ->
            Transaction(
                id = dto.id, accountId = dto.accountId, categoryId = dto.categoryId,
                userId = dto.userId, type = dto.type, amount = dto.amount,
                currency = dto.currency, description = dto.description, note = dto.note, date = dto.date
            )
        } ?: emptyList()
    }

    suspend fun getMonthlySummary(from: String, to: String) = transactionDao.getMonthlySummary(from, to)

    suspend fun getById(id: Long): Transaction? = transactionDao.getById(id)?.toDomain()

    suspend fun autocomplete(query: String): List<Pair<String, Long>> {
        return transactionDao.autocomplete(query).map { it.description to it.categoryId }
    }

    suspend fun create(
        accountId: Long, categoryId: Long, type: String,
        amount: Double, currency: String, description: String?, note: String?, date: String
    ): Long {
        require(amount > 0) { "Amount must be positive" }
        require(type == "income" || type == "expense") { "Type must be 'income' or 'expense'" }

        val localId = database.withTransaction {
            val minId = transactionDao.getMinId() ?: 0
            val id = if (minId >= 0) -1 else minId - 1
            val entity = TransactionEntity(
                id = id,
                accountId = accountId,
                categoryId = categoryId,
                userId = 0,
                type = type,
                amount = amount,
                currency = currency,
                description = description,
                note = note,
                date = date,
                isLocalOnly = true
            )
            transactionDao.upsert(entity)
            val delta = if (type == "income") amount else -amount
            accountDao.adjustBalance(accountId, delta)
            id
        }

        if (isSyncEnabled()) {
            val input = TransactionInput(accountId, categoryId, type, amount, currency, description, note, date)
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "transaction",
                operation = "CREATE",
                entityId = localId,
                payloadJson = gson.toJson(input)
            ))
            syncManager.triggerImmediateSync()
        }
        return localId
    }

    suspend fun update(
        id: Long, accountId: Long, categoryId: Long, type: String,
        amount: Double, currency: String, description: String?, note: String?, date: String
    ) {
        require(amount > 0) { "Amount must be positive" }
        require(type == "income" || type == "expense") { "Type must be 'income' or 'expense'" }

        database.withTransaction {
            val existing = transactionDao.getById(id) ?: return@withTransaction
            // Reverse old transaction's effect on old account
            val oldDelta = if (existing.type == "income") existing.amount else -existing.amount
            accountDao.adjustBalance(existing.accountId, -oldDelta)
            // Apply new transaction's effect on new account
            val newDelta = if (type == "income") amount else -amount
            accountDao.adjustBalance(accountId, newDelta)

            transactionDao.upsert(existing.copy(
                accountId = accountId, categoryId = categoryId, type = type,
                amount = amount, currency = currency, description = description, note = note, date = date
            ))
        }

        if (isSyncEnabled()) {
            val input = TransactionInput(accountId, categoryId, type, amount, currency, description, note, date)
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "transaction",
                operation = "UPDATE",
                entityId = id,
                serverId = if (id > 0) id else null,
                payloadJson = gson.toJson(input)
            ))
            syncManager.triggerImmediateSync()
        }
    }

    suspend fun delete(id: Long) {
        database.withTransaction {
            val existing = transactionDao.getById(id) ?: return@withTransaction
            // Reverse the transaction's effect on account balance
            val delta = if (existing.type == "income") existing.amount else -existing.amount
            accountDao.adjustBalance(existing.accountId, -delta)
            transactionDao.upsert(existing.copy(deletedAt = "deleted"))
        }

        if (isSyncEnabled()) {
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "transaction",
                operation = "DELETE",
                entityId = id,
                serverId = if (id > 0) id else null
            ))
            syncManager.triggerImmediateSync()
        }
    }

    fun search(query: String): Flow<List<Transaction>> = transactionDao.search(query).map { list ->
        list.map { it.toDomain() }
    }

    suspend fun getFilteredSync(from: String?, to: String?, categoryId: Long?): List<Transaction> =
        transactionDao.getFilteredSync(from, to, categoryId).map { it.toDomain() }

    suspend fun getCategoryBreakdown(from: String?, to: String?): List<CategoryBreakdownRow> =
        transactionDao.getCategoryBreakdown(from, to)

    private fun TransactionEntity.toDomain() = Transaction(
        id = id, accountId = accountId, categoryId = categoryId,
        userId = userId, type = type, amount = amount,
        currency = currency, description = description, note = note, date = date,
        isLocalOnly = isLocalOnly
    )
}
