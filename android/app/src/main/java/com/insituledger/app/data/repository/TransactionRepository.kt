package com.insituledger.app.data.repository

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.nextLocalId
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.CategoryBreakdownRow
import com.insituledger.app.data.local.db.dao.CurrencySummaryRow
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// Must match the backend's autocompleteAmountSampleSize.
const val AUTOCOMPLETE_AMOUNT_SAMPLE_SIZE = 3

// Ids per query when an id list is bound into IN (...). Older Android SQLite
// allows at most 999 bound variables per statement.
private const val FILTER_IDS_CHUNK = 500

/**
 * What the transactions list is narrowed to. The list (getSorted), "select all
 * matching" (getMatchingIds) and the banner count (countMatching) all build
 * their WHERE clause from this through [buildTransactionWhere], so the ids a
 * bulk edit touches are exactly the rows the list shows.
 */
data class TransactionFilter(
    val from: String? = null,
    val to: String? = null,
    val categoryId: Long? = null,
    val search: String? = null,
    /** Whole-description match (trimmed, ASCII case-insensitive) instead of substring. No effect without [search]. */
    val exactMatch: Boolean = false,
    val accountIds: Set<Long>? = null,
    /** Only entries [currentUserId] added. Ignored when [currentUserId] is null (local mode). */
    val onlyMine: Boolean = false,
    val currentUserId: Long? = null
)

/** A WHERE clause ("WHERE deleted_at IS NULL ...") and its bind args, in order. */
data class TransactionWhere(val sql: String, val args: List<Any>)

/**
 * Pure SQL builder shared by every query over the filtered list. The text and
 * argument order for the pre-existing filters are unchanged from when this
 * lived inline in getSorted.
 */
fun buildTransactionWhere(filter: TransactionFilter): TransactionWhere {
    val sb = StringBuilder("WHERE deleted_at IS NULL")
    val args = mutableListOf<Any>()

    val accountIds = filter.accountIds
    if (accountIds != null) {
        if (accountIds.isEmpty()) sb.append(" AND 0")
        else {
            sb.append(" AND account_id IN (${accountIds.joinToString { "?" }})")
            args.addAll(accountIds.sorted())
        }
    }
    if (filter.from != null) {
        sb.append(" AND date >= ?")
        args.add(filter.from)
    }
    if (filter.to != null) {
        sb.append(" AND SUBSTR(date, 1, 10) <= ?")
        args.add(filter.to)
    }
    if (filter.categoryId != null) {
        // Expand parent → direct children so filtering by a parent category
        // includes its sub-categories' transactions. Mirrors the backend.
        sb.append(" AND category_id IN (SELECT id FROM categories WHERE id = ? OR parent_id = ?)")
        args.add(filter.categoryId)
        args.add(filter.categoryId)
    }
    val search = filter.search
    if (!search.isNullOrBlank()) {
        // Search is a predicate like any other, so it composes with the date
        // and category filters, the chosen sort and the LIMIT above.
        // Description only, matching GET /api/transactions and the reports
        // search summary.
        if (filter.exactMatch) {
            // Mirrors the backend's match=exact: surrounding spaces ignored,
            // ASCII case-insensitive, %/_/\ literal (plain =, not LIKE), and a
            // NULL description never matches.
            sb.append(" AND TRIM(description) = ? COLLATE NOCASE")
            args.add(search.trim())
        } else {
            sb.append(" AND description LIKE ? ESCAPE '\\'")
            args.add("%" + escapeLike(search.trim()) + "%")
        }
    }
    val userId = filter.currentUserId
    if (filter.onlyMine && userId != null) {
        // "Added by me". An unsynced local row has no server-side creator yet
        // but was necessarily entered on this device, so it counts as mine.
        sb.append(" AND (created_by_user_id = ? OR (created_by_user_id IS NULL AND is_local_only = 1))")
        args.add(userId)
    }
    return TransactionWhere(sb.toString(), args)
}

