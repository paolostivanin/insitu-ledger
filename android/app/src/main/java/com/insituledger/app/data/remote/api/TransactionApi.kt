package com.insituledger.app.data.remote.api

import com.insituledger.app.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

interface TransactionApi {
    @GET("transactions")
    suspend fun list(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("category_id") categoryId: Long? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("sort_by") sortBy: String? = null,
        @Query("sort_dir") sortDir: String? = null,
        @Query("owner_id") ownerId: Long? = null
    ): Response<List<TransactionDto>>

    @POST("transactions")
    suspend fun create(@Body input: TransactionInput, @Query("owner_id") ownerId: Long? = null): Response<CreateTransactionResponse>

    @PUT("transactions/{id}")
    suspend fun update(@Path("id") id: Long, @Body input: TransactionInput, @Query("owner_id") ownerId: Long? = null): Response<Unit>

    @DELETE("transactions/{id}")
    suspend fun delete(@Path("id") id: Long, @Query("owner_id") ownerId: Long? = null): Response<Unit>

    // Partial update (description and/or category). Server >= 1.27; an older
    // server answers 405, which the push loop treats as a permanent failure.
    @POST("transactions/batch-update")
    suspend fun batchUpdate(@Body input: BatchUpdateRequest): Response<BatchUpdateResponse>

    // Soft-deletes many rows at once; 204 even when some ids are already gone.
    // Exists on every server version.
    @POST("transactions/batch-delete")
    suspend fun batchDelete(@Body input: BatchDeleteRequest): Response<Unit>

    @GET("transactions/autocomplete")
    suspend fun autocomplete(@Query("q") query: String, @Query("owner_id") ownerId: Long? = null): Response<List<AutocompleteSuggestion>>
}
