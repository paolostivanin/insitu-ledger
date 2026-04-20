package com.insituledger.app.data.repository

import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.local.db.entity.AccountEntity
import com.insituledger.app.data.local.db.entity.PendingOperationEntity
import com.insituledger.app.data.remote.api.AccountApi
import com.insituledger.app.data.remote.dto.AccountInput
import com.insituledger.app.domain.model.Account
import com.insituledger.app.data.sync.SyncManager
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao,
    private val pendingOpDao: PendingOperationDao,
    private val accountApi: AccountApi,
    private val gson: Gson,
    private val syncManager: SyncManager,
    private val prefs: UserPreferences
) {
    private fun isSyncEnabled() = prefs.getSyncModeImmediate() == "webapp"

    private val _cached = MutableStateFlow<List<Account>?>(null)

    fun getAll(): Flow<List<Account>> = accountDao.getAll().map { list ->
        list.map { it.toDomain() }
    }.onEach { _cached.value = it }

    suspend fun getCached(): List<Account> = _cached.value ?: getAll().first()

    suspend fun listFromServer(ownerId: Long): List<Account> {
        val response = accountApi.list(ownerId = ownerId)
        if (!response.isSuccessful) return emptyList()
        return response.body()?.filter { it.deletedAt == null }?.map { dto ->
            Account(id = dto.id, userId = dto.userId, name = dto.name, currency = dto.currency, balance = dto.balance)
        } ?: emptyList()
    }

    suspend fun getById(id: Long): Account? = accountDao.getById(id)?.toDomain()

    suspend fun create(name: String, currency: String, balance: Double): Long {
        val minId = accountDao.getMinId() ?: 0
        val localId = if (minId >= 0) -1 else minId - 1
        val entity = AccountEntity(
            id = localId,
            userId = 0,
            name = name,
            currency = currency,
            balance = balance,
            isLocalOnly = true
        )
        accountDao.upsert(entity)

        if (isSyncEnabled()) {
            val input = AccountInput(name, currency, balance)
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "account",
                operation = "CREATE",
                entityId = localId,
                payloadJson = gson.toJson(input)
            ))
            syncManager.triggerImmediateSync()
        }
        return localId
    }

    suspend fun update(id: Long, name: String, currency: String) {
        val existing = accountDao.getById(id) ?: return
        // Balance is intentionally not user-editable after creation: it is
        // derived from transaction deltas (adjustBalance) and any direct
        // overwrite would break that invariant. The backend likewise ignores
        // `balance` on PUT.
        accountDao.upsert(existing.copy(name = name, currency = currency))

        if (isSyncEnabled()) {
            val input = AccountInput(name, currency, balance = null)
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "account",
                operation = "UPDATE",
                entityId = id,
                serverId = if (id > 0) id else null,
                payloadJson = gson.toJson(input)
            ))
            syncManager.triggerImmediateSync()
        }
    }

    suspend fun delete(id: Long) {
        val existing = accountDao.getById(id) ?: return
        accountDao.upsert(existing.copy(deletedAt = "deleted"))

        if (isSyncEnabled()) {
            pendingOpDao.insert(PendingOperationEntity(
                entityType = "account",
                operation = "DELETE",
                entityId = id,
                serverId = if (id > 0) id else null
            ))
            syncManager.triggerImmediateSync()
        }
    }

    private fun AccountEntity.toDomain() = Account(
        id = id, userId = userId, name = name,
        currency = currency, balance = balance,
        isLocalOnly = isLocalOnly
    )
}
