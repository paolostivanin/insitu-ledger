package com.insituledger.app.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.CategoryDao
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.local.db.dao.ScheduledTransactionDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import com.insituledger.app.data.local.db.entity.PendingOperationEntity
import com.insituledger.app.data.local.db.entity.ScheduledTransactionEntity
import com.insituledger.app.data.local.db.entity.TransactionEntity
import com.insituledger.app.data.remote.dto.BulkPatchPayload
import com.insituledger.app.data.sync.SyncManager
import com.insituledger.app.domain.model.Category
import com.insituledger.app.util.FieldLimits
import com.insituledger.app.util.ServerVersion
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Same cap as the server's maxBatchIDs. */
const val BULK_EDIT_MAX_IDS = 5000

/** First server with POST /api/transactions/batch-update and /api/scheduled/batch-update. */
const val BULK_EDIT_MIN_SERVER_VERSION = "1.27.0"

/** Rejection messages. Same rules and wording as POST /api/transactions/batch-update. */
object BulkEditMessages {
    const val IDS_REQUIRED = "ids required"
    const val NOTHING_TO_UPDATE = "nothing to update: set description and/or category_id"
    const val TOO_MANY_IDS = "too many ids (max $BULK_EDIT_MAX_IDS)"
    const val DESCRIPTION_EMPTY = "description must not be empty"
    const val DESCRIPTION_TOO_LONG = "description must not exceed ${FieldLimits.MAX_DESCRIPTION_BYTES} characters"
    const val INVALID_CATEGORY = "invalid category_id"
    // Android only: a PATCH must never carry a negative (unsynced) foreign key.
    const val CATEGORY_NOT_SYNCED = "this category hasn't synced yet; sync, then try again"
    const val MULTIPLE_OWNERS = "selected entries belong to different owners; change the category for one owner at a time"
    const val CATEGORY_NOT_FOUND = "category not found"
    const val MIXED_TYPES = "selected entries mix income and expense; change the category for one type at a time"
    const val TYPE_MISMATCH = "category type does not match the selected entries"
    const val SCHEDULE_OTHER_OWNER = "a linked recurring schedule belongs to a different owner"
    const val SCHEDULE_TYPE_MISMATCH = "a linked recurring schedule has a different type than the new category"
    // Webapp mode only: the PATCH ops would fail on a server without the batch endpoints.
    const val SERVER_VERSION_UNKNOWN = "Sync once with the server to enable bulk edit"
    const val SERVER_TOO_OLD = "Bulk edit needs server 1.27 or newer. Update the server, then sync"
}

/**
 * Why bulk edit is off for this server, or null when it can run. Local mode
 * never talks to a server. In webapp mode the version comes from the last
 * sync's GET /api/health: null = never fetched, ServerVersion.OLD = a server
 * that doesn't report one (older than 1.27).
 */
fun bulkEditServerBlock(syncEnabled: Boolean, serverVersion: String?): String? = when {
    !syncEnabled -> null
    serverVersion == null -> BulkEditMessages.SERVER_VERSION_UNKNOWN
    !ServerVersion.atLeast(serverVersion, BULK_EDIT_MIN_SERVER_VERSION) -> BulkEditMessages.SERVER_TOO_OLD
    else -> null
}

data class BulkEditRequest(
    val ids: List<Long>,
    /** New name for every entry; null keeps each one's own. Trimmed before use. */
    val description: String? = null,
    /** New category for every entry; null keeps each one's own. */
    val categoryId: Long? = null,
    /** Also rename / recategorise the live schedules the entries came from. */
    val updateSchedules: Boolean = false
)

data class BulkEditCounts(
    val updated: Int,
    val unchanged: Int,
    val skipped: Int,
    val schedulesUpdated: Int = 0,
    val schedulesUnchanged: Int = 0,
    val schedulesSkipped: Int = 0
)

sealed class BulkEditResult {
    data class Applied(val counts: BulkEditCounts) : BulkEditResult()
    /** Nothing was written. */
    data class Rejected(val message: String) : BulkEditResult()
}

