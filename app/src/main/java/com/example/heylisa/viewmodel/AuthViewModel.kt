package com.example.heylisa.viewmodel

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heylisa.auth.TokenManager
import com.example.heylisa.main.MainActivity
import com.example.heylisa.repository.AuthRepository
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AuthViewModel(
    private val authRepository: AuthRepository = AuthRepository()
) : ViewModel() {

    // Only Auth-related state
    data class AuthUiState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val isSignedIn: Boolean = false,
        val email: String? = null
    )

    sealed class AuthResult {
        data class Success(val accessToken: String) : AuthResult()
        data class Error(val message: String) : AuthResult()
    }

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
                    authRepository.exchangeAuthCodeForToken(context, serverAuthCode, email) { result ->
                        when (result) {
                            is AuthResult.Success -> {
                                val token = result.accessToken
                                TokenManager.saveToken(context, token)
                                _uiState.value = _uiState.value.copy(
                                    isLoading = false,
                                    isSignedIn = true,
                                    email = email
                                )
                                navigateToMainActivity(context, email)
                            }
                            is AuthResult.Error -> {
                                _uiState.value = _uiState.value.copy(
                                    isLoading = false,
                                    error = result.message
                                )
                                Log.e("AuthViewModel", "Token exchange failed: ${result.message}")
                            }
                        }
                    }
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
