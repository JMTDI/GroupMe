package com.jmtditech.groupme.data

import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface GroupMeApi {
    @GET("users/me")
    suspend fun me(): Envelope<UserDto>

    @GET("groups")
    suspend fun groups(@Query("page") page: Int, @Query("per_page") perPage: Int): Envelope<List<GroupDto>>

    @GET("chats")
    suspend fun chats(@Query("page") page: Int, @Query("per_page") perPage: Int): Envelope<List<ChatDto>>

    /** 304 (no body) means "nothing new" when after_id is used. */
    @GET("groups/{id}/messages")
    suspend fun groupMessages(
        @Path("id") id: String,
        @Query("limit") limit: Int,
        @Query("before_id") beforeId: String?,
        @Query("after_id") afterId: String?,
    ): Response<Envelope<GroupMessagesResp>>

    @POST("groups/{id}/messages")
    suspend fun sendGroup(@Path("id") id: String, @Body body: SendGroupBody): Envelope<SendGroupResp>

    @GET("direct_messages")
    suspend fun directMessages(
        @Query("other_user_id") otherUserId: String,
        @Query("before_id") beforeId: String?,
        @Query("since_id") sinceId: String?,
    ): Response<Envelope<DmResp>>

    @POST("direct_messages")
    suspend fun sendDm(@Body body: SendDmBody): Envelope<SendDmResp>

    @POST("messages/{conv}/{msg}/like")
    suspend fun like(@Path("conv", encoded = true) conv: String, @Path("msg") msg: String): Response<Unit>

    @POST("messages/{conv}/{msg}/unlike")
    suspend fun unlike(@Path("conv", encoded = true) conv: String, @Path("msg") msg: String): Response<Unit>

    @POST("https://image.groupme.com/pictures")
    suspend fun upload(@Body body: RequestBody): UploadResp
}
