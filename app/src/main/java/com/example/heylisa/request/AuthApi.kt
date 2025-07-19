package com.example.heylisa.request

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthApi {
    @POST("auth/google/callback/")
    fun exchangeAuthCodeForToken(@Body request: AuthRequest): Call<AuthResponse>
}

data class AuthRequest(
    val server_auth_code: String
)

data class AuthResponse(
    val access_token: String?,
    val refresh_token: String?,
    val email: String?
)