/** What the bulk-edit sheet needs to know about a selection. */
data class BulkEditSummary(
    val requested: Int,
    val liveCount: Int,
    val types: Set<String>,
    /** Account owners (0 normalised to the current user). */
    val owners: Set<Long>,
    val commonCategoryId: Long?,
    /** Live (not finished, not deleted) schedules the selected entries came from. */
    val linkedScheduleIds: List<Long>,
    val linkedScheduleTypes: Set<String>,
    val linkedScheduleOwners: Set<Long>,
    val currentUserId: Long?,
    /** Bulk edit can't run against this server (see [bulkEditServerBlock]); null when it can. */
    val serverBlockedReason: String? = null
) {
    val owner: Long? get() = owners.singleOrNull()
    val type: String? get() = types.singleOrNull()

    /** Why the category can't be changed for this selection, or null when it can. */
    val categoryBlockedReason: String?
        get() = when {
            liveCount == 0 -> "None of the selected entries can be edited."
            owners.size > 1 -> "The selected entries belong to different owners. Change the category for one owner at a time."
            types.size > 1 -> "The selected entries mix income and expense. Change the category for one type at a time."
            else -> null
        }
}

/** Accounts and categories created locally carry user id 0 until synced: they are the current user's. */
internal fun normalizeOwner(userId: Long, currentUserId: Long?): Long =
    if (userId == 0L) currentUserId ?: 0L else userId

/**
 * Categories a bulk edit may assign: the selection owner's, of the selection
 * type, and (in webapp mode) already synced. Empty when the category can't be
 * changed for this selection.
 */
fun bulkCategoryOptions(categories: List<Category>, summary: BulkEditSummary, syncEnabled: Boolean): List<Category> {
    if (summary.categoryBlockedReason != null) return emptyList()
    val owner = summary.owner ?: return emptyList()
    val type = summary.type ?: return emptyList()
    return categories.filter {
        it.type == type &&
            normalizeOwner(it.userId, summary.currentUserId) == owner &&
            (!syncEnabled || it.id > 0)
    }
}

/**
 * Renames and/or recategorises many entries (and optionally their live
 * schedules) at once. Separate from TransactionRepository so its constructor,
 * built by several test files, stays put.
 *
 * Validation and writes run in one Room transaction, validation first, so a
 * rejection leaves nothing behind. Only the description and category change:
 * each changed row is written as `row.copy(...)`, so every other column
 * (balance-relevant fields, the recurring-entry marker, a schedule's
 * next_occurrence and counters) is exactly what was read inside the same
 * transaction. In webapp mode each live row queues one partial "PATCH" op
 * carrying only the requested fields, never a full-row UPDATE built from a
 * possibly stale local copy, and only against a server that has the batch
 * endpoints (>= 1.27, learnt from the last sync).
 */
