package com.insituledger.app.data.remote.api

import com.insituledger.app.data.remote.dto.HealthResponse
import com.insituledger.app.data.remote.dto.SyncResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface SyncApi {
    @GET("sync")
    suspend fun sync(@Query("since") since: Long): Response<SyncResponse>

    // Unauthenticated. Read during sync for the server version (bulk edit
    // needs >= 1.27); any failure is ignored.
    @GET("health")
    suspend fun health(): Response<HealthResponse>
}
