package com.insituledger.app.data.remote.api

import com.insituledger.app.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

interface AccountApi {
    @GET("accounts")
    suspend fun list(@Query("owner_id") ownerId: Long? = null): Response<List<AccountDto>>

    @POST("accounts")
    suspend fun create(@Body input: AccountInput, @Query("owner_id") ownerId: Long? = null): Response<IdResponse>

    @PUT("accounts/{id}")
    suspend fun update(@Path("id") id: Long, @Body input: AccountInput, @Query("owner_id") ownerId: Long? = null): Response<Unit>

    @DELETE("accounts/{id}")
    suspend fun delete(@Path("id") id: Long, @Query("owner_id") ownerId: Long? = null): Response<Unit>
}
