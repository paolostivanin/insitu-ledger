package com.insituledger.app.data.repository

import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.dao.CategoryDao
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.local.db.dao.ScheduledTransactionDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import com.insituledger.app.data.local.db.entity.CategoryEntity
import com.insituledger.app.data.local.db.entity.PendingOperationEntity
import com.insituledger.app.data.remote.api.CategoryApi
import com.insituledger.app.data.remote.dto.CategoryInput
import com.insituledger.app.domain.model.Category
import com.insituledger.app.data.sync.SyncManager
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// v1.19.0 (Tier B B4): mirror the server-side delete check on the client so
// the failure is visible at the moment the user taps delete — the optimistic
// offline-first flow would otherwise show "Category deleted" and then have
// the row resurrected by the next pull.
sealed class CategoryDeleteResult {
    object Success : CategoryDeleteResult()
    object NotFound : CategoryDeleteResult()
    object HasTransactions : CategoryDeleteResult()
    object HasScheduled : CategoryDeleteResult()
}

@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    private val transactionDao: TransactionDao,
    private val scheduledDao: ScheduledTransactionDao,
    private val pendingOpDao: PendingOperationDao,
    private val categoryApi: CategoryApi,
    private val gson: Gson,
    private val syncManager: SyncManager,
    private val prefs: UserPreferences
) {
    private fun isSyncEnabled() = prefs.getSyncModeImmediate() == "webapp"

    private val _cached = MutableStateFlow<List<Category>?>(null)

    fun getAll(): Flow<List<Category>> = categoryDao.getAll().map { list ->
        list.map { it.toDomain() }
    }.onEach { _cached.value = it }

    suspend fun getCached(): List<Category> = _cached.value ?: getAll().first()

    suspend fun listFromServer(ownerId: Long): List<Category> {
        val response = categoryApi.list(ownerId = ownerId)
        if (!response.isSuccessful) return emptyList()
        return response.body()?.filter { it.deletedAt == null }?.map { dto ->
            Category(id = dto.id, userId = dto.userId, parentId = dto.parentId,
                name = dto.name, type = dto.type, icon = dto.icon, color = dto.color)
        } ?: emptyList()
    }

    fun getByType(type: String): Flow<List<Category>> = categoryDao.getByType(type).map { list ->
        list.map { it.toDomain() }
    }

    suspend fun getById(id: Long): Category? = categoryDao.getById(id)?.toDomain()

    suspend fun create(name: String, type: String, parentId: Long?, icon: String?, color: String?): Long {
        val minId = categoryDao.getMinId() ?: 0
        val localId = if (minId >= 0) -1 else minId - 1
        val entity = CategoryEntity(
            id = localId,
            userId = 0,
            parentId = parentId,
            name = name,
            type = type,
            icon = icon,
            color = color,
            isLocalOnly = true
        )
        categoryDao.upsert(entity)

        if (isSyncEnabled()) {
            val input = CategoryInput(parentId, name, type, icon, color)
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "category",
                operation = "CREATE",
                entityId = localId,
                payloadJson = gson.toJson(input),
                clientId = UUID.randomUUID().toString()
            ))
            syncManager.triggerImmediateSync()
        }
        return localId
    }

    suspend fun update(id: Long, name: String, type: String, parentId: Long?, icon: String?, color: String?) {
        val existing = categoryDao.getById(id) ?: return
        categoryDao.upsert(existing.copy(name = name, type = type, parentId = parentId, icon = icon, color = color))

        if (isSyncEnabled()) {
            val input = CategoryInput(parentId, name, type, icon, color)
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "category",
                operation = "UPDATE",
                entityId = id,
                serverId = if (id > 0) id else null,
                payloadJson = gson.toJson(input)
            ))
            syncManager.triggerImmediateSync()
        }
    }

    suspend fun delete(id: Long): CategoryDeleteResult {
        val existing = categoryDao.getById(id) ?: return CategoryDeleteResult.NotFound
        // Pre-check the dependents the backend will reject on (Tier B B4).
        // Optimistic local soft-delete would otherwise be reversed by the
        // next pull when the server returns 409, plus the queued DELETE op
        // would grind in the queue with no surfaced error.
        if (transactionDao.countByCategoryId(id) > 0) return CategoryDeleteResult.HasTransactions
        if (scheduledDao.countByCategoryId(id) > 0) return CategoryDeleteResult.HasScheduled

        categoryDao.upsert(existing.copy(deletedAt = "deleted"))

        if (isSyncEnabled()) {
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "category",
                operation = "DELETE",
                entityId = id,
                serverId = if (id > 0) id else null
            ))
            syncManager.triggerImmediateSync()
        }
        return CategoryDeleteResult.Success
    }

    private fun CategoryEntity.toDomain() = Category(
        id = id, userId = userId, parentId = parentId,
        name = name, type = type, icon = icon, color = color,
        isLocalOnly = isLocalOnly
    )
}
