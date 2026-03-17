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
        @Query("offset") offset: Int? = null
    ): Response<List<TransactionDto>>

    @POST("transactions")
    suspend fun create(@Body input: TransactionInput): Response<IdResponse>

    @PUT("transactions/{id}")
    suspend fun update(@Path("id") id: Long, @Body input: TransactionInput): Response<Unit>

    @DELETE("transactions/{id}")
    suspend fun delete(@Path("id") id: Long): Response<Unit>

    @GET("transactions/autocomplete")
    suspend fun autocomplete(@Query("q") query: String): Response<List<AutocompleteSuggestion>>
}
