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

// Per-op result. Lets pushPendingOperations distinguish a "you no longer have
// access" failure (dead-letter the op, let the next pull confirm a tombstone) from
// a transient 5xx (retry later) from a permanent 4xx (needs user attention).
// Without this, every HTTP error reduced to the same opaque "server rejected"
// and SyncWorker retried 5x regardless of whether retry could possibly help.
sealed class PushOutcome {
    object Success : PushOutcome()
    // 403/404 on an account-scoped mutation may mean the guest lost access to
    // the parent resource. Keep the op visible as failed until a pull confirms
    // the tombstone; purgeAccountLocally then removes it automatically.
    object LostAccess : PushOutcome()
    // Other 4xx (400 validation, 409 conflict). Op moves to the visible failed
    // queue and SyncWorker does NOT retry — backoff can't help.
    data class Permanent(val code: Int, val message: String) : PushOutcome()
    // 5xx, IOException, network timeout. Op stays queued and SyncWorker
    // returns retry() up to MAX_ATTEMPTS.
    data class Transient(val message: String) : PushOutcome()
}

private fun outcomeForCreateOrOwnedCategoryMutation(response: Response<*>): PushOutcome {
    if (response.isSuccessful) return PushOutcome.Success
    val code = response.code()
    return when {
        code in 400..499 -> PushOutcome.Permanent(code, "HTTP $code")
        else -> PushOutcome.Transient("HTTP $code")
    }
}

private fun outcomeForAccessScopedMutation(response: Response<*>): PushOutcome {
    if (response.isSuccessful) return PushOutcome.Success
    val code = response.code()
    return when {
        code == 403 || code == 404 -> PushOutcome.LostAccess
        code in 400..499 -> PushOutcome.Permanent(code, "HTTP $code")
        else -> PushOutcome.Transient("HTTP $code")
    }
}

private fun outcomeForIdempotentDelete(
    response: Response<*>,
    forbiddenMeansLostAccess: Boolean
): PushOutcome {
    // 404 on DELETE means already gone — desired end state.
    if (response.isSuccessOrAlreadyGone()) return PushOutcome.Success
    val code = response.code()
    return when {
        code == 403 && forbiddenMeansLostAccess -> PushOutcome.LostAccess
        code in 400..499 -> PushOutcome.Permanent(code, "HTTP $code")
        else -> PushOutcome.Transient("HTTP $code")
    }
}