@Singleton
class BulkEditRepository @Inject constructor(
    private val database: AppDatabase,
    private val transactionDao: TransactionDao,
    private val scheduledDao: ScheduledTransactionDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val pendingOpDao: PendingOperationDao,
    private val gson: Gson,
    private val syncManager: SyncManager,
    private val prefs: UserPreferences
) {
    private fun isSyncEnabled() = prefs.getSyncModeImmediate() == "webapp"

    suspend fun summarize(ids: Collection<Long>): BulkEditSummary {
        val currentUserId = prefs.userIdFlow.first()
        val serverBlockedReason = bulkEditServerBlock(isSyncEnabled(), prefs.serverVersionFlow.first())
        val distinct = ids.distinct()
        return database.withTransaction {
            val owners = OwnerLookup(currentUserId)
            val live = loadLiveRows(distinct)
            val schedules = loadLiveSchedules(live)
            BulkEditSummary(
                requested = distinct.size,
                liveCount = live.size,
                types = live.mapTo(linkedSetOf()) { it.type },
                owners = live.mapTo(linkedSetOf()) { owners.of(it.accountId, it.userId) },
                commonCategoryId = live.map { it.categoryId }.distinct().singleOrNull(),
                linkedScheduleIds = schedules.map { it.id },
                linkedScheduleTypes = schedules.mapTo(linkedSetOf()) { it.type },
                linkedScheduleOwners = schedules.mapTo(linkedSetOf()) { owners.of(it.accountId, it.userId) },
                currentUserId = currentUserId,
                serverBlockedReason = serverBlockedReason
            )
        }
    }

    suspend fun apply(req: BulkEditRequest): BulkEditResult {
        // Input checks, in the server's order. No I/O yet.
        if (req.ids.isEmpty()) return BulkEditResult.Rejected(BulkEditMessages.IDS_REQUIRED)
        if (req.description == null && req.categoryId == null) {
            return BulkEditResult.Rejected(BulkEditMessages.NOTHING_TO_UPDATE)
        }
        val ids = req.ids.distinct()
        if (ids.size > BULK_EDIT_MAX_IDS) return BulkEditResult.Rejected(BulkEditMessages.TOO_MANY_IDS)
        val description = req.description?.trim()
        if (description != null) {
            if (description.isEmpty()) return BulkEditResult.Rejected(BulkEditMessages.DESCRIPTION_EMPTY)
            if (FieldLimits.utf8Length(description) > FieldLimits.MAX_DESCRIPTION_BYTES) {
                return BulkEditResult.Rejected(BulkEditMessages.DESCRIPTION_TOO_LONG)
            }
        }
        val syncEnabled = isSyncEnabled()
        bulkEditServerBlock(syncEnabled, prefs.serverVersionFlow.first())?.let {
            return BulkEditResult.Rejected(it)
        }
        val categoryId = req.categoryId
        if (categoryId != null) {
            if (categoryId == 0L) return BulkEditResult.Rejected(BulkEditMessages.INVALID_CATEGORY)
            // Negative = created offline and not yet pushed. A PATCH can't
            // carry it (nothing remaps PATCH payloads), so refuse up front.
            if (categoryId < 0 && syncEnabled) return BulkEditResult.Rejected(BulkEditMessages.CATEGORY_NOT_SYNCED)
        }
        // DataStore reads and payload serialisation stay outside the transaction.
        val currentUserId = prefs.userIdFlow.first()
        val patchJson = if (syncEnabled) gson.toJson(BulkPatchPayload(description, categoryId)) else null

        val result: BulkEditResult = database.withTransaction {
            // ---- Phase 1: reads and validation only. ----
            val owners = OwnerLookup(currentUserId)
            val live = loadLiveRows(ids)
            // Missing and soft-deleted rows are skipped, never resurrected.
            val skipped = ids.size - live.size
            if (live.isEmpty()) {
                return@withTransaction BulkEditResult.Applied(BulkEditCounts(updated = 0, unchanged = 0, skipped = skipped))
            }

            var categoryType: String? = null
            var owner: Long? = null
            if (categoryId != null) {
                val rowOwners = live.mapTo(linkedSetOf()) { owners.of(it.accountId, it.userId) }
                if (rowOwners.size > 1) return@withTransaction BulkEditResult.Rejected(BulkEditMessages.MULTIPLE_OWNERS)
                owner = rowOwners.single()
                val category = categoryDao.getById(categoryId)
                if (category == null || category.deletedAt != null ||
                    normalizeOwner(category.userId, currentUserId) != owner
                ) return@withTransaction BulkEditResult.Rejected(BulkEditMessages.CATEGORY_NOT_FOUND)
                val types = live.mapTo(linkedSetOf()) { it.type }
                if (types.size > 1) return@withTransaction BulkEditResult.Rejected(BulkEditMessages.MIXED_TYPES)
                if (category.type != types.single()) return@withTransaction BulkEditResult.Rejected(BulkEditMessages.TYPE_MISMATCH)
                categoryType = category.type
            }

            var liveSchedules = emptyList<ScheduledTransactionEntity>()
            var schedulesSkipped = 0
            if (req.updateSchedules) {
                val linkedIds = live.mapNotNull { it.scheduledId }.distinct()
                // Finished series are soft-deleted: skipped, like on the server.
                liveSchedules = loadLiveSchedules(live)
                schedulesSkipped = linkedIds.size - liveSchedules.size
                if (categoryType != null) {
                    for (s in liveSchedules) {
                        if (owners.of(s.accountId, s.userId) != owner) {
                            return@withTransaction BulkEditResult.Rejected(BulkEditMessages.SCHEDULE_OTHER_OWNER)
                        }
                        if (s.type != categoryType) {
                            return@withTransaction BulkEditResult.Rejected(BulkEditMessages.SCHEDULE_TYPE_MISMATCH)
                        }
                    }
                }
            }

            // ---- Phase 2: writes. ----
            // Room is written only for rows whose value actually changes. In
            // webapp mode every live row (and ticked live schedule) still gets
            // a PATCH: the local copy may be stale (an edit on the web not
            // pulled yet), the server treats an identical value as a no-op,
            // and identical PATCHes go out as one batch request anyway.
            var updated = 0
            for (row in live) {
                val newDescription = description ?: row.description
                val newCategoryId = categoryId ?: row.categoryId
                if (newDescription != row.description || newCategoryId != row.categoryId) {
                    // Amount, type and account are untouched, so balances are too.
                    transactionDao.upsert(row.copy(description = newDescription, categoryId = newCategoryId))
                    updated++
                }
                if (patchJson != null) {
                    pendingOpDao.insert(PendingOperationEntity(
                        entityType = "transaction",
                        operation = "PATCH",
                        entityId = row.id,
                        // Negative = its CREATE hasn't been pushed yet; the push
                        // loop defers this op until the CREATE remaps entity_id.
                        serverId = row.id.takeIf { it > 0 },
                        payloadJson = patchJson
                    ))
                }
            }

            var schedulesUpdated = 0
            for (s in liveSchedules) {
                val newDescription = description ?: s.description
                val newCategoryId = categoryId ?: s.categoryId
                if (newDescription != s.description || newCategoryId != s.categoryId) {
                    // next_occurrence, rrule, counters and active come from the row
                    // just read, so the materializer's view of the series is kept.
                    scheduledDao.upsert(s.copy(description = newDescription, categoryId = newCategoryId))
                    schedulesUpdated++
                }
                if (patchJson != null) {
                    pendingOpDao.insert(PendingOperationEntity(
                        entityType = "scheduled",
                        operation = "PATCH",
                        entityId = s.id,
                        serverId = s.id.takeIf { it > 0 },
                        payloadJson = patchJson
                    ))
                }
            }

            BulkEditResult.Applied(BulkEditCounts(
                updated = updated,
                unchanged = live.size - updated,
                skipped = skipped,
                schedulesUpdated = schedulesUpdated,
                schedulesUnchanged = liveSchedules.size - schedulesUpdated,
                schedulesSkipped = schedulesSkipped
            ))
        }

        // Webapp mode queued a PATCH per live row and schedule: push them.
        if (result is BulkEditResult.Applied && syncEnabled &&
            result.counts.updated + result.counts.unchanged + result.counts.schedulesUpdated + result.counts.schedulesUnchanged > 0
        ) syncManager.triggerImmediateSync()
        return result
    }

    /** Live rows for [ids], in [ids] order. Chunked to stay under SQLite's bind-variable limit. */
    private suspend fun loadLiveRows(ids: List<Long>): List<TransactionEntity> {
        val byId = HashMap<Long, TransactionEntity>(ids.size)
        for (chunk in ids.chunked(500)) {
            for (row in transactionDao.getByIds(chunk)) byId[row.id] = row
        }
        return ids.mapNotNull { byId[it] }.filter { it.deletedAt == null }
    }

    private suspend fun loadLiveSchedules(live: List<TransactionEntity>): List<ScheduledTransactionEntity> =
        live.mapNotNull { it.scheduledId }.distinct()
            .mapNotNull { scheduledDao.getById(it) }
            .filter { it.deletedAt == null }

    /**
     * Row owner = the account's owner (transactions.user_id mirrors it on the
     * server, but a row created offline still says 0 here). Falls back to the
     * row's own user_id if the account isn't in Room.
     */
    private inner class OwnerLookup(private val currentUserId: Long?) {
        private val cache = HashMap<Long, Long?>()
        suspend fun of(accountId: Long, rowUserId: Long): Long {
            val accountOwner = if (cache.containsKey(accountId)) cache[accountId]
            else accountDao.getById(accountId)?.userId.also { cache[accountId] = it }
            return normalizeOwner(accountOwner ?: rowUserId, currentUserId)
        }
    }
}
