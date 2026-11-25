package com.insituledger.app.data.remote.api

import com.insituledger.app.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

interface ScheduledApi {
    @GET("scheduled")
    suspend fun list(@Query("owner_id") ownerId: Long? = null): Response<List<ScheduledTransactionDto>>

    @POST("scheduled")
    suspend fun create(@Body input: ScheduledInput, @Query("owner_id") ownerId: Long? = null): Response<IdResponse>

    @PUT("scheduled/{id}")
    suspend fun update(@Path("id") id: Long, @Body input: ScheduledInput, @Query("owner_id") ownerId: Long? = null): Response<Unit>

    // Partial update (description and/or category); never touches
    // next_occurrence, rrule, amount or the counters. Server >= 1.27.
    @POST("scheduled/batch-update")
    suspend fun batchUpdate(@Body input: ScheduledBatchUpdateRequest): Response<BatchUpdateResponse>

    @DELETE("scheduled/{id}")
    suspend fun delete(@Path("id") id: Long, @Query("owner_id") ownerId: Long? = null): Response<Unit>
}
