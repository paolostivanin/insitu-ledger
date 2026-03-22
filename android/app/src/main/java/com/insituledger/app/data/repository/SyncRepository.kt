package com.insituledger.app.data.repository

import androidx.room.withTransaction
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.*
import com.insituledger.app.data.local.db.entity.*
import com.insituledger.app.data.remote.api.*
import com.insituledger.app.data.remote.dto.*
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepository @Inject constructor(
    private val database: AppDatabase,
    private val syncApi: SyncApi,
    private val transactionApi: TransactionApi,
    private val categoryApi: CategoryApi,
    private val accountApi: AccountApi,
    private val scheduledApi: ScheduledApi,
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val accountDao: AccountDao,
    private val scheduledDao: ScheduledTransactionDao,
    private val pendingOpDao: PendingOperationDao,
    private val prefs: UserPreferences,
    private val gson: Gson
) {
    suspend fun pushPendingOperations(): Result<Unit> {
        return try {
            val ops = pendingOpDao.getAll()
            for (op in ops) {
                val success = when (op.entityType) {
                    "account" -> pushAccountOp(op)
                    "category" -> pushCategoryOp(op)
                    "transaction" -> pushTransactionOp(op)
                    "scheduled" -> pushScheduledOp(op)
                    else -> true
                }
                if (success) {
                    pendingOpDao.delete(op)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun pushAccountOp(op: PendingOperationEntity): Boolean {
        return when (op.operation) {
            "CREATE" -> {
                val input = gson.fromJson(op.payloadJson, AccountInput::class.java)
                val response = accountApi.create(input)
                if (response.isSuccessful) {
                    val serverId = response.body()!!.id
                    remapAccountId(op.entityId, serverId)
                    true
                } else false
            }
            "UPDATE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return true
                val input = gson.fromJson(op.payloadJson, AccountInput::class.java)
                accountApi.update(id, input).isSuccessful
            }
            "DELETE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return true
                accountApi.delete(id).isSuccessful
            }
            else -> true
        }
    }

    private suspend fun pushCategoryOp(op: PendingOperationEntity): Boolean {
        return when (op.operation) {
            "CREATE" -> {
                val input = gson.fromJson(op.payloadJson, CategoryInput::class.java)
                val response = categoryApi.create(input)
                if (response.isSuccessful) {
                    val serverId = response.body()!!.id
                    remapCategoryId(op.entityId, serverId)
                    true
                } else false
            }
            "UPDATE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return true
                val input = gson.fromJson(op.payloadJson, CategoryInput::class.java)
                categoryApi.update(id, input).isSuccessful
            }
            "DELETE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return true
                categoryApi.delete(id).isSuccessful
            }
            else -> true
        }
    }

    private suspend fun pushTransactionOp(op: PendingOperationEntity): Boolean {
        return when (op.operation) {
            "CREATE" -> {
                val input = gson.fromJson(op.payloadJson, TransactionInput::class.java)
                val response = transactionApi.create(input)
                if (response.isSuccessful) {
                    val serverId = response.body()!!.id
                    remapTransactionId(op.entityId, serverId)
                    true
                } else false
            }
            "UPDATE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return true
                val input = gson.fromJson(op.payloadJson, TransactionInput::class.java)
                transactionApi.update(id, input).isSuccessful
            }
            "DELETE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return true
                transactionApi.delete(id).isSuccessful
            }
            else -> true
        }
    }

    private suspend fun pushScheduledOp(op: PendingOperationEntity): Boolean {
        return when (op.operation) {
            "CREATE" -> {
                val input = gson.fromJson(op.payloadJson, ScheduledInput::class.java)
                val response = scheduledApi.create(input)
                if (response.isSuccessful) {
                    val serverId = response.body()!!.id
                    remapScheduledId(op.entityId, serverId)
                    true
                } else false
            }
            "UPDATE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return true
                val input = gson.fromJson(op.payloadJson, ScheduledInput::class.java)
                scheduledApi.update(id, input).isSuccessful
            }
            "DELETE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return true
                scheduledApi.delete(id).isSuccessful
            }
            else -> true
        }
    }

    private suspend fun remapAccountId(oldId: Long, newId: Long) {
        database.withTransaction {
            accountDao.updateId(oldId, newId)
            transactionDao.updateAccountId(oldId, newId)
            scheduledDao.updateAccountId(oldId, newId)
            pendingOpDao.updateEntityId(oldId, newId, "account")
        }
    }

    private suspend fun remapCategoryId(oldId: Long, newId: Long) {
        database.withTransaction {
            categoryDao.updateId(oldId, newId)
            transactionDao.updateCategoryId(oldId, newId)
            scheduledDao.updateCategoryId(oldId, newId)
            pendingOpDao.updateEntityId(oldId, newId, "category")
        }
    }

    private suspend fun remapTransactionId(oldId: Long, newId: Long) {
        database.withTransaction {
            transactionDao.updateId(oldId, newId)
            pendingOpDao.updateEntityId(oldId, newId, "transaction")
        }
    }

    private suspend fun remapScheduledId(oldId: Long, newId: Long) {
        database.withTransaction {
            scheduledDao.updateId(oldId, newId)
            pendingOpDao.updateEntityId(oldId, newId, "scheduled")
        }
    }

    suspend fun pull(): Result<Unit> {
        return try {
            val lastVersion = prefs.lastSyncVersionFlow.first()
            val response = syncApi.sync(lastVersion)
            if (!response.isSuccessful) {
                return Result.failure(Exception("Sync failed: ${response.code()}"))
            }

            val data = response.body()!!

            // Upsert accounts
            val accountEntities = data.accounts.map { it.toEntity() }
            if (accountEntities.isNotEmpty()) {
                accountDao.upsertAll(accountEntities)
                accountDao.purgeDeleted()
            }

            // Upsert categories
            val categoryEntities = data.categories.map { it.toEntity() }
            if (categoryEntities.isNotEmpty()) {
                categoryDao.upsertAll(categoryEntities)
                categoryDao.purgeDeleted()
            }

            // Upsert transactions
            val transactionEntities = data.transactions.map { it.toEntity() }
            if (transactionEntities.isNotEmpty()) {
                transactionDao.upsertAll(transactionEntities)
                transactionDao.purgeDeleted()
            }

            // Upsert scheduled transactions
            val scheduledEntities = data.scheduledTransactions.map { it.toEntity() }
            if (scheduledEntities.isNotEmpty()) {
                scheduledDao.upsertAll(scheduledEntities)
                scheduledDao.purgeDeleted()
            }

            prefs.saveLastSyncVersion(data.currentVersion)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun enqueueLocalDataForSync() {
        // Enqueue accounts first (transactions/scheduled reference them)
        val accounts = accountDao.getAllSync()
        for (a in accounts) {
            if (!a.isLocalOnly) continue
            val input = AccountInput(a.name, a.currency, a.balance)
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "account",
                operation = "CREATE",
                entityId = a.id,
                payloadJson = gson.toJson(input)
            ))
        }

        // Enqueue categories next (transactions/scheduled reference them)
        val categories = categoryDao.getAllSync()
        for (c in categories) {
            if (!c.isLocalOnly) continue
            val input = CategoryInput(c.parentId, c.name, c.type, c.icon, c.color)
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "category",
                operation = "CREATE",
                entityId = c.id,
                payloadJson = gson.toJson(input)
            ))
        }

        // Enqueue transactions
        val transactions = transactionDao.getAllSync()
        for (t in transactions) {
            if (!t.isLocalOnly) continue
            val input = TransactionInput(t.accountId, t.categoryId, t.type, t.amount, t.currency, t.description, t.date)
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "transaction",
                operation = "CREATE",
                entityId = t.id,
                payloadJson = gson.toJson(input)
            ))
        }

        // Enqueue scheduled transactions
        val scheduled = scheduledDao.getAllSync()
        for (s in scheduled) {
            if (!s.isLocalOnly) continue
            val input = ScheduledInput(s.accountId, s.categoryId, s.type, s.amount, s.currency, s.description, s.rrule, s.nextOccurrence, s.maxOccurrences)
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "scheduled",
                operation = "CREATE",
                entityId = s.id,
                payloadJson = gson.toJson(input)
            ))
        }
    }

    suspend fun sync(): Result<Unit> {
        val pushResult = pushPendingOperations()
        if (pushResult.isFailure) return pushResult
        return pull()
    }

    private fun AccountDto.toEntity() = AccountEntity(
        id = id, userId = userId, name = name, currency = currency,
        balance = balance, createdAt = createdAt, updatedAt = updatedAt,
        deletedAt = deletedAt, syncVersion = syncVersion
    )

    private fun CategoryDto.toEntity() = CategoryEntity(
        id = id, userId = userId, parentId = parentId, name = name,
        type = type, icon = icon, color = color, createdAt = createdAt,
        updatedAt = updatedAt, deletedAt = deletedAt, syncVersion = syncVersion
    )

    private fun TransactionDto.toEntity() = TransactionEntity(
        id = id, accountId = accountId, categoryId = categoryId,
        userId = userId, type = type, amount = amount, currency = currency,
        description = description, date = date, createdAt = createdAt,
        updatedAt = updatedAt, deletedAt = deletedAt, syncVersion = syncVersion
    )

    private fun ScheduledTransactionDto.toEntity() = ScheduledTransactionEntity(
        id = id, accountId = accountId, categoryId = categoryId,
        userId = userId, type = type, amount = amount, currency = currency,
        description = description, rrule = rrule, nextOccurrence = nextOccurrence,
        active = active, createdAt = createdAt, updatedAt = updatedAt,
        deletedAt = deletedAt, syncVersion = syncVersion
    )
}
