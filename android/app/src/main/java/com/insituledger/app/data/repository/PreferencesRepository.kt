package com.insituledger.app.data.repository

import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.remote.api.ProfileApi
import com.insituledger.app.data.remote.dto.UserPreferencesDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesRepository @Inject constructor(
    private val profileApi: ProfileApi,
    private val prefs: UserPreferences
) {
    /** Pull preferences from the server and mirror them into DataStore. */
    suspend fun loadFromServer(): Result<Long?> = try {
        val response = profileApi.getPreferences()
        if (response.isSuccessful) {
            val defaultId = response.body()?.defaultAccountId
            prefs.saveDefaultAccountId(defaultId)
            Result.success(defaultId)
        } else {
            Result.failure(Exception("HTTP ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Push the preference to the server, mirroring locally on success. */
    suspend fun setDefaultAccount(accountId: Long?): Result<Unit> = try {
        val response = profileApi.setPreferences(UserPreferencesDto(defaultAccountId = accountId))
        if (response.isSuccessful) {
            prefs.saveDefaultAccountId(accountId)
            Result.success(Unit)
        } else {
            Result.failure(Exception("HTTP ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
