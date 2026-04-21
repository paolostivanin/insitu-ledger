package com.insituledger.app.data.repository

import com.insituledger.app.data.local.datastore.UserPreferences
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduledRepository @Inject constructor(
    private val scheduledDao: ScheduledTransactionDao,
    private val pendingOpDao: PendingOperationDao,
    private val scheduledApi: ScheduledApi,
    private val gson: Gson,
    private val syncManager: SyncManager,
    private val prefs: UserPreferences
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
        val currentUserId = prefs.userIdFlow.first()
        val minId = scheduledDao.getMinId() ?: 0
        val localId = if (minId >= 0) -1 else minId - 1
        val entity = ScheduledTransactionEntity(
            id = localId,
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
            createdByUserId = currentUserId
        )
        scheduledDao.upsert(entity)

        if (isSyncEnabled()) {
            val input = ScheduledInput(accountId, categoryId, type, amount, currency, description, note, rrule, nextOccurrence, maxOccurrences)
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "scheduled",
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
        amount: Double, currency: String, description: String?, note: String?,
        rrule: String, nextOccurrence: String, maxOccurrences: Int? = null
    ) {
        val existing = scheduledDao.getById(id) ?: return
        scheduledDao.upsert(existing.copy(
            accountId = accountId, categoryId = categoryId, type = type,
            amount = amount, currency = currency, description = description, note = note,
            rrule = rrule, nextOccurrence = nextOccurrence, maxOccurrences = maxOccurrences
        ))

        if (isSyncEnabled()) {
            val input = ScheduledInput(accountId, categoryId, type, amount, currency, description, note, rrule, nextOccurrence, maxOccurrences)
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "scheduled",
                operation = "UPDATE",
                entityId = id,
                serverId = if (id > 0) id else null,
                payloadJson = gson.toJson(input)
            ))
            syncManager.triggerImmediateSync()
        }
    }

    suspend fun delete(id: Long) {
        val existing = scheduledDao.getById(id) ?: return
        scheduledDao.upsert(existing.copy(deletedAt = "deleted"))

        if (isSyncEnabled()) {
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "scheduled",
                operation = "DELETE",
                entityId = id,
                serverId = if (id > 0) id else null
            ))
            syncManager.triggerImmediateSync()
        }
    }

    private fun ScheduledTransactionEntity.toDomain() = ScheduledTransaction(
        id = id, accountId = accountId, categoryId = categoryId,
        userId = userId, type = type, amount = amount,
        currency = currency, description = description, note = note,
        rrule = rrule, nextOccurrence = nextOccurrence,
        active = active, maxOccurrences = maxOccurrences,
        occurrenceCount = occurrenceCount, isLocalOnly = isLocalOnly,
        createdByUserId = createdByUserId
    )
}
