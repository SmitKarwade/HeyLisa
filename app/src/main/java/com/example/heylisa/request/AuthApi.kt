package com.example.heylisa.request

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface AuthApi {
    @POST("auth/google/callback/")
    fun exchangeAuthCodeForToken(@Body request: AuthRequest): Call<AuthResponse>

    @GET("gmail/inbox/")
    fun getInbox(@Header("Authorization") authHeader: String): Call<InboxResponse>
}

data class AuthRequest(
    val server_auth_code: String
)

data class AuthResponse(
    val session_key: String?,
    val access_token: String?,
    val refresh_token: String?,
    val email: String?
)

data class InboxResponse(
    val messages: List<Message>?
)

data class Message(
    val id: String,
    val from: String,
    val subject: String,
    val body: String
)