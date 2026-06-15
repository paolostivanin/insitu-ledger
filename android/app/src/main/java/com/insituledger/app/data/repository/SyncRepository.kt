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
import retrofit2.Response
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// DELETE is idempotent — 404 (entity already gone) reaches the desired end state.
private fun Response<*>.isSuccessOrAlreadyGone(): Boolean =
    isSuccessful || code() == 404

// Thrown by pushPendingOperations / sync when at least one queued op failed to
// push (HTTP error or thrown exception). SettingsViewModel surfaces the message
// to the user; SyncWorker treats it as a retryable failure subject to its own
// MAX_ATTEMPTS cap so a permanent 4xx doesn't keep WorkManager alive forever.
class SyncPushException(val failedCount: Int, val firstFailure: String) :
    Exception("$failedCount sync operation(s) failed. First: $firstFailure")

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
    // Process the pending-op queue with re-read between rounds so that ID
    // remaps performed by a successful CREATE become visible to dependent
    // UPDATE/DELETE/CREATE ops in the same drain. A UPDATE/DELETE that still
    // carries a negative ID is deferred (the CREATE it depends on may run
    // later this round). The loop terminates when no op succeeded in a full
    // pass — at that point any still-deferred op is reported as failed
    // ("blocked by unsynced CREATE") because the CREATE it depends on either
    // failed permanently or was never enqueued.
    suspend fun pushPendingOperations(): Result<Unit> {
        return try {
            val processedIds = mutableSetOf<Long>()
            val failedIds = mutableSetOf<Long>()
            val failureMessages = mutableListOf<String>()

            while (true) {
                val ops = pendingOpDao.getAll().filter {
                    it.id !in processedIds && it.id !in failedIds
                }
                if (ops.isEmpty()) break

                var anySucceeded = false
                var anyDeferred = false

                for (snapshot in ops) {
                    // Re-read each op just before sending: a prior op in this
                    // round may have rewritten its payload_json (FK remap) or
                    // bumped its entity_id away from the negative placeholder.
                    val op = pendingOpDao.getById(snapshot.id) ?: continue
                    if (op.operation != "CREATE" && (op.serverId ?: op.entityId) < 0) {
                        anyDeferred = true
                        continue
                    }

                    val ok = try {
                        when (op.entityType) {
                            "account" -> pushAccountOp(op)
                            "category" -> pushCategoryOp(op)
                            "transaction" -> pushTransactionOp(op)
                            "scheduled" -> pushScheduledOp(op)
                            else -> true
                        }
                    } catch (e: Exception) {
                        failedIds.add(op.id)
                        failureMessages.add(opSummary(op) + ": " + (e.message ?: "exception"))
                        continue
                    }

                    if (ok) {
                        pendingOpDao.delete(op)
                        processedIds.add(op.id)
                        anySucceeded = true
                    } else {
                        failedIds.add(op.id)
                        failureMessages.add(opSummary(op) + ": server rejected")
                    }
                }

                if (!anySucceeded) {
                    if (anyDeferred) {
                        // No CREATE progressed this round, so the CREATE every
                        // remaining deferred op depends on will never produce
                        // a positive ID. Surface them as failures.
                        val stuck = pendingOpDao.getAll().filter {
                            it.id !in processedIds &&
                                it.id !in failedIds &&
                                it.operation != "CREATE" &&
                                (it.serverId ?: it.entityId) < 0
                        }
                        for (op in stuck) {
                            failureMessages.add(opSummary(op) + ": blocked by unsynced CREATE")
                        }
                    }
                    break
                }
            }

            if (failureMessages.isEmpty()) {
                Result.success(Unit)
            } else {
                Result.failure(SyncPushException(failureMessages.size, failureMessages.first()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun opSummary(op: PendingOperationEntity): String =
        "${op.entityType} ${op.operation} #${op.entityId}"

    private suspend fun pushAccountOp(op: PendingOperationEntity): Boolean {
        return when (op.operation) {
            "CREATE" -> {
                val stored = gson.fromJson(op.payloadJson, AccountInput::class.java)
                val input = stored.copy(clientId = op.clientId)
                val response = accountApi.create(input)
                if (response.isSuccessful) {
                    val serverId = response.body()?.id ?: return false
                    remapAccountId(op.entityId, serverId)
                    true
                } else false
            }
            "UPDATE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return false
                val input = gson.fromJson(op.payloadJson, AccountInput::class.java)
                accountApi.update(id, input).isSuccessful
            }
            "DELETE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return false
                accountApi.delete(id).isSuccessOrAlreadyGone()
            }
            else -> true
        }
    }

    private suspend fun pushCategoryOp(op: PendingOperationEntity): Boolean {
        return when (op.operation) {
            "CREATE" -> {
                val stored = gson.fromJson(op.payloadJson, CategoryInput::class.java)
                val input = stored.copy(clientId = op.clientId)
                val response = categoryApi.create(input)
                if (response.isSuccessful) {
                    val serverId = response.body()?.id ?: return false
                    remapCategoryId(op.entityId, serverId)
                    true
                } else false
            }
            "UPDATE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return false
                val input = gson.fromJson(op.payloadJson, CategoryInput::class.java)
                categoryApi.update(id, input).isSuccessful
            }
            "DELETE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return false
                categoryApi.delete(id).isSuccessOrAlreadyGone()
            }
            else -> true
        }
    }

    private suspend fun pushTransactionOp(op: PendingOperationEntity): Boolean {
        return when (op.operation) {
            "CREATE" -> {
                val stored = gson.fromJson(op.payloadJson, TransactionInput::class.java)
                val input = stored.copy(clientId = op.clientId)
                val response = transactionApi.create(input)
                if (!response.isSuccessful) return false
                val body = response.body() ?: return false
                if (body.scheduled) {
                    // Server converted our transaction CREATE into a scheduled
                    // tx (shouldn't happen post-1.18 backend, but be defensive
                    // — pre-1.18 this caused the phantom-id duplicate bug).
                    // Drop the local optimistic transactions row; the next
                    // pull will bring back the scheduled_transactions row.
                    transactionDao.deleteById(op.entityId)
                    return true
                }
                remapTransactionId(op.entityId, body.id)
                true
            }
            "UPDATE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return false
                val input = gson.fromJson(op.payloadJson, TransactionInput::class.java)
                transactionApi.update(id, input).isSuccessful
            }
            "DELETE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return false
                transactionApi.delete(id).isSuccessOrAlreadyGone()
            }
            else -> true
        }
    }

    private suspend fun pushScheduledOp(op: PendingOperationEntity): Boolean {
        return when (op.operation) {
            "CREATE" -> {
                val stored = gson.fromJson(op.payloadJson, ScheduledInput::class.java)
                val input = stored.copy(clientId = op.clientId)
                val response = scheduledApi.create(input)
                if (response.isSuccessful) {
                    val serverId = response.body()?.id ?: return false
                    remapScheduledId(op.entityId, serverId)
                    true
                } else false
            }
            "UPDATE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return false
                val input = gson.fromJson(op.payloadJson, ScheduledInput::class.java)
                scheduledApi.update(id, input).isSuccessful
            }
            "DELETE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return false
                scheduledApi.delete(id).isSuccessOrAlreadyGone()
            }
            else -> true
        }
    }

    // After a successful push, swap the negative local ID for the server's
    // positive ID AND clear is_local_only. Without the clearLocalOnly step,
    // a future enqueueLocalDataForSync (called on every login) would re-enqueue
    // every already-synced row, causing the duplicate-expense bug. Also rewrite
    // the account_id embedded inside payload_json of dependent pending CREATEs
    // (transaction, scheduled) — otherwise the backend keeps rejecting them on
    // every retry because the FK is still the negative placeholder.
    private suspend fun remapAccountId(oldId: Long, newId: Long) {
        database.withTransaction {
            accountDao.updateId(oldId, newId)
            accountDao.clearLocalOnly(newId)
            transactionDao.updateAccountId(oldId, newId)
            scheduledDao.updateAccountId(oldId, newId)
            pendingOpDao.updateEntityId(oldId, newId, "account")
            rewriteAccountIdInPendingCreates(oldId, newId)
        }
    }

    private suspend fun remapCategoryId(oldId: Long, newId: Long) {
        database.withTransaction {
            categoryDao.updateId(oldId, newId)
            categoryDao.clearLocalOnly(newId)
            transactionDao.updateCategoryId(oldId, newId)
            scheduledDao.updateCategoryId(oldId, newId)
            pendingOpDao.updateEntityId(oldId, newId, "category")
            rewriteCategoryIdInPendingCreates(oldId, newId)
            rewriteParentIdInPendingCategoryCreates(oldId, newId)
        }
    }

    private suspend fun remapTransactionId(oldId: Long, newId: Long) {
        database.withTransaction {
            transactionDao.updateId(oldId, newId)
            transactionDao.clearLocalOnly(newId)
            pendingOpDao.updateEntityId(oldId, newId, "transaction")
        }
    }

    private suspend fun remapScheduledId(oldId: Long, newId: Long) {
        database.withTransaction {
            scheduledDao.updateId(oldId, newId)
            scheduledDao.clearLocalOnly(newId)
            pendingOpDao.updateEntityId(oldId, newId, "scheduled")
        }
    }

    // CREATE and UPDATE payloads both carry FK columns. TransactionRepository.update
    // (and the scheduled / category equivalents) serialize whatever account/category
    // id the form was holding at the time, which is the still-negative placeholder
    // when the user edits an offline-created entity before its parent has synced.
    private suspend fun rewriteAccountIdInPendingCreates(oldId: Long, newId: Long) {
        rewriteTransactionFk(oldId, newId, "CREATE", isAccount = true)
        rewriteTransactionFk(oldId, newId, "UPDATE", isAccount = true)
        rewriteScheduledFk(oldId, newId, "CREATE", isAccount = true)
        rewriteScheduledFk(oldId, newId, "UPDATE", isAccount = true)
    }

    private suspend fun rewriteCategoryIdInPendingCreates(oldId: Long, newId: Long) {
        rewriteTransactionFk(oldId, newId, "CREATE", isAccount = false)
        rewriteTransactionFk(oldId, newId, "UPDATE", isAccount = false)
        rewriteScheduledFk(oldId, newId, "CREATE", isAccount = false)
        rewriteScheduledFk(oldId, newId, "UPDATE", isAccount = false)
    }

    private suspend fun rewriteParentIdInPendingCategoryCreates(oldId: Long, newId: Long) {
        for (operation in listOf("CREATE", "UPDATE")) {
            for (op in pendingOpDao.findByEntityTypeAndOperation("category", operation)) {
                val payload = op.payloadJson ?: continue
                val input = runCatching { gson.fromJson(payload, CategoryInput::class.java) }.getOrNull() ?: continue
                if (input.parentId != oldId) continue
                pendingOpDao.updatePayloadJson(op.id, gson.toJson(input.copy(parentId = newId)))
            }
        }
    }

    private suspend fun rewriteTransactionFk(oldId: Long, newId: Long, operation: String, isAccount: Boolean) {
        for (op in pendingOpDao.findByEntityTypeAndOperation("transaction", operation)) {
            val payload = op.payloadJson ?: continue
            val input = runCatching { gson.fromJson(payload, TransactionInput::class.java) }.getOrNull() ?: continue
            val rewritten = if (isAccount) {
                if (input.accountId != oldId) continue
                input.copy(accountId = newId)
            } else {
                if (input.categoryId != oldId) continue
                input.copy(categoryId = newId)
            }
            pendingOpDao.updatePayloadJson(op.id, gson.toJson(rewritten))
        }
    }

    private suspend fun rewriteScheduledFk(oldId: Long, newId: Long, operation: String, isAccount: Boolean) {
        for (op in pendingOpDao.findByEntityTypeAndOperation("scheduled", operation)) {
            val payload = op.payloadJson ?: continue
            val input = runCatching { gson.fromJson(payload, ScheduledInput::class.java) }.getOrNull() ?: continue
            val rewritten = if (isAccount) {
                if (input.accountId != oldId) continue
                input.copy(accountId = newId)
            } else {
                if (input.categoryId != oldId) continue
                input.copy(categoryId = newId)
            }
            pendingOpDao.updatePayloadJson(op.id, gson.toJson(rewritten))
        }
    }

    suspend fun pull(): Result<Unit> {
        return try {
            val lastVersion = prefs.lastSyncVersionFlow.first()
            val response = syncApi.sync(lastVersion)
            if (!response.isSuccessful) {
                return Result.failure(Exception("Sync failed: ${response.code()}"))
            }

            val data = response.body()
                ?: return Result.failure(Exception("Empty sync response body"))

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

            // v1.19.0: purge accounts whose grant was revoked server-side.
            // All in one transaction so a crash mid-purge can't leave dangling
            // transactions pointing at a deleted account.
            val revoked = data.revokedAccountIds.orEmpty()
            if (revoked.isNotEmpty()) {
                database.withTransaction {
                    for (accountId in revoked) {
                        for (txnId in transactionDao.selectIdsByAccountId(accountId)) {
                            pendingOpDao.deleteByEntity("transaction", txnId)
                        }
                        for (schedId in scheduledDao.selectIdsByAccountId(accountId)) {
                            pendingOpDao.deleteByEntity("scheduled", schedId)
                        }
                        transactionDao.deleteByAccountId(accountId)
                        scheduledDao.deleteByAccountId(accountId)
                        pendingOpDao.deleteByEntity("account", accountId)
                        accountDao.deleteById(accountId)
                    }
                }
            }

            prefs.saveLastSyncVersion(data.currentVersion)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Called on every login to back-fill anything created while in local-only
    // mode. Must be safe to call repeatedly: skip rows whose remap already
    // cleared isLocalOnly, AND skip rows that already have a queued CREATE op
    // (e.g. an offline expense that hasn't synced yet). Otherwise a re-login
    // after a silent 401 enqueues every offline-created entity a second time.
    suspend fun enqueueLocalDataForSync() {
        // Enqueue accounts first (transactions/scheduled reference them)
        for (a in accountDao.getAllSync()) {
            if (!a.isLocalOnly) continue
            if (pendingOpDao.findByEntity("account", "CREATE", a.id) != null) continue
            val input = AccountInput(a.name, a.currency, a.balance)
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "account",
                operation = "CREATE",
                entityId = a.id,
                payloadJson = gson.toJson(input),
                clientId = UUID.randomUUID().toString()
            ))
        }

        // Enqueue categories next (transactions/scheduled reference them)
        for (c in categoryDao.getAllSync()) {
            if (!c.isLocalOnly) continue
            if (pendingOpDao.findByEntity("category", "CREATE", c.id) != null) continue
            val input = CategoryInput(c.parentId, c.name, c.type, c.icon, c.color)
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "category",
                operation = "CREATE",
                entityId = c.id,
                payloadJson = gson.toJson(input),
                clientId = UUID.randomUUID().toString()
            ))
        }

        // Enqueue transactions
        for (t in transactionDao.getAllSync()) {
            if (!t.isLocalOnly) continue
            if (pendingOpDao.findByEntity("transaction", "CREATE", t.id) != null) continue
            val input = TransactionInput(t.accountId, t.categoryId, t.type, t.amount, t.currency, t.description, t.note, t.date)
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "transaction",
                operation = "CREATE",
                entityId = t.id,
                payloadJson = gson.toJson(input),
                clientId = UUID.randomUUID().toString()
            ))
        }

        // Enqueue scheduled transactions
        for (s in scheduledDao.getAllSync()) {
            if (!s.isLocalOnly) continue
            if (pendingOpDao.findByEntity("scheduled", "CREATE", s.id) != null) continue
            val input = ScheduledInput(s.accountId, s.categoryId, s.type, s.amount, s.currency, s.description, s.note, s.rrule, s.nextOccurrence, s.maxOccurrences)
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "scheduled",
                operation = "CREATE",
                entityId = s.id,
                payloadJson = gson.toJson(input),
                clientId = UUID.randomUUID().toString()
            ))
        }
    }

    // Skip pull when push reported failures: pulling while local writes are
    // unresolved can overwrite a row whose update or delete is still pending
    // (silent reversion from the user's perspective).
    suspend fun sync(): Result<Unit> {
        val pushResult = pushPendingOperations()
        if (pushResult.isFailure) return pushResult
        return pull()
    }

    private fun AccountDto.toEntity() = AccountEntity(
        id = id, userId = userId, name = name, currency = currency,
        balance = balance, createdAt = createdAt, updatedAt = updatedAt,
        deletedAt = deletedAt, syncVersion = syncVersion,
        ownerName = ownerName ?: "",
        isShared = isShared
    )

    private fun CategoryDto.toEntity() = CategoryEntity(
        id = id, userId = userId, parentId = parentId, name = name,
        type = type, icon = icon, color = color, createdAt = createdAt,
        updatedAt = updatedAt, deletedAt = deletedAt, syncVersion = syncVersion
    )

    private fun TransactionDto.toEntity() = TransactionEntity(
        id = id, accountId = accountId, categoryId = categoryId,
        userId = userId, type = type, amount = amount, currency = currency,
        description = description, note = note, date = date, createdAt = createdAt,
        updatedAt = updatedAt, deletedAt = deletedAt, syncVersion = syncVersion,
        createdByUserId = createdByUserId
    )

    private fun ScheduledTransactionDto.toEntity() = ScheduledTransactionEntity(
        id = id, accountId = accountId, categoryId = categoryId,
        userId = userId, type = type, amount = amount, currency = currency,
        description = description, note = note, rrule = rrule, nextOccurrence = nextOccurrence,
        active = active, maxOccurrences = maxOccurrences, occurrenceCount = occurrenceCount,
        createdAt = createdAt, updatedAt = updatedAt,
        deletedAt = deletedAt, syncVersion = syncVersion,
        createdByUserId = createdByUserId
    )
}
