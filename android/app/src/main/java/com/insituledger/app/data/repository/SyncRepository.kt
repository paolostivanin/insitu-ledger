package com.insituledger.app.data.repository

import androidx.room.withTransaction
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.*
import com.insituledger.app.data.local.db.entity.*
import com.insituledger.app.data.remote.api.*
import com.insituledger.app.data.remote.dto.*
import com.insituledger.app.util.ServerVersion
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // 5xx, 429, 401, IOException, network timeout. Op stays queued and
    // SyncWorker returns retry() up to MAX_ATTEMPTS. stopsDrain (429 rate
    // limit, 401 signed out) also ends the drain: every further request
    // would be refused too.
    data class Transient(val message: String, val stopsDrain: Boolean = false) : PushOutcome()
}

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_UNAUTHORIZED = 401

// Ids per coalesced batch-update / batch-delete request.
internal const val MAX_PUSH_BATCH = 500

// The fields a PATCH op can carry (BulkPatchPayload's JSON names).
private val PATCH_FIELDS = listOf("description", "category_id")

private fun rateLimitedOutcome(): PushOutcome =
    PushOutcome.Transient("HTTP $HTTP_TOO_MANY_REQUESTS", stopsDrain = true)

// The session is gone (expired or revoked; AuthInterceptor signs out). The op
// is fine, the credentials aren't: keep it queued for after the next login.
// Failing it used to send the user to Settings → Retry, which (with the CREATE
// re-queued at login) produced duplicate server rows.
private fun signedOutOutcome(): PushOutcome =
    PushOutcome.Transient("HTTP $HTTP_UNAUTHORIZED (signed out)", stopsDrain = true)

private fun outcomeForCreateOrOwnedCategoryMutation(response: Response<*>): PushOutcome {
    if (response.isSuccessful) return PushOutcome.Success
    val code = response.code()
    return when {
        code == HTTP_TOO_MANY_REQUESTS -> rateLimitedOutcome()
        code == HTTP_UNAUTHORIZED -> signedOutOutcome()
        code in 400..499 -> PushOutcome.Permanent(code, "HTTP $code")
        else -> PushOutcome.Transient("HTTP $code")
    }
}

