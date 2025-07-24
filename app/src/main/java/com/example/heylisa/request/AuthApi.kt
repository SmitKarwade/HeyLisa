package com.example.heylisa.request

import com.example.heylisa.model.IntentRequest
import com.example.heylisa.model.IntentResponse
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

    @POST("gmail/draft/")
    fun createDraft(
        @Header("Authorization") authHeader: String,
        @Body request: DraftRequest
    ): Call<DraftResponse>

    @POST("gmail/edit/")
    fun editDraft(@Body request: EditDraftRequest): Call<DraftResponse>

    @POST("gmail/confirm/")
    fun confirmSend(
        @Header("Authorization") auth: String,
        @Body request: ConfirmSendRequest
    ): Call<ConfirmSendResponse>

    @POST("gmail/get-intent/")
    fun getIntent(
        @Body request: IntentRequest
    ): Call<IntentResponse>

}

data class AuthRequest(
    val server_auth_code: String
)

data class AuthResponse(
    val access_token: String?,
    val refresh_token: String?,
    val email: String?
)

data class InboxResponse(
    val messages: List<EmailMessage>
) {
    data class EmailMessage(
        val id: String,
        val from: String,
        val subject: String,
        val body: String
    )
}


data class Message(
    val id: String,
    val from: String,
    val subject: String,
    val body: String
)

data class DraftRequest(
    val prompt: String
)

data class DraftResponse(
    val draft_id: String?,
    val to: String?,
    val subject: String?,
    val body: String?,
    val raw_input: String?,
    val edit_summary: String? = null
)


data class EditDraftRequest(
    val draft_id: String,
    val edit_prompt: String
)

data class ConfirmSendRequest(
    val draft_id: String,
    val action: String
)

data class ConfirmSendResponse(
    val message: String,
    val to: String
)
