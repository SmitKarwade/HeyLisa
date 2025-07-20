package com.example.heylisa.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heylisa.main.MainActivity
import com.example.heylisa.request.AuthClient
import com.example.heylisa.request.AuthRequest
import com.example.heylisa.request.AuthResponse
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Response

class AuthViewModel : ViewModel() {
    data class AuthUiState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val isSignedIn: Boolean = false,
        val email: String? = null
    )

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState

    fun handleSignInResult(context: Context, data: Intent?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)
                val serverAuthCode = account.serverAuthCode
                val email = account.email

                if (serverAuthCode != null) {
                    exchangeAuthCodeForToken(context, serverAuthCode, email)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Server auth code is null"
                    )
                }
            } catch (e: ApiException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Sign-in failed: ${e.statusCode}, ${e.message}"
                )
                Log.e("AuthViewModel", "Sign-in failed", e)
            }
        }
    }

    private fun exchangeAuthCodeForToken(context: Context, authCode: String, email: String?) {
        val request = AuthRequest(server_auth_code = authCode)
        val call = AuthClient.authApi.exchangeAuthCodeForToken(request)

        call.enqueue(object : retrofit2.Callback<AuthResponse> {
            override fun onResponse(call: Call<AuthResponse>, response: Response<AuthResponse>) {
                if (response.isSuccessful) {
                    val authResponse = response.body()
                    val accessToken = authResponse?.access_token
                    if (accessToken != null) {
                        Log.d("Access_Token", "Access Token: $accessToken")
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isSignedIn = true,
                            email = email
                        )
                        navigateToMainActivity(context, email)
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Access token not found in response"
                        )
                        Log.d("AuthViewModel", "Access Token not found")
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Token exchange failed: ${response.code()} - ${response.errorBody()?.string()}"
                    )
                    Log.e("AuthViewModel", "Token exchange failed: ${response.code()}")
                }
            }

            override fun onFailure(call: Call<AuthResponse>, t: Throwable) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Token exchange failed: ${t.message}"
                )
                Log.e("AuthViewModel", "Token exchange failed", t)
            }
        })
    }

    private fun navigateToMainActivity(context: Context, email: String?) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("email", email)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}