// Thrown by pushPendingOperations / sync when at least one queued op failed
// to push. Carries the transient/permanent split so SyncWorker can decide
// between retry() and failure() without a fragile message scrape.
class SyncPushException(
    val failedCount: Int,
    val transientCount: Int,
    val permanentCount: Int,
    val firstFailure: String,
    val lostAccessCount: Int = 0
) : Exception(
    "$failedCount sync operation(s) failed " +
        "($transientCount transient, $permanentCount permanent). First: $firstFailure"
) {
    val canRetry: Boolean get() = transientCount > 0
}

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
            var transientCount = 0
            var permanentCount = 0
            var lostAccessCount = 0

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

                    val outcome = try {
                        when (op.entityType) {
                            "account" -> pushAccountOp(op)
                            "category" -> pushCategoryOp(op)
                            "transaction" -> pushTransactionOp(op)
                            "scheduled" -> pushScheduledOp(op)
                            else -> PushOutcome.Success
                        }
                    } catch (e: Exception) {
                        // Network/IO errors are transient by default; the
                        // SyncWorker decides whether to retry.
                        PushOutcome.Transient(e.message ?: "exception")
                    }

                    when (outcome) {
                        is PushOutcome.Success -> {
                            pendingOpDao.delete(op)
                            processedIds.add(op.id)
                            anySucceeded = true
                        }
                        is PushOutcome.LostAccess -> {
                            // Do not silently drop ambiguous 403/404 responses:
                            // they can also be policy/validation failures. Move
                            // the op out of the active queue, then pull access
                            // tombstones. A confirmed account purge removes it;
                            // otherwise Settings exposes retry/discard.
                            val message = "${opSummary(op)}: possible lost access"
                            pendingOpDao.markFailed(op.id, message)
                            failedIds.add(op.id)
                            failureMessages.add(message)
                            lostAccessCount++
                            permanentCount++
                        }
                        is PushOutcome.Permanent -> {
                            failedIds.add(op.id)
                            val message = "${opSummary(op)}: ${outcome.message}"
                            pendingOpDao.markFailed(op.id, message)
                            failureMessages.add(message)
                            permanentCount++
                        }
                        is PushOutcome.Transient -> {
                            failedIds.add(op.id)
                            failureMessages.add("${opSummary(op)}: ${outcome.message}")
                            transientCount++
                        }
                    }
                }

                if (!anySucceeded) {
                    if (anyDeferred && transientCount == 0) {
                        // No CREATE progressed this round, so the CREATE every
                        // remaining deferred op depends on will never produce
                        // a positive ID this drain. Surface them as permanent
                        // for the report; the CREATE itself is already counted
                        // (as Transient or Permanent) so retry behaviour is
                        // driven by the CREATE's own classification.
                        val stuck = pendingOpDao.getAll().filter {
                            it.id !in processedIds &&
                                it.id !in failedIds &&
                                it.operation != "CREATE" &&
                                (it.serverId ?: it.entityId) < 0
                        }
                        for (op in stuck) {
                            val message = opSummary(op) + ": blocked by unsynced CREATE"
                            pendingOpDao.markFailed(op.id, message)
                            failureMessages.add(message)
                            permanentCount++
                            failedIds.add(op.id)
                        }
                    }
                    break
                }
            }

            val realFailures = transientCount + permanentCount
            if (realFailures == 0) {
                Result.success(Unit)
            } else {
                Result.failure(SyncPushException(
                    failedCount = realFailures,
                    transientCount = transientCount,
                    permanentCount = permanentCount,
                    lostAccessCount = lostAccessCount,
                    firstFailure = failureMessages.first()
                ))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun opSummary(op: PendingOperationEntity): String =
        "${op.entityType} ${op.operation} #${op.entityId}"

    private suspend fun pushAccountOp(op: PendingOperationEntity): PushOutcome {
        return when (op.operation) {
            "CREATE" -> {
                val stored = gson.fromJson(op.payloadJson, AccountInput::class.java)
                val input = stored.copy(clientId = op.clientId)
                val response = accountApi.create(input)
                if (response.isSuccessful) {
                    val serverId = response.body()?.id ?: return PushOutcome.Transient("empty body")
                    remapAccountId(op.entityId, serverId)
                    PushOutcome.Success
                } else outcomeForCreateOrOwnedCategoryMutation(response)
            }
            "UPDATE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return PushOutcome.Permanent(0, "unsynced parent")
                val input = gson.fromJson(op.payloadJson, AccountInput::class.java)
                outcomeForAccessScopedMutation(accountApi.update(id, input))
            }
            "DELETE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return PushOutcome.Permanent(0, "unsynced parent")
                outcomeForIdempotentDelete(accountApi.delete(id), forbiddenMeansLostAccess = true)
            }
            else -> PushOutcome.Success
        }
    }

    private suspend fun pushCategoryOp(op: PendingOperationEntity): PushOutcome {
        return when (op.operation) {
            "CREATE" -> {
                val stored = gson.fromJson(op.payloadJson, CategoryInput::class.java)
                val input = stored.copy(clientId = op.clientId)
                val response = categoryApi.create(input)
                if (response.isSuccessful) {
                    val serverId = response.body()?.id ?: return PushOutcome.Transient("empty body")
                    remapCategoryId(op.entityId, serverId)
                    PushOutcome.Success
                } else outcomeForCreateOrOwnedCategoryMutation(response)
            }
            "UPDATE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return PushOutcome.Permanent(0, "unsynced parent")
                val input = gson.fromJson(op.payloadJson, CategoryInput::class.java)
                outcomeForCreateOrOwnedCategoryMutation(categoryApi.update(id, input))
            }
            "DELETE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return PushOutcome.Permanent(0, "unsynced parent")
                outcomeForIdempotentDelete(categoryApi.delete(id), forbiddenMeansLostAccess = false)
            }
            else -> PushOutcome.Success
        }
    }

    private suspend fun pushTransactionOp(op: PendingOperationEntity): PushOutcome {
        return when (op.operation) {
            "CREATE" -> {
                val stored = gson.fromJson(op.payloadJson, TransactionInput::class.java)
                val input = stored.copy(clientId = op.clientId)
                val response = transactionApi.create(input)
                if (!response.isSuccessful) return outcomeForCreateOrOwnedCategoryMutation(response)
                val body = response.body() ?: return PushOutcome.Transient("empty body")
                if (body.scheduled) {
                    // Server converted our transaction CREATE into a scheduled
                    // tx (shouldn't happen post-1.18 backend, but be defensive
                    // — pre-1.18 this caused the phantom-id duplicate bug).
                    // Drop the local optimistic transactions row; the next
                    // pull will bring back the scheduled_transactions row.
                    transactionDao.deleteById(op.entityId)
                    return PushOutcome.Success
                }
                remapTransactionId(op.entityId, body.id)
                PushOutcome.Success
            }
            "UPDATE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return PushOutcome.Permanent(0, "unsynced parent")
                val input = gson.fromJson(op.payloadJson, TransactionInput::class.java)
                outcomeForAccessScopedMutation(transactionApi.update(id, input))
            }
            "DELETE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return PushOutcome.Permanent(0, "unsynced parent")
                outcomeForIdempotentDelete(transactionApi.delete(id), forbiddenMeansLostAccess = true)
            }
            else -> PushOutcome.Success
        }
    }

    private suspend fun pushScheduledOp(op: PendingOperationEntity): PushOutcome {
        return when (op.operation) {
            "CREATE" -> {
                val stored = gson.fromJson(op.payloadJson, ScheduledInput::class.java)
                val input = stored.copy(clientId = op.clientId)
                val response = scheduledApi.create(input)
                if (response.isSuccessful) {
                    val serverId = response.body()?.id ?: return PushOutcome.Transient("empty body")
                    remapScheduledId(op.entityId, serverId)
                    PushOutcome.Success
                } else outcomeForCreateOrOwnedCategoryMutation(response)
            }
            "UPDATE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return PushOutcome.Permanent(0, "unsynced parent")
                val input = gson.fromJson(op.payloadJson, ScheduledInput::class.java)
                outcomeForAccessScopedMutation(scheduledApi.update(id, input))
            }
            "DELETE" -> {
                val id = op.serverId ?: op.entityId
                if (id < 0) return PushOutcome.Permanent(0, "unsynced parent")
                outcomeForIdempotentDelete(scheduledApi.delete(id), forbiddenMeansLostAccess = true)
            }
            else -> PushOutcome.Success
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

    suspend fun pull(): Result<Unit> = try {
        val data = fetchSyncData()
        database.withTransaction {
            // Purge access-lost accounts before generic tombstone cleanup. The
            // dependent rows still exist here, so their queued operations can
            // be found and deleted before purgeDeleted removes their IDs.
            purgeAccessLostAccounts(data)

            val accountEntities = data.accounts.map { it.toEntity() }
            if (accountEntities.isNotEmpty()) {
                accountDao.upsertAll(accountEntities)
                accountDao.purgeDeleted()
            }

            val categoryEntities = data.categories.map { it.toEntity() }
            if (categoryEntities.isNotEmpty()) {
                categoryDao.upsertAll(categoryEntities)
                categoryDao.purgeDeleted()
            }

            val transactionEntities = data.transactions.map { it.toEntity() }
            if (transactionEntities.isNotEmpty()) {
                transactionDao.upsertAll(transactionEntities)
                transactionDao.purgeDeleted()
            }

            val scheduledEntities = data.scheduledTransactions.map { it.toEntity() }
            if (scheduledEntities.isNotEmpty()) {
                scheduledDao.upsertAll(scheduledEntities)
                scheduledDao.purgeDeleted()
            }
        }

        prefs.saveLastSyncVersion(data.currentVersion)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    // When a batch contains LostAccess plus an unrelated transient failure,
    // applying the full pull could overwrite a still-pending local edit. Fetch
    // the same incremental response but apply only account-access tombstones;
    // do not advance the cursor, so the next clean pull replays all other rows.
    private suspend fun pullAccessLossOnly(): Result<Unit> = try {
        val data = fetchSyncData()
        database.withTransaction {
            purgeAccessLostAccounts(data)
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun fetchSyncData(): SyncResponse {
        val lastVersion = prefs.lastSyncVersionFlow.first()
        val response = syncApi.sync(lastVersion)
        if (!response.isSuccessful) {
            throw Exception("Sync failed: ${response.code()}")
        }
        return response.body() ?: throw Exception("Empty sync response body")
    }

    private suspend fun purgeAccessLostAccounts(data: SyncResponse) {
        val tombstonedAccountIds = data.accounts
            .filter { it.deletedAt != null }
            .map { it.id }
        val accountsToPurge = (data.revokedAccountIds.orEmpty() + tombstonedAccountIds).distinct()
        for (accountId in accountsToPurge) {
            purgeAccountLocally(accountId)
        }
    }

    // Wipe every local trace of an account the user can no longer reach.
    // Caller is responsible for wrapping in database.withTransaction so partial
    // wipes can't leave a dangling-FK state. Shared by:
    //   - revoked_account_ids (share revocation, v1.19)
    //   - account tombstones (owner-soft-delete cascade, v1.20)
    // Both reach this point through pull() — push() marks suspected
    // LostAccess ops failed, and this confirmed tombstone purge removes them.
    private suspend fun purgeAccountLocally(accountId: Long) {
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

    suspend fun sync(): Result<Unit> {
        val pushResult = pushPendingOperations()
        if (pushResult.isSuccess) return pull()

        val pushError = pushResult.exceptionOrNull() as? SyncPushException
            ?: return pushResult
        val pullResult = when {
            // Permanent operations have left the active queue, so server truth
            // can be applied immediately and future syncs are not wedged.
            pushError.transientCount == 0 -> pull()
            // Preserve active transient edits, but never let them retain data
            // from an account whose access was revoked in the same batch.
            pushError.lostAccessCount > 0 -> pullAccessLossOnly()
            else -> null
        }
        return if (pullResult?.isFailure == true) pullResult else pushResult
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
