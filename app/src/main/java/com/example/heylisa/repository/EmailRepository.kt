package com.example.heylisa.repository

import android.content.Context
import android.util.Log
import com.example.heylisa.auth.TokenManager
import com.example.heylisa.model.IntentRequest
import com.example.heylisa.model.IntentResponse
import com.example.heylisa.request.AuthClient
import com.example.heylisa.request.ConfirmSendRequest
import com.example.heylisa.request.ConfirmSendResponse
import com.example.heylisa.request.DraftRequest
import com.example.heylisa.request.DraftResponse
import com.example.heylisa.request.EditDraftRequest
import com.example.heylisa.request.InboxResponse
import com.example.heylisa.viewmodel.EmailViewModel
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class EmailRepository {

    // Get Intent from AI
    fun getIntent(
        context: Context,
        userInput: String,
        currentScreen: String,
        callback: (EmailViewModel.IntentResult) -> Unit
    ) {
        val token = TokenManager.getToken(context)
        if (token == null) {
            callback(EmailViewModel.IntentResult.Error("No access token available"))
            return
        }

        val request = IntentRequest(
            user_input = userInput,
            current_screen = currentScreen
        )

        val call = AuthClient.authApi.getIntent(request)

        call.enqueue(object : Callback<IntentResponse> {
            override fun onResponse(call: Call<IntentResponse>, response: Response<IntentResponse>) {
                if (response.isSuccessful) {
                    val intentResponse = response.body()
                    if (intentResponse != null) {
                        Log.d("EmailRepository", "Intent received: ${intentResponse.intent}")
                        callback(EmailViewModel.IntentResult.Success(intentResponse))
                    } else {
                        callback(EmailViewModel.IntentResult.Error("Empty intent response"))
                    }
                } else {
                    val errorMessage = "Intent recognition failed: ${response.code()}"
                    Log.e("EmailRepository", errorMessage)
                    callback(EmailViewModel.IntentResult.Error(errorMessage))
                }
            }

            override fun onFailure(call: Call<IntentResponse>, t: Throwable) {
                val errorMessage = "Network error: ${t.message}"
                Log.e("EmailRepository", errorMessage, t)
                callback(EmailViewModel.IntentResult.Error(errorMessage))
            }
        })
    }

    // Create Draft
    fun createDraft(
        context: Context,
        prompt: String,
        callback: (EmailViewModel.DraftResult) -> Unit
    ) {
        val token = TokenManager.getToken(context)
        if (token == null) {
            callback(EmailViewModel.DraftResult.Error("No access token available"))
            return
        }

        val request = DraftRequest(prompt = prompt)
        val call = AuthClient.authApi.createDraft("Bearer $token", request)

        call.enqueue(object : Callback<DraftResponse> {
            override fun onResponse(call: Call<DraftResponse>, response: Response<DraftResponse>) {
                if (response.isSuccessful) {
                    val draftResponse = response.body()
                    if (draftResponse != null) {
                        Log.d("EmailRepository", "Draft created: ${draftResponse.draft_id}")
                        callback(EmailViewModel.DraftResult.Success(draftResponse))
                    } else {
                        callback(EmailViewModel.DraftResult.Error("Empty draft response"))
                    }
                } else {
                    val errorMessage = "Draft creation failed: ${response.code()}"
                    Log.e("EmailRepository", errorMessage)
                    callback(EmailViewModel.DraftResult.Error(errorMessage))
                }
            }

            override fun onFailure(call: Call<DraftResponse>, t: Throwable) {
                val errorMessage = "Network error: ${t.message}"
                Log.e("EmailRepository", errorMessage, t)
                callback(EmailViewModel.DraftResult.Error(errorMessage))
            }
        })
    }

    // Edit Draft
    fun editDraft(
        context: Context,
        draftId: String,
        editPrompt: String,
        callback: (EmailViewModel.DraftResult) -> Unit
    ) {
        val request = EditDraftRequest(
            draft_id = draftId,
            edit_prompt = editPrompt
        )

        val call = AuthClient.authApi.editDraft(request)

        call.enqueue(object : Callback<DraftResponse> {
            override fun onResponse(call: Call<DraftResponse>, response: Response<DraftResponse>) {
                if (response.isSuccessful) {
                    val draftResponse = response.body()
                    if (draftResponse != null) {
                        Log.d("EmailRepository", "Draft edited: ${draftResponse.draft_id}")
                        callback(EmailViewModel.DraftResult.Success(draftResponse))
                    } else {
                        callback(EmailViewModel.DraftResult.Error("Empty edit response"))
                    }
                } else {
                    val errorMessage = "Draft edit failed: ${response.code()}"
                    Log.e("EmailRepository", errorMessage)
                    callback(EmailViewModel.DraftResult.Error(errorMessage))
                }
            }

            override fun onFailure(call: Call<DraftResponse>, t: Throwable) {
                val errorMessage = "Network error: ${t.message}"
                Log.e("EmailRepository", errorMessage, t)
                callback(EmailViewModel.DraftResult.Error(errorMessage))
            }
        })
    }

    // Send Email
    fun confirmSend(
        context: Context,
        draftId: String,
        action: String,
        callback: (EmailViewModel.SendResult) -> Unit
    ) {
        val token = TokenManager.getToken(context)
        if (token == null) {
            callback(EmailViewModel.SendResult.Error("No access token available"))
            return
        }

        val request = ConfirmSendRequest(draft_id = draftId, action = action)
        val call = AuthClient.authApi.confirmSend("Bearer $token", request)

        call.enqueue(object : Callback<ConfirmSendResponse> {
            override fun onResponse(call: Call<ConfirmSendResponse>, response: Response<ConfirmSendResponse>) {
                if (response.isSuccessful) {
                    val confirmResponse = response.body()
                    if (confirmResponse != null) {
                        callback(EmailViewModel.SendResult.Success(confirmResponse))
                    } else {
                        callback(EmailViewModel.SendResult.Error("Empty send response"))
                    }
                } else {
                    val errorMessage = "Send failed: ${response.code()}"
                    callback(EmailViewModel.SendResult.Error(errorMessage))
                }
            }

            override fun onFailure(call: Call<ConfirmSendResponse>, t: Throwable) {
                val errorMessage = "Network error: ${t.message}"
                callback(EmailViewModel.SendResult.Error(errorMessage))
            }
        })
    }

    // Get Inbox
    fun fetchInboxEmails(
        context: Context,
        callback: (EmailViewModel.InboxResult) -> Unit
    ) {
        val token = TokenManager.getToken(context)
        if (token == null) {
            callback(EmailViewModel.InboxResult.Error("No access token available"))
            return
        }

        val call = AuthClient.authApi.getInbox("Bearer $token")

        call.enqueue(object : Callback<InboxResponse> {
            override fun onResponse(call: Call<InboxResponse>, response: Response<InboxResponse>) {
                if (response.isSuccessful) {
                    val inboxResponse = response.body()
                    if (inboxResponse != null) {
                        callback(EmailViewModel.InboxResult.Success(inboxResponse))
                    } else {
                        callback(EmailViewModel.InboxResult.Error("Empty inbox response"))
                    }
                } else {
                    val errorMessage = "Inbox fetch failed: ${response.code()}"
                    callback(EmailViewModel.InboxResult.Error(errorMessage))
                }
            }

            override fun onFailure(call: Call<InboxResponse>, t: Throwable) {
                val errorMessage = "Network error: ${t.message}"
                callback(EmailViewModel.InboxResult.Error(errorMessage))
            }
        })
    }
}