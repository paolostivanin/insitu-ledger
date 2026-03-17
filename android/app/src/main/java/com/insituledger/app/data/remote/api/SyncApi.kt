package com.insituledger.app.data.remote.api

import com.insituledger.app.data.remote.dto.SyncResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface SyncApi {
    @GET("sync")
    suspend fun sync(@Query("since") since: Long): Response<SyncResponse>
}
