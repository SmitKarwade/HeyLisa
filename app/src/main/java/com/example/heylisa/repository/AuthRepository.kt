package com.example.heylisa.repository

import android.content.Context
import android.util.Log
import com.example.heylisa.request.AuthClient
import com.example.heylisa.request.AuthRequest
import com.example.heylisa.request.AuthResponse
import com.example.heylisa.viewmodel.AuthViewModel
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class AuthRepository {

    fun exchangeAuthCodeForToken(
        context: Context,
        authCode: String,
        email: String?,
        callback: (AuthViewModel.AuthResult) -> Unit
    ) {
        val request = AuthRequest(server_auth_code = authCode)
        val call = AuthClient.authApi.exchangeAuthCodeForToken(request)

        call.enqueue(object : Callback<AuthResponse> {
            override fun onResponse(call: Call<AuthResponse>, response: Response<AuthResponse>) {
                if (response.isSuccessful) {
                    val authResponse = response.body()
                    val accessToken = authResponse?.access_token
                    if (accessToken != null) {
                        Log.d("Access_Token", "Access Token: $accessToken")
                        callback(AuthViewModel.AuthResult.Success(accessToken))
                    } else {
                        Log.d("AuthRepository", "Access Token not found")
                        callback(AuthViewModel.AuthResult.Error("Access token not found in response"))
                    }
                } else {
                    val errorMessage = "Token exchange failed: ${response.code()} - ${response.errorBody()?.string()}"
                    Log.e("AuthRepository", errorMessage)
                    callback(AuthViewModel.AuthResult.Error(errorMessage))
                }
            }

            override fun onFailure(call: Call<AuthResponse>, t: Throwable) {
                Log.e("AuthRepository", "Token exchange failed: ${t.message}")
                callback(AuthViewModel.AuthResult.Error("Token exchange failed: ${t.message}"))
            }
        })
    }
}