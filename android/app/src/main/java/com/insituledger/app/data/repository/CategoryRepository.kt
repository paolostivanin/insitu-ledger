package com.insituledger.app.data.repository

import com.insituledger.app.data.local.db.dao.CategoryDao
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.local.db.entity.CategoryEntity
import com.insituledger.app.data.local.db.entity.PendingOperationEntity
import com.insituledger.app.data.remote.api.CategoryApi
import com.insituledger.app.data.remote.dto.CategoryInput
import com.insituledger.app.domain.model.Category
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    private val pendingOpDao: PendingOperationDao,
    private val categoryApi: CategoryApi,
    private val gson: Gson
) {
    fun getAll(): Flow<List<Category>> = categoryDao.getAll().map { list ->
        list.map { it.toDomain() }
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

        val input = CategoryInput(parentId, name, type, icon, color)
        pendingOpDao.insert(PendingOperationEntity(
            entityType = "category",
            operation = "CREATE",
            entityId = localId,
            payloadJson = gson.toJson(input)
        ))
        return localId
    }

    suspend fun update(id: Long, name: String, type: String, parentId: Long?, icon: String?, color: String?) {
        val existing = categoryDao.getById(id) ?: return
        categoryDao.upsert(existing.copy(name = name, type = type, parentId = parentId, icon = icon, color = color))

        val input = CategoryInput(parentId, name, type, icon, color)
        pendingOpDao.insert(PendingOperationEntity(
            entityType = "category",
            operation = "UPDATE",
            entityId = id,
            serverId = if (id > 0) id else null,
            payloadJson = gson.toJson(input)
        ))
    }

    suspend fun delete(id: Long) {
        val existing = categoryDao.getById(id) ?: return
        categoryDao.upsert(existing.copy(deletedAt = "deleted"))

        pendingOpDao.insert(PendingOperationEntity(
            entityType = "category",
            operation = "DELETE",
            entityId = id,
            serverId = if (id > 0) id else null
        ))
    }

    private fun CategoryEntity.toDomain() = Category(
        id = id, userId = userId, parentId = parentId,
        name = name, type = type, icon = icon, color = color,
        isLocalOnly = isLocalOnly
    )
}
