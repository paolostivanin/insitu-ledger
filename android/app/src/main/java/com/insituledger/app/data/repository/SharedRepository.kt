package com.insituledger.app.data.repository

import com.insituledger.app.data.remote.api.SharedApi
import com.insituledger.app.data.remote.dto.AccessibleOwnerDto
import com.insituledger.app.data.remote.dto.SharedAccessDto
import com.insituledger.app.data.remote.dto.SharedAccessInput
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SharedRepository @Inject constructor(
    private val sharedApi: SharedApi,
    private val sharedAccessState: SharedAccessState
) {
    suspend fun loadAccessibleOwners(): List<AccessibleOwnerDto> {
        val response = sharedApi.accessible()
        val owners = if (response.isSuccessful) response.body() ?: emptyList() else emptyList()
        sharedAccessState.updateAccessibleOwners(owners)
        return owners
    }

    suspend fun listSharedAccess(): List<SharedAccessDto> {
        val response = sharedApi.list()
        return if (response.isSuccessful) response.body() ?: emptyList() else emptyList()
    }

    suspend fun createSharedAccess(guestEmail: String, accountId: Long, permission: String): Result<Long> {
        val response = sharedApi.create(SharedAccessInput(guestEmail, accountId, permission))
        return if (response.isSuccessful) {
            Result.success(response.body()?.id ?: 0)
        } else {
            val errorBody = response.errorBody()?.string() ?: "Failed to share"
            Result.failure(Exception(errorBody))
        }
    }

    suspend fun deleteSharedAccess(id: Long): Boolean {
        return sharedApi.delete(id).isSuccessful
    }
}