private fun outcomeForAccessScopedMutation(response: Response<*>): PushOutcome {
    if (response.isSuccessful) return PushOutcome.Success
    val code = response.code()
    return when {
        code == 403 || code == 404 -> PushOutcome.LostAccess
        code == HTTP_TOO_MANY_REQUESTS -> rateLimitedOutcome()
        code == HTTP_UNAUTHORIZED -> signedOutOutcome()
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
        code == HTTP_TOO_MANY_REQUESTS -> rateLimitedOutcome()
        code == HTTP_UNAUTHORIZED -> signedOutOutcome()
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
    // Serialises drains. syncNow() is called straight from ViewModels as well
    // as from the workers; two concurrent drains could push the same op twice
    // or push an entity's ops out of order.
    private val syncMutex = Mutex()

    // Process the pending-op queue with re-read between rounds so that ID
    // remaps performed by a successful CREATE become visible to dependent
    // UPDATE/DELETE/PATCH/CREATE ops in the same drain. A non-CREATE op that
    // still carries a negative ID is deferred (the CREATE it depends on may
    // run later this round). The loop terminates when no op succeeded in a
    // full pass — at that point any still-deferred op is reported as failed
    // ("blocked by unsynced CREATE") because the CREATE it depends on either
    // failed permanently or was never enqueued.
    //
    // Ordering, per entity (entityKey):
    //  - ops are read in queue (id) order, never by wall-clock created_at;
    //  - after an op fails transiently or is deferred, the entity's newer ops
    //    are skipped for the rest of the drain and stay pending behind it;
    //  - nothing ever waits behind a permanently failed op. Instead, when an
    //    op succeeds, the entity's older ops (pending or failed) that it
    //    supersedes are dropped or trimmed in the same Room transaction (see
    //    supersedeOlderOps), so retrying what is left can never regress a
    //    field a newer successful op already set.
    // A 429 ends the drain at once; everything not yet sent stays pending.
    //
    // Runs of identical PATCH ops, and runs of transaction DELETEs, go out as
    // one batch request of up to MAX_PUSH_BATCH ids (see pushGroup): a bulk
    // edit of thousands of rows must not spend thousands of requests against
    // the server's per-IP rate limit.
    suspend fun pushPendingOperations(): Result<Unit> {
        return try {
            val drain = Drain()
            drain.run()
            drain.result()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private inner class Drain {
        val processedIds = mutableSetOf<Long>()
        // Failed this drain: permanent ones left the active queue, transient
        // ones stay pending for the next drain.
        val failedIds = mutableSetOf<Long>()
        // Held back behind a transient failure of the same entity; still pending.
        val skippedIds = mutableSetOf<Long>()
        val failureMessages = mutableListOf<String>()
        var transientCount = 0
        var permanentCount = 0
        var lostAccessCount = 0
        // Entities with an op that failed transiently or was deferred in this
        // drain: their newer ops must not overtake it.
        val heldKeys = mutableSetOf<String>()
        var anySucceeded = false
        var anyDeferred = false

        suspend fun run() {
            while (true) {
                val ops = pendingOpDao.getAll().filter {
                    it.id !in processedIds && it.id !in failedIds && it.id !in skippedIds
                }
                if (ops.isEmpty()) return
                anySucceeded = false
                anyDeferred = false
                if (round(ops)) return // rate limited
                if (!anySucceeded) {
                    if (anyDeferred && transientCount == 0) failStuckDeferred()
                    return
                }
            }
        }

        fun result(): Result<Unit> {
            val realFailures = transientCount + permanentCount
            return if (realFailures == 0) {
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
        }

        /** One pass over [ops]. Returns true when the drain must stop (429). */
        private suspend fun round(ops: List<PendingOperationEntity>): Boolean {
            var i = 0
            while (i < ops.size) {
                // Re-read each op just before sending: a prior op in this
                // round may have rewritten its payload_json (FK remap, or a
                // newer op's success trimmed it), bumped its entity_id away
                // from the negative placeholder, or removed it.
                val op = pendingOpDao.getById(ops[i++].id) ?: continue
                if (op.state != PendingOperationEntity.STATE_PENDING) continue
                val key = entityKey(op)
                // A CREATE starts its entity's history, so nothing queued for
                // that id can be ahead of it.
                if (op.operation != "CREATE") {
                    if (effectiveId(op) < 0) {
                        anyDeferred = true
                        heldKeys.add(key)
                        continue
                    }
                    if (key in heldKeys) {
                        skippedIds.add(op.id)
                        continue
                    }
                }
                if (!isBatchable(op)) {
                    if (pushSingle(op)) return true
                    continue
                }
                // Extend with the consecutive pending ops that can share the
                // request: same type, operation and payload bytes, synced, not
                // held back, and at most one op per entity (two ops of one
                // entity must stay ordered, not land in one statement).
                val group = mutableListOf(op)
                val groupKeys = mutableSetOf(key)
                while (i < ops.size && group.size < MAX_PUSH_BATCH) {
                    val next = pendingOpDao.getById(ops[i].id)
                    if (next == null || next.state != PendingOperationEntity.STATE_PENDING) {
                        i++ // no longer queued: not in the way
                        continue
                    }
                    val nextKey = entityKey(next)
                    if (next.entityType != op.entityType || next.operation != op.operation ||
                        next.payloadJson != op.payloadJson || effectiveId(next) <= 0 ||
                        nextKey in heldKeys || nextKey in groupKeys
                    ) break
                    group += next
                    groupKeys += nextKey
                    i++
                }
                if (pushGroup(group)) return true
            }
            return false
        }

        /** Returns true when the drain must stop (429). */
        private suspend fun pushSingle(op: PendingOperationEntity): Boolean {
            val outcome = try {
                when (op.entityType) {
                    "account" -> pushAccountOp(op)
                    "category" -> pushCategoryOp(op)
                    "transaction" -> pushTransactionOp(op)
                    "scheduled" -> pushScheduledOp(op)
                    // Never silently drop an op this build can't push.
                    else -> PushOutcome.Permanent(0, "unknown entity type")
                }
            } catch (e: CancellationException) {
                // Cancelled drain (WorkManager REPLACE, scope gone): unwind,
                // the op stays pending. Not a push failure.
                throw e
            } catch (e: Exception) {
                // Network/IO errors are transient by default; the
                // SyncWorker decides whether to retry.
                PushOutcome.Transient(e.message ?: "exception")
            }
            return record(op, outcome)
        }

        /**
         * Sends a run of ops as one batch request. A run of one goes through
         * the single-op path unchanged. 2xx: every op succeeded. 429: stop,
         * all stay pending. Anything else: push each op alone, so one bad id
         * (e.g. a 403 on an account the user lost) fails only its own op.
         */
        private suspend fun pushGroup(group: List<PendingOperationEntity>): Boolean {
            if (group.size == 1) return pushSingle(group.single())
            val result = try {
                sendBatch(group)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BatchResult.FALL_BACK
            }
            return when (result) {
                BatchResult.OK -> {
                    succeed(group)
                    false
                }
                BatchResult.RATE_LIMITED, BatchResult.SIGNED_OUT -> {
                    val reason = if (result == BatchResult.SIGNED_OUT) {
                        "HTTP $HTTP_UNAUTHORIZED (signed out)"
                    } else {
                        "HTTP $HTTP_TOO_MANY_REQUESTS"
                    }
                    for (op in group) {
                        failedIds.add(op.id)
                        heldKeys.add(entityKey(op))
                        failureMessages.add("${opSummary(op)}: $reason")
                        transientCount++
                    }
                    true
                }
                BatchResult.FALL_BACK -> pushEachAlone(group)
            }
        }

        private suspend fun pushEachAlone(group: List<PendingOperationEntity>): Boolean {
            for (op in group) {
                if (pushSingle(op)) return true
            }
            return false
        }

        /** Books [outcome] for [op]. Returns true when the drain must stop (429). */
        private suspend fun record(op: PendingOperationEntity, outcome: PushOutcome): Boolean {
            when (outcome) {
                is PushOutcome.Success -> succeed(listOf(op))
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
                    val message = "${opSummary(op)}: ${outcome.message}"
                    pendingOpDao.markFailed(op.id, message)
                    failedIds.add(op.id)
                    failureMessages.add(message)
                    permanentCount++
                }
                is PushOutcome.Transient -> {
                    failedIds.add(op.id)
                    heldKeys.add(entityKey(op))
                    failureMessages.add("${opSummary(op)}: ${outcome.message}")
                    transientCount++
                    return outcome.stopsDrain
                }
            }
            return false
        }

        /**
         * Dequeues the pushed ops and, in the same transaction, drops or
         * trims each one's older ops it supersedes.
         */
        private suspend fun succeed(ops: List<PendingOperationEntity>) {
            database.withTransaction {
                for (op in ops) {
                    if (op.operation == "CREATE") dropOtherCreates(op)
                    pendingOpDao.delete(op)
                    supersedeOlderOps(op)
                }
            }
            for (op in ops) processedIds.add(op.id)
            anySucceeded = true
        }

        // The row now exists on the server, so any other CREATE queued for it
        // (older builds re-queued one at login with a fresh client_id) would
        // create a second server row. The remap has already moved them from
        // the old local id onto the new server id; drop them under either.
        private suspend fun dropOtherCreates(op: PendingOperationEntity) {
            val remappedId = pendingOpDao.getById(op.id)?.entityId
            val ids = listOfNotNull(op.entityId, remappedId).distinct()
            pendingOpDao.deleteOtherCreates(op.entityType, ids, keepId = op.id)
        }

        // No CREATE progressed this round, so the CREATE every remaining
        // deferred op depends on will never produce a positive ID this drain.
        // Surface them as permanent for the report; the CREATE itself is
        // already counted (as Transient or Permanent) so retry behaviour is
        // driven by the CREATE's own classification.
        private suspend fun failStuckDeferred() {
            val stuck = pendingOpDao.getAll().filter {
                it.id !in processedIds &&
                    it.id !in failedIds &&
                    it.operation != "CREATE" &&
                    effectiveId(it) < 0
            }
            for (op in stuck) {
                val message = opSummary(op) + ": blocked by unsynced CREATE"
                pendingOpDao.markFailed(op.id, message)
                failureMessages.add(message)
                permanentCount++
                failedIds.add(op.id)
            }
        }
    }

    /**
     * [op] for entity K just succeeded. K's older ops (smaller id, pending or
     * failed, never CREATE) are what a retry could still send after it, so
     * make each one unable to undo [op]:
     *  - DELETE: drop them all; the row is gone.
     *  - full UPDATE: drop older UPDATEs and PATCHes. Its payload was built
     *    from the local row, which already held every earlier local edit.
     *  - PATCH with fields F: strip F from older PATCHes (dropping any left
     *    empty) and overwrite F in older UPDATE payloads with [op]'s values.
     * Must run in the transaction that dequeues [op].
     */
    private suspend fun supersedeOlderOps(op: PendingOperationEntity) {
        if (op.operation == "CREATE") return
        val older = pendingOpDao.getOlderOpsForEntity(op.entityType, effectiveId(op), op.id)
        if (older.isEmpty()) return
        when (op.operation) {
            "DELETE" -> older.forEach { pendingOpDao.delete(it) }
            "UPDATE" -> older
                .filter { it.operation == "UPDATE" || it.operation == "PATCH" }
                .forEach { pendingOpDao.delete(it) }
            "PATCH" -> {
                val fields = patchFields(op.payloadJson)
                if (fields.isEmpty()) return
                for (o in older) {
                    when (o.operation) {
                        "PATCH" -> {
                            val obj = jsonObjectOrNull(o.payloadJson) ?: continue
                            fields.keys.forEach { obj.remove(it) }
                            if (patchFields(obj).isEmpty()) pendingOpDao.delete(o)
                            else pendingOpDao.updatePayloadJson(o.id, gson.toJson(obj))
                        }
                        "UPDATE" -> {
                            val obj = jsonObjectOrNull(o.payloadJson) ?: continue
                            fields.forEach { (name, value) -> obj.add(name, value.deepCopy()) }
                            pendingOpDao.updatePayloadJson(o.id, gson.toJson(obj))
                        }
                    }
                }
            }
        }
    }

    private enum class BatchResult { OK, RATE_LIMITED, SIGNED_OUT, FALL_BACK }

    /** One request for a run of batchable ops (see isBatchable) of distinct, synced entities. */
    private suspend fun sendBatch(group: List<PendingOperationEntity>): BatchResult {
        val first = group.first()
        val ids = group.map { effectiveId(it) }
        val response = if (first.operation == "DELETE") {
            transactionApi.batchDelete(BatchDeleteRequest(ids))
        } else {
            val patch = parsePatch(first) ?: return BatchResult.FALL_BACK
            if (first.entityType == "transaction") {
                transactionApi.batchUpdate(BatchUpdateRequest(
                    ids = ids, description = patch.description, categoryId = patch.categoryId
                ))
            } else {
                scheduledApi.batchUpdate(ScheduledBatchUpdateRequest(
                    ids = ids, description = patch.description, categoryId = patch.categoryId
                ))
            }
        }
        return when {
            response.isSuccessful -> BatchResult.OK
            // batch-delete skips ids that are already gone; a 404 is the same
            // end state, as for a single DELETE.
            first.operation == "DELETE" && response.code() == 404 -> BatchResult.OK
            response.code() == HTTP_TOO_MANY_REQUESTS -> BatchResult.RATE_LIMITED
            response.code() == HTTP_UNAUTHORIZED -> BatchResult.SIGNED_OUT
            else -> BatchResult.FALL_BACK
        }
    }

    private fun jsonObjectOrNull(json: String?): JsonObject? {
        if (json == null) return null
        return runCatching { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
    }

    /** The patch fields a PATCH payload sets (absent or null = unchanged). */
    private fun patchFields(json: String?): Map<String, JsonElement> =
        jsonObjectOrNull(json)?.let { patchFields(it) } ?: emptyMap()

    private fun patchFields(obj: JsonObject): Map<String, JsonElement> =
        PATCH_FIELDS.mapNotNull { name -> obj.get(name)?.takeIf { !it.isJsonNull }?.let { name to it } }.toMap()

    // Coalescable into one batch request: PATCHes (batch-update) and
    // transaction DELETEs (batch-delete). An unreadable PATCH goes alone and
    // fails as "empty patch" without a request.
    private fun isBatchable(op: PendingOperationEntity): Boolean = when (op.operation) {
        "PATCH" -> (op.entityType == "transaction" || op.entityType == "scheduled") && parsePatch(op) != null
        "DELETE" -> op.entityType == "transaction"
        else -> false
    }

    private fun effectiveId(op: PendingOperationEntity): Long = op.serverId ?: op.entityId

    // FIFO key. After a CREATE remap entity_id carries the server id, and
    // server_id (when set) always equals it, so both spellings of one row
    // share a key.
    private fun entityKey(op: PendingOperationEntity): String =
        "${op.entityType}:${effectiveId(op)}"

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
            else -> unknownOperation(op)
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
            else -> unknownOperation(op)
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
            "PATCH" -> {
                // server_id stays null for a row that was still local when
                // the op was queued; the CREATE's remap rewrites entity_id.
                val id = op.serverId ?: op.entityId
                if (id < 0) return PushOutcome.Permanent(0, "unsynced parent")
                val patch = parsePatch(op) ?: return PushOutcome.Permanent(0, "empty patch")
                outcomeForAccessScopedMutation(transactionApi.batchUpdate(BatchUpdateRequest(
                    ids = listOf(id), description = patch.description, categoryId = patch.categoryId
                )))
            }
            else -> unknownOperation(op)
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
            "PATCH" -> {
                // Description/category only: the server never touches
                // next_occurrence, rrule or the counters on this endpoint.
                val id = op.serverId ?: op.entityId
                if (id < 0) return PushOutcome.Permanent(0, "unsynced parent")
                val patch = parsePatch(op) ?: return PushOutcome.Permanent(0, "empty patch")
                outcomeForAccessScopedMutation(scheduledApi.batchUpdate(ScheduledBatchUpdateRequest(
                    ids = listOf(id), description = patch.description, categoryId = patch.categoryId
                )))
            }
            else -> unknownOperation(op)
        }
    }

    // An op type this build doesn't know (e.g. queued by a newer version that
    // was then downgraded) must stay visible as failed, never be dropped.
    private fun unknownOperation(op: PendingOperationEntity): PushOutcome =
        PushOutcome.Permanent(0, "unknown operation ${op.operation}")

    // null when the payload is unreadable or asks for no change: the server
    // would answer 400 "nothing to update" anyway.
    private fun parsePatch(op: PendingOperationEntity): BulkPatchPayload? {
        val payload = op.payloadJson ?: return null
        val patch = runCatching { gson.fromJson(payload, BulkPatchPayload::class.java) }.getOrNull() ?: return null
        return patch.takeIf { it.description != null || it.categoryId != null }
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
            // Keep locally materialized entries pointing at their schedule
            // (and away from a future local schedule that reuses the id).
            transactionDao.updateScheduledId(oldId, newId)
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

    suspend fun pull(allowBackfill: Boolean = true): Result<Unit> = try {
        val backfill = allowBackfill && !prefs.fullSnapshotDoneFlow.first()
        val data = fetchSyncData(backfill)
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

        prefs.saveSyncCheckpoint(data.currentVersion, backfill)
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

    private suspend fun fetchSyncData(backfill: Boolean = false): SyncResponse {
        val lastVersion = prefs.lastSyncVersionFlow.first()
        // A full snapshot, once per install (see UserPreferences.FULL_SNAPSHOT_DONE),
        // repairs rows an incremental pull won't resend: creator names older
        // versions discarded and finished schedules they purged. A failed pull
        // leaves the flag unset.
        val since = if (backfill) 0L else lastVersion
        val response = syncApi.sync(since)
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
            enqueueCreate("account", a.id, gson.toJson(AccountInput(a.name, a.currency, a.balance)))
        }

        // Enqueue categories next (transactions/scheduled reference them)
        for (c in categoryDao.getAllSync()) {
            if (!c.isLocalOnly) continue
            enqueueCreate("category", c.id, gson.toJson(CategoryInput(c.parentId, c.name, c.type, c.icon, c.color)))
        }

        // Enqueue transactions
        for (t in transactionDao.getAllSync()) {
            if (!t.isLocalOnly) continue
            enqueueCreate("transaction", t.id, gson.toJson(
                TransactionInput(t.accountId, t.categoryId, t.type, t.amount, t.currency, t.description, t.note, t.date)
            ))
        }

        // Enqueue scheduled transactions
        for (s in scheduledDao.getAllSync()) {
            if (!s.isLocalOnly) continue
            enqueueCreate("scheduled", s.id, gson.toJson(
                ScheduledInput(s.accountId, s.categoryId, s.type, s.amount, s.currency, s.description, s.note, s.rrule, s.nextOccurrence, s.maxOccurrences)
            ))
        }
    }

    // Exactly one CREATE per local-only row, whatever state earlier attempts
    // are in. A second CREATE with a fresh client_id is how "logged out by a
    // 401 → log in → Settings Retry" used to create duplicate server rows.
    //  - A pending CREATE is kept as is: it may already have reached the
    //    server (lost response), and its client_id lets the server dedupe it.
    //    Failed duplicates next to it are dropped.
    //  - Otherwise the oldest failed CREATE is re-queued with the row's
    //    current values and its original client_id; other failed duplicates
    //    are dropped. A failed CREATE got a 4xx, so it never created a row.
    //  - Only a row with no CREATE at all gets a new one.
    private suspend fun enqueueCreate(entityType: String, entityId: Long, payloadJson: String) {
        database.withTransaction {
            val existing = pendingOpDao.findCreatesAnyState(entityType, entityId)
            val keep = existing.firstOrNull { it.state == PendingOperationEntity.STATE_PENDING }
                ?: existing.firstOrNull()
            when {
                keep == null -> pendingOpDao.insert(PendingOperationEntity(
                    entityType = entityType,
                    operation = "CREATE",
                    entityId = entityId,
                    payloadJson = payloadJson,
                    clientId = UUID.randomUUID().toString()
                ))
                keep.state != PendingOperationEntity.STATE_PENDING -> pendingOpDao.requeueCreate(keep.id, payloadJson)
            }
            if (keep != null && existing.size > 1) {
                pendingOpDao.deleteOtherCreates(entityType, listOf(entityId), keepId = keep.id)
            }
        }
    }

    suspend fun sync(): Result<Unit> = syncMutex.withLock { syncLocked() }

    private suspend fun syncLocked(): Result<Unit> {
        refreshServerVersion()
        val pushResult = pushPendingOperations()
        if (pushResult.isSuccess) return pull()

        val pushError = pushResult.exceptionOrNull() as? SyncPushException
            ?: return pushResult
        val pullResult = when {
            // Permanent operations have left the active queue, so server truth
            // can be applied immediately and future syncs are not wedged.
            pushError.transientCount == 0 -> pull(allowBackfill = false)
            // Preserve active transient edits, but never let them retain data
            // from an account whose access was revoked in the same batch.
            pushError.lostAccessCount > 0 -> pullAccessLossOnly()
            else -> null
        }
        return if (pullResult?.isFailure == true) pullResult else pushResult
    }

    /**
     * Remembers the server's version (GET /api/health) for the bulk-edit
     * gate; a server that reports none is older than 1.27. Best effort: a
     * failure leaves the stored value alone and never fails the sync.
     */
    private suspend fun refreshServerVersion() {
        try {
            val response = syncApi.health()
            if (!response.isSuccessful) return
            val body = response.body() ?: return
            prefs.saveServerVersion(body.version?.trim()?.takeIf { it.isNotEmpty() } ?: ServerVersion.OLD)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unreachable, unparsable or not a JSON body: keep what we knew.
        }
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
        createdByUserId = createdByUserId, createdByName = createdByName,
        scheduledId = scheduledId, occurrence = occurrence, occurrenceTotal = occurrenceTotal
    )

    private fun ScheduledTransactionDto.toEntity() = ScheduledTransactionEntity(
        id = id, accountId = accountId, categoryId = categoryId,
        userId = userId, type = type, amount = amount, currency = currency,
        description = description, note = note, rrule = rrule, nextOccurrence = nextOccurrence,
        active = active, maxOccurrences = maxOccurrences, occurrenceCount = occurrenceCount,
        createdAt = createdAt, updatedAt = updatedAt,
        deletedAt = deletedAt, syncVersion = syncVersion,
        createdByUserId = createdByUserId, createdByName = createdByName
    )
}