// % and _ are LIKE wildcards; a user typing one means the character, not
// "match anything". Paired with ESCAPE '\' in the queries.
internal fun escapeLike(s: String): String =
    s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

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

    fun getRecent(limit: Int = 10, accountIds: Set<Long>? = null): Flow<List<Transaction>> =
        getSorted(limit = limit, accountIds = accountIds)

    fun getSorted(
        from: String? = null,
        to: String? = null,
        categoryId: Long? = null,
        search: String? = null,
        sortBy: String = "date",
        sortDir: String = "desc",
        limit: Int = 100,
        offset: Int = 0,
        accountIds: Set<Long>? = null,
        exactMatch: Boolean = false,
        // Non-null = "Added by me" for this user id.
        onlyMineUserId: Long? = null
    ): Flow<List<Transaction>> = getSorted(
        TransactionFilter(
            from = from, to = to, categoryId = categoryId, search = search,
            exactMatch = exactMatch, accountIds = accountIds,
            onlyMine = onlyMineUserId != null, currentUserId = onlyMineUserId
        ),
        sortBy = sortBy, sortDir = sortDir, limit = limit, offset = offset
    )

    fun getSorted(
        filter: TransactionFilter,
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

        val where = buildTransactionWhere(filter)
        val sql = "SELECT * FROM transactions ${where.sql} ORDER BY $column $dir, id DESC LIMIT ? OFFSET ?"
        val args = where.args + listOf<Any>(limit, offset)

        return transactionDao.getSorted(SimpleSQLiteQuery(sql, args.toTypedArray())).map { list ->
            list.map { it.toDomain() }
        }
    }

    /**
     * Ids of every row [filter] matches (up to [limit]), for "select all
     * matching". Same WHERE clause as [getSorted], so it selects exactly what
     * the list would show if scrolled to the end.
     */
    suspend fun getMatchingIds(filter: TransactionFilter, limit: Int): List<Long> {
        val where = buildTransactionWhere(filter)
        val sql = "SELECT id FROM transactions ${where.sql} ORDER BY id LIMIT ?"
        return transactionDao.getIdsRaw(SimpleSQLiteQuery(sql, (where.args + limit).toTypedArray()))
    }

    /**
     * The ids among [ids] that [filter] still matches (live rows only), in
     * [ids] order. A selection outlives syncs, which can rename, move,
     * delete or remap a selected row out of the list; bulk actions run on
     * this instead of the raw selection. Same WHERE clause as [getSorted],
     * chunked so the IN list plus the filter's own arguments stay under the
     * 999 bind-variable limit of older SQLite builds.
     */
    suspend fun filterIds(filter: TransactionFilter, ids: Collection<Long>): List<Long> {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return emptyList()
        val where = buildTransactionWhere(filter)
        val matched = HashSet<Long>(distinct.size)
        for (chunk in distinct.chunked(FILTER_IDS_CHUNK)) {
            val sql = "SELECT id FROM transactions ${where.sql} AND id IN (${chunk.joinToString { "?" }})"
            matched += transactionDao.getIdsRaw(SimpleSQLiteQuery(sql, (where.args + chunk).toTypedArray()))
        }
        return distinct.filter { it in matched }
    }

    /** How many rows [filter] matches; same WHERE clause as [getSorted]. */
    suspend fun countMatching(filter: TransactionFilter): Int {
        val where = buildTransactionWhere(filter)
        val sql = "SELECT COUNT(*) FROM transactions ${where.sql}"
        return transactionDao.countRaw(SimpleSQLiteQuery(sql, where.args.toTypedArray()))
    }

    suspend fun listFromServer(
        ownerId: Long? = null,
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
                currency = dto.currency, description = dto.description, note = dto.note, date = dto.date,
                createdByUserId = dto.createdByUserId, createdByName = dto.createdByName,
                scheduledId = dto.scheduledId, occurrence = dto.occurrence, occurrenceTotal = dto.occurrenceTotal
            )
        } ?: emptyList()
    }

    suspend fun getMonthlySummary(from: String, to: String) = transactionDao.getMonthlySummary(from, to)

    suspend fun getById(id: Long): Transaction? = transactionDao.getById(id)?.toDomain()

    suspend fun autocomplete(query: String): List<Pair<String, Long>> {
        return transactionDao.autocomplete(query).map { it.description to it.categoryId }
    }

    // Suggest an amount to pre-fill when the most recent [sampleSize] transactions
    // for this exact description share one identical (amount, currency). Mirrors
    // the backend's autocomplete amount logic for the local-first path.
    suspend fun suggestedAmount(
        description: String,
        sampleSize: Int = AUTOCOMPLETE_AMOUNT_SAMPLE_SIZE
    ): Pair<Double, String>? {
        val rows = transactionDao.recentAmountsForDescription(description, sampleSize)
        if (rows.size < sampleSize) return null
        val first = rows.first()
        val allSame = rows.all {
            kotlin.math.abs(it.amount - first.amount) < 0.005 && it.currency == first.currency
        }
        return if (allSame) first.amount to first.currency else null
    }

    suspend fun create(
        accountId: Long, categoryId: Long, type: String,
        amount: Double, currency: String, description: String?, note: String?, date: String
    ): Long {
        require(amount > 0) { "Amount must be positive" }
        require(type == "income" || type == "expense") { "Type must be 'income' or 'expense'" }

        // Stamp authenticated creator so the "Added by [me]" badge shows
        // immediately for offline-created entries on shared accounts. The next
        // sync round-trip refreshes from the server-side creator value anyway.
        val syncEnabled = isSyncEnabled()
        val currentUserId = prefs.userIdFlow.first()
        val currentUserName = prefs.userNameFlow.first()
        val payloadJson = if (syncEnabled) {
            gson.toJson(TransactionInput(accountId, categoryId, type, amount, currency, description, note, date))
        } else null

        // Row, balance change and pending CREATE commit together; sync is
        // triggered only after the commit.
        val localId = database.withTransaction {
            val id = nextLocalId(transactionDao.getMinId(), pendingOpDao.getMinEntityId("transaction"))
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
                isLocalOnly = true,
                createdByUserId = currentUserId,
                createdByName = currentUserName
            )
            transactionDao.upsert(entity)
            val delta = if (type == "income") amount else -amount
            accountDao.adjustBalance(accountId, delta)
            if (payloadJson != null) {
                pendingOpDao.insert(PendingOperationEntity(
                    entityType = "transaction",
                    operation = "CREATE",
                    entityId = id,
                    payloadJson = payloadJson,
                    clientId = UUID.randomUUID().toString()
                ))
            }
            id
        }

        if (syncEnabled) syncManager.triggerImmediateSync()
        return localId
    }

    suspend fun update(
        id: Long, accountId: Long, categoryId: Long, type: String,
        amount: Double, currency: String, description: String?, note: String?, date: String
    ) {
        require(amount > 0) { "Amount must be positive" }
        require(type == "income" || type == "expense") { "Type must be 'income' or 'expense'" }

        val syncEnabled = isSyncEnabled()
        val payloadJson = if (syncEnabled) {
            gson.toJson(TransactionInput(accountId, categoryId, type, amount, currency, description, note, date))
        } else null

        // Guard, balance move, row and pending UPDATE commit together; sync is
        // triggered only after the commit. A missing or soft-deleted row is a
        // no-op: its balance effect was already reversed when it was deleted,
        // and there is nothing left to push an UPDATE for.
        val changed = database.withTransaction {
            val existing = transactionDao.getById(id) ?: return@withTransaction false
            if (existing.deletedAt != null) return@withTransaction false
            // Reverse old transaction's effect on old account
            val oldDelta = if (existing.type == "income") existing.amount else -existing.amount
            accountDao.adjustBalance(existing.accountId, -oldDelta)
            // Apply new transaction's effect on new account
            val newDelta = if (type == "income") amount else -amount
            accountDao.adjustBalance(accountId, newDelta)

            // copy() keeps the recurring-entry marker (scheduled_id,
            // occurrence, occurrence_total): an edited auto-added entry still
            // shows where it came from. The UPDATE payload never carries it.
            transactionDao.upsert(existing.copy(
                accountId = accountId, categoryId = categoryId, type = type,
                amount = amount, currency = currency, description = description, note = note, date = date
            ))
            if (payloadJson != null) {
                pendingOpDao.insert(PendingOperationEntity(
                    entityType = "transaction",
                    operation = "UPDATE",
                    entityId = id,
                    serverId = if (id > 0) id else null,
                    payloadJson = payloadJson
                ))
            }
            true
        }

        if (changed && syncEnabled) syncManager.triggerImmediateSync()
    }

    suspend fun delete(id: Long) {
        val syncEnabled = isSyncEnabled()
        val changed = database.withTransaction {
            val existing = transactionDao.getById(id) ?: return@withTransaction false
            if (existing.deletedAt != null) return@withTransaction false
            // Reverse the transaction's effect on account balance
            val delta = if (existing.type == "income") existing.amount else -existing.amount
            accountDao.adjustBalance(existing.accountId, -delta)
            transactionDao.upsert(existing.copy(deletedAt = "deleted"))
            if (syncEnabled) {
                pendingOpDao.insert(PendingOperationEntity(
                    entityType = "transaction", operation = "DELETE", entityId = id,
                    serverId = if (id > 0) id else null
                ))
            }
            true
        }
        if (changed && syncEnabled) syncManager.triggerImmediateSync()
    }

    /**
     * Deletes many entries in one Room transaction: each live row is
     * soft-deleted, its balance effect reversed and (webapp mode) one DELETE
     * op queued; missing or already-deleted rows are skipped, as in [delete].
     * All or nothing, so leaving the screen mid-way can't half-delete a
     * selection. Sync is triggered once, after the commit, and only if
     * something was deleted. Returns how many rows were deleted.
     */
    suspend fun deleteMany(ids: Collection<Long>): Int {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return 0
        val syncEnabled = isSyncEnabled()
        val deleted = database.withTransaction {
            var count = 0
            // Summed per account so a large selection is one balance write per
            // account (the server's batch delete aggregates the same way).
            val balanceDelta = LinkedHashMap<Long, Double>()
            for (chunk in distinct.chunked(FILTER_IDS_CHUNK)) {
                val byId = transactionDao.getByIds(chunk).associateBy { it.id }
                for (id in chunk) {
                    val existing = byId[id] ?: continue
                    if (existing.deletedAt != null) continue
                    val delta = if (existing.type == "income") existing.amount else -existing.amount
                    balanceDelta[existing.accountId] = (balanceDelta[existing.accountId] ?: 0.0) - delta
                    transactionDao.upsert(existing.copy(deletedAt = "deleted"))
                    if (syncEnabled) {
                        pendingOpDao.insert(PendingOperationEntity(
                            entityType = "transaction", operation = "DELETE", entityId = id,
                            serverId = if (id > 0) id else null
                        ))
                    }
                    count++
                }
            }
            for ((accountId, delta) in balanceDelta) accountDao.adjustBalance(accountId, delta)
            count
        }
        if (deleted > 0 && syncEnabled) syncManager.triggerImmediateSync()
        return deleted
    }

    suspend fun getFilteredSync(from: String?, to: String?, categoryId: Long?): List<Transaction> =
        transactionDao.getFilteredSync(from, to, categoryId).map { it.toDomain() }

    suspend fun getCategoryBreakdown(from: String?, to: String?): List<CategoryBreakdownRow> =
        transactionDao.getCategoryBreakdown(from, to)

    suspend fun searchSummary(query: String, from: String? = null, to: String? = null): List<CurrencySummaryRow> =
        transactionDao.searchSummary(escapeLike(query.trim()), from, to)

    private fun TransactionEntity.toDomain() = Transaction(
        id = id, accountId = accountId, categoryId = categoryId,
        userId = userId, type = type, amount = amount,
        currency = currency, description = description, note = note, date = date,
        isLocalOnly = isLocalOnly,
        createdByUserId = createdByUserId, createdByName = createdByName,
        scheduledId = scheduledId, occurrence = occurrence, occurrenceTotal = occurrenceTotal
    )
}
