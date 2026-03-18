package com.insituledger.app.data.remote.api

import com.insituledger.app.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

interface CategoryApi {
    @GET("categories")
    suspend fun list(@Query("owner_id") ownerId: Long? = null): Response<List<CategoryDto>>

    @POST("categories")
    suspend fun create(@Body input: CategoryInput, @Query("owner_id") ownerId: Long? = null): Response<IdResponse>

    @PUT("categories/{id}")
    suspend fun update(@Path("id") id: Long, @Body input: CategoryInput, @Query("owner_id") ownerId: Long? = null): Response<Unit>

    @DELETE("categories/{id}")
    suspend fun delete(@Path("id") id: Long, @Query("owner_id") ownerId: Long? = null): Response<Unit>
}
