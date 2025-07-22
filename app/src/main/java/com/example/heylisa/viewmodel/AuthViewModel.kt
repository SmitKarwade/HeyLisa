package com.example.heylisa.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heylisa.main.MainActivity
import com.example.heylisa.repository.AuthRepository
import com.example.heylisa.request.ConfirmSendResponse
import com.example.heylisa.request.DraftResponse
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AuthViewModel(private val authRepository: AuthRepository = AuthRepository()) : ViewModel() {
    data class AuthUiState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val isSignedIn: Boolean = false,
        val email: String? = null,
        val isSpeechActive: Boolean = false,
        val speechResult: String? = null,
        val draftResponse: DraftResponse? = null,
        val isDraftCreated: Boolean = false
    )

    sealed class AuthResult {
        data class Success(val accessToken: String) : AuthResult()
        data class Error(val message: String) : AuthResult()
    }

    sealed class DraftResult {
        data class Success(val draftResponse: DraftResponse) : DraftResult()
        data class Error(val message: String) : DraftResult()
    }

    sealed class SendResult {
        data class Success(val data: ConfirmSendResponse) : SendResult()
        data class Error(val msg: String)                 : SendResult()
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

    fun handleSpeechResult(result: String?) {
        if (result != null && result.contains("send an email to|draft an email to".toRegex())) {
            _uiState.value = _uiState.value.copy(speechResult = result, isSpeechActive = false)
        } else {
            _uiState.value = _uiState.value.copy(
                isSpeechActive = false,
                error = "Normal Question"
            )
        }
    }

    fun createDraft(context: Context, prompt: String) {
        val token = TokenManager.getToken(context)
        token?.let { token ->
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                authRepository.createDraft(context, prompt, token) { result ->
                    Toast.makeText(context, "Draft created $result" , Toast.LENGTH_SHORT).show()
                    when (result) {
                        is DraftResult.Success -> {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                draftResponse = result.draftResponse
                            )
                        }
                        is DraftResult.Error -> {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                error = result.message
                            )
                            Log.e("AuthViewModel", "Draft creation failed: ${result.message}")
                        }
                    }
                }
            }
        } ?: run {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "No access token available. Please sign in again."
            )
        }
    }

    fun editDraft(context: Context, draftId: String, editPrompt: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            authRepository.editDraft(
                context = context,
                draftId = draftId,
                editPrompt = editPrompt
            ) { result ->
                when (result) {
                    is DraftResult.Success -> {
                        Log.d("AuthViewModel", "Draft edited successfully")
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            draftResponse = result.draftResponse,
                            error = null
                        )
                    }
                    is DraftResult.Error -> {
                        Log.e("AuthViewModel", "Draft edit failed: ${result.message}")
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = result.message
                        )
                    }
                }
            }
        }
    }

    fun confirmSendEmail(context: Context, draftId: String, action: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            authRepository.confirmSend(context, draftId, action) { result ->
                when (result) {
                    is SendResult.Success -> {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                        Toast.makeText(
                            context,
                            result.data.message,
                            Toast.LENGTH_LONG
                        ).show()
                        if (action == "send") clearDraft()
                    }
                    is SendResult.Error -> {
                        _uiState.value =
                            _uiState.value.copy(isLoading = false, error = result.msg)
                    }
                }
            }
        }
    }

    fun getDraftTo(): String? = _uiState.value.draftResponse?.to
    fun getDraftSubject(): String? = _uiState.value.draftResponse?.subject
    fun getDraftBody(): String? = _uiState.value.draftResponse?.body
    fun getDraftId(): String? = _uiState.value.draftResponse?.draft_id

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

    fun clearDraft() {
        _uiState.value = _uiState.value.copy(draftResponse = null, speechResult = null)
    }

    fun resetDraftState() {
        _uiState.value = _uiState.value.copy(
            draftResponse = null,
            isDraftCreated = false,
            speechResult = null
        )
    }
}