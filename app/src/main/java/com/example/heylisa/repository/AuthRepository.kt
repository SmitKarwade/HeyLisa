package com.example.heylisa.repository

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.example.heylisa.auth.AuthViewModel
import com.example.heylisa.auth.TokenManager
import com.example.heylisa.request.AuthClient
import com.example.heylisa.request.AuthRequest
import com.example.heylisa.request.AuthResponse
import com.example.heylisa.request.ConfirmSendRequest
import com.example.heylisa.request.ConfirmSendResponse
import com.example.heylisa.request.DraftRequest
import com.example.heylisa.request.DraftResponse
import com.example.heylisa.request.EditDraftRequest
import com.example.heylisa.request.InboxResponse
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class AuthRepository {
    fun exchangeAuthCodeForToken(context: Context, authCode: String, email: String?, callback: (AuthViewModel.AuthResult) -> Unit) {
        val request = AuthRequest(server_auth_code = authCode)
        val call = AuthClient.authApi.exchangeAuthCodeForToken(request)

        call.enqueue(object : retrofit2.Callback<AuthResponse> {
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

    fun createDraft(context: Context, prompt: String, accessToken: String, callback: (AuthViewModel.DraftResult) -> Unit) {
        val request = DraftRequest(prompt = prompt)
        val call = AuthClient.authApi.createDraft("Bearer $accessToken", request)

        call.enqueue(object : Callback<DraftResponse> {
            override fun onResponse(call: Call<DraftResponse>, response: Response<DraftResponse>) {
                Toast.makeText(context, "onRespone...", Toast.LENGTH_SHORT).show()
                Log.d("AuthRepository", "onResponse: ${response.isSuccessful}")
                if (response.isSuccessful) {
                    val draftResponse = response.body()
                    if (draftResponse != null) {
                        Log.d("AuthRepository", "Draft created: ${draftResponse.draft_id}")
                        callback(AuthViewModel.DraftResult.Success(draftResponse))
                    } else {
                        val errorMessage = "Draft creation failed: Empty response body"
                        Log.e("AuthRepository", errorMessage)
                        callback(AuthViewModel.DraftResult.Error(errorMessage))
                    }
                } else {
                    val errorMessage = try {
                        "Draft creation failed: ${response.code()} - ${response.errorBody()?.string()}"
                    } catch (e: Exception) {
                        "Draft creation failed: ${response.code()} - Unable to read error body"
                    }
                    Log.e("AuthRepository", errorMessage)
                    callback(AuthViewModel.DraftResult.Error(errorMessage))
                }
            }

            override fun onFailure(call: Call<DraftResponse>, t: Throwable) {
                val errorMessage = "Network error: ${t.message}"
                Log.e("AuthRepository", errorMessage, t)
                callback(AuthViewModel.DraftResult.Error(errorMessage))
            }
        })
    }

    fun editDraft(
        context: Context,
        draftId: String,
        editPrompt: String,
        callback: (AuthViewModel.DraftResult) -> Unit
    ) {

        val request = EditDraftRequest(
            draft_id = draftId,
            edit_prompt = editPrompt
        )

        val call = AuthClient.authApi.editDraft(request)

        Log.d("AuthRepository", "Editing draft with ID: $draftId, command: $editPrompt")

        call.enqueue(object : Callback<DraftResponse> {
            override fun onResponse(call: Call<DraftResponse>, response: Response<DraftResponse>) {
                Log.d("AuthRepository", "Edit draft response code: ${response.code()}")
                if (response.isSuccessful) {
                    val draftResponse = response.body()
                    if (draftResponse != null) {
                        Log.d("AuthRepository", "Draft edited successfully: ${draftResponse.draft_id}")
                        callback(AuthViewModel.DraftResult.Success(draftResponse))
                    } else {
                        val errorMessage = "Draft edit failed: Empty response body"
                        Log.e("AuthRepository", errorMessage)
                        callback(AuthViewModel.DraftResult.Error(errorMessage))
                    }
                } else {
                    val errorMessage = try {
                        val errorBody = response.errorBody()?.string()
                        Log.e("AuthRepository", "Error body: $errorBody")
                        "Draft edit failed: ${response.code()} - $errorBody"
                    } catch (e: Exception) {
                        "Draft edit failed: ${response.code()} - Unable to read error body"
                    }
                    Log.e("AuthRepository", errorMessage)
                    callback(AuthViewModel.DraftResult.Error(errorMessage))
                }
            }

            override fun onFailure(call: Call<DraftResponse>, t: Throwable) {
                val errorMessage = "Network error while editing draft: ${t.message}"
                Log.e("AuthRepository", errorMessage, t)
                callback(AuthViewModel.DraftResult.Error(errorMessage))
            }
        })
    }

    fun confirmSend(
        context: Context,
        draftId: String,
        action: String,
        callback: (AuthViewModel.SendResult) -> Unit
    ) {
        val token = TokenManager.getToken(context)
        if (token == null) {
            callback(AuthViewModel.SendResult.Error("Missing access-token, please sign-in again."))
            return
        }

        val request = ConfirmSendRequest(draft_id = draftId, action = action)
        Log.d("AuthRepository", "▶️ confirmSend payload → $request")

        AuthClient.authApi
            .confirmSend("Bearer $token", request)
            .enqueue(object : Callback<ConfirmSendResponse> {

                override fun onResponse(
                    call: Call<ConfirmSendResponse>,
                    resp: Response<ConfirmSendResponse>
                ) {
                    if (resp.isSuccessful && resp.body() != null) {
                        callback(AuthViewModel.SendResult.Success(resp.body()!!))
                    } else {
                        val err = resp.errorBody()?.string()
                        callback(
                            AuthViewModel.SendResult.Error(
                                "Confirm-send failed: ${resp.code()} – $err"
                            )
                        )
                    }
                }

                override fun onFailure(call: Call<ConfirmSendResponse>, t: Throwable) {
                    callback(AuthViewModel.SendResult.Error("Network error: ${t.message}"))
                }
            })
    }


    fun fetchInboxEmails(context: Context, accessToken: String) {
        val authHeader = "Bearer $accessToken"
        val call = AuthClient.authApi.getInbox(authHeader)

        call.enqueue(object : Callback<InboxResponse> {
            override fun onResponse(call: Call<InboxResponse>, response: Response<InboxResponse>) {
                if (response.isSuccessful) {
                    val inboxResponse = response.body()
                    inboxResponse?.messages?.let { messages ->
                        messages.forEach { message ->
                            Log.d("Inbox", "Email - ID: ${message.id}, From: ${message.from}, Subject: ${message.subject}, Body: ${message.body}")
                        }
                    } ?: run {
                        Log.d("Inbox", "No messages found")
                    }
                } else {
                    Log.e("Inbox", "Failed to fetch inbox: ${response.code()} - ${response.errorBody()?.string()}")
                }
            }

            override fun onFailure(call: Call<InboxResponse>, t: Throwable) {
                Log.e("Inbox", "Failed to fetch inbox: ${t.message}")
            }
        })
    }
}