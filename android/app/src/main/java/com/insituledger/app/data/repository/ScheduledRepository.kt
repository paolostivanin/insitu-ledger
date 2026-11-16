package com.insituledger.app.data.repository

import com.insituledger.app.data.local.datastore.UserPreferences
import androidx.room.withTransaction
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.local.db.dao.ScheduledTransactionDao
import com.insituledger.app.data.local.db.entity.PendingOperationEntity
import com.insituledger.app.data.local.db.entity.ScheduledTransactionEntity
import com.insituledger.app.data.remote.api.ScheduledApi
import com.insituledger.app.data.remote.dto.ScheduledInput
import com.insituledger.app.domain.model.ScheduledTransaction
import com.insituledger.app.data.sync.SyncManager
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduledRepository @Inject constructor(
    private val scheduledDao: ScheduledTransactionDao,
    private val pendingOpDao: PendingOperationDao,
    private val scheduledApi: ScheduledApi,
    private val gson: Gson,
    private val syncManager: SyncManager,
    private val prefs: UserPreferences,
    private val database: AppDatabase
) {
    private fun isSyncEnabled() = prefs.getSyncModeImmediate() == "webapp"

    fun getAll(): Flow<List<ScheduledTransaction>> = scheduledDao.getAll().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun listFromServer(ownerId: Long? = null): List<ScheduledTransaction> {
        val response = scheduledApi.list(ownerId = ownerId)
        if (!response.isSuccessful) return emptyList()
        return response.body()?.filter { it.deletedAt == null }?.map { dto ->
            ScheduledTransaction(
                id = dto.id, accountId = dto.accountId, categoryId = dto.categoryId,
                userId = dto.userId, type = dto.type, amount = dto.amount,
                currency = dto.currency, description = dto.description, note = dto.note,
                rrule = dto.rrule, nextOccurrence = dto.nextOccurrence, active = dto.active,
                maxOccurrences = dto.maxOccurrences, occurrenceCount = dto.occurrenceCount,
                createdByUserId = dto.createdByUserId, createdByName = dto.createdByName
            )
        } ?: emptyList()
    }

    suspend fun getById(id: Long): ScheduledTransaction? = scheduledDao.getById(id)?.toDomain()

    suspend fun create(
        accountId: Long, categoryId: Long, type: String,
        amount: Double, currency: String, description: String?, note: String?,
        rrule: String, nextOccurrence: String, maxOccurrences: Int? = null
    ): Long {
        // Everything that can suspend on DataStore or allocate the payload
        // happens before the transaction; the row and its pending CREATE commit
        // together (a crash in between used to leave an unsynced schedule), and
        // sync is only triggered once they have.
        val syncEnabled = isSyncEnabled()
        val currentUserId = prefs.userIdFlow.first()
        val currentUserName = prefs.userNameFlow.first()
        val payloadJson = if (syncEnabled) {
            gson.toJson(ScheduledInput(accountId, categoryId, type, amount, currency, description, note, rrule, nextOccurrence, maxOccurrences))
        } else null

        val localId = database.withTransaction {
            val minId = scheduledDao.getMinId() ?: 0
            val id = if (minId >= 0) -1 else minId - 1
            scheduledDao.upsert(ScheduledTransactionEntity(
                id = id,
                accountId = accountId,
                categoryId = categoryId,
                userId = 0,
                type = type,
                amount = amount,
                currency = currency,
                description = description,
                note = note,
                rrule = rrule,
                nextOccurrence = nextOccurrence,
                maxOccurrences = maxOccurrences,
                isLocalOnly = true,
                createdByUserId = currentUserId,
                createdByName = currentUserName
            ))
            if (payloadJson != null) {
                pendingOpDao.insert(PendingOperationEntity(
                    entityType = "scheduled",
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
        amount: Double, currency: String, description: String?, note: String?,
        rrule: String, nextOccurrence: String, maxOccurrences: Int? = null,
        active: Boolean? = null
    ) {
        val syncEnabled = isSyncEnabled()
        val payloadJson = if (syncEnabled) {
            gson.toJson(ScheduledInput(accountId, categoryId, type, amount, currency, description, note, rrule, nextOccurrence, maxOccurrences, active))
        } else null

        val changed = database.withTransaction {
            val existing = scheduledDao.getById(id) ?: return@withTransaction false
            scheduledDao.upsert(existing.copy(
                accountId = accountId, categoryId = categoryId, type = type,
                amount = amount, currency = currency, description = description, note = note,
                rrule = rrule, nextOccurrence = nextOccurrence, maxOccurrences = maxOccurrences,
                active = active ?: existing.active
            ))
            if (payloadJson != null) {
                pendingOpDao.insert(PendingOperationEntity(
                    entityType = "scheduled",
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
            val existing = scheduledDao.getById(id) ?: return@withTransaction false
            if (existing.deletedAt != null) return@withTransaction false
            scheduledDao.upsert(existing.copy(deletedAt = "deleted"))
            if (syncEnabled) {
                pendingOpDao.insert(PendingOperationEntity(
                    entityType = "scheduled", operation = "DELETE", entityId = id,
                    serverId = if (id > 0) id else null
                ))
            }
            true
        }
        if (changed && syncEnabled) syncManager.triggerImmediateSync()
    }

    private fun ScheduledTransactionEntity.toDomain() = ScheduledTransaction(
        id = id, accountId = accountId, categoryId = categoryId,
        userId = userId, type = type, amount = amount,
        currency = currency, description = description, note = note,
        rrule = rrule, nextOccurrence = nextOccurrence,
        active = active, maxOccurrences = maxOccurrences,
        occurrenceCount = occurrenceCount, isLocalOnly = isLocalOnly,
        createdByUserId = createdByUserId, createdByName = createdByName
    )
}
