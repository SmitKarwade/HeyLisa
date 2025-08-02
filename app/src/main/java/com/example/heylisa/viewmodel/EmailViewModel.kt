package com.example.heylisa.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heylisa.model.IntentResponse
import com.example.heylisa.repository.EmailRepository
import com.example.heylisa.request.ConfirmSendResponse
import com.example.heylisa.request.DraftResponse
import com.example.heylisa.request.InboxResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class EmailViewModel(
    private val context: Context,
    private val emailRepository: EmailRepository = EmailRepository()
) : ViewModel() {

    private var isFirstDraftCreation = true

    // Helper functions to send broadcasts to the service
    private fun notifyProcessingStarted() {
        Log.d("EmailViewModel", "📤 Sending PROCESSING_STARTED broadcast")
        context.sendBroadcast(Intent("com.example.heylisa.PROCESSING_STARTED"))
    }

    private fun notifyProcessingComplete() {
        Log.d("EmailViewModel", "📤 Sending PROCESSING_COMPLETE broadcast")
        context.sendBroadcast(Intent("com.example.heylisa.PROCESSING_COMPLETE"))
    }


    data class EmailUiState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val currentScreen: String = "home",
        val lastIntent: IntentResponse? = null,
        val navigationEvent: NavigationEvent? = null,
        val currentDraft: DraftResponse? = null,
        val isDraftCreated: Boolean = false,
        val isSpeechActive: Boolean = false,
        val lastSpeechResult: String? = null,
        val isProcessingBackend: Boolean = false
    )

    sealed class IntentResult {
        data class Success(val intentResponse: IntentResponse) : IntentResult()
        data class Error(val message: String) : IntentResult()
    }

    sealed class DraftResult {
        data class Success(val draftResponse: DraftResponse) : DraftResult()
        data class Error(val message: String) : DraftResult()
    }

    sealed class SendResult {
        data class Success(val response: ConfirmSendResponse) : SendResult()
        data class Error(val message: String) : SendResult()
    }

    sealed class InboxResult {
        data class Success(val inboxResponse: InboxResponse) : InboxResult()
        data class Error(val message: String) : InboxResult()
    }

    sealed class NavigationEvent {
        object ToComposer : NavigationEvent()
        object ToInbox : NavigationEvent()
        object ToDrafts : NavigationEvent()
        object ToSent : NavigationEvent()
        object ToChat : NavigationEvent()
        object SendEmail : NavigationEvent()
        data class ShowError(val message: String) : NavigationEvent()
        data class ShowSuccess(val message: String) : NavigationEvent()
    }

    private val _uiState = MutableStateFlow(EmailUiState())
    val uiState: StateFlow<EmailUiState> = _uiState

    fun processUserInput(context: Context, input: String) {
        val cleanInput = input.trim()
        if (cleanInput.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                lastSpeechResult = cleanInput
            )

            Log.d("EmailViewModel", "Processing input: '$cleanInput'")

            emailRepository.getIntent(
                context = context,
                userInput = cleanInput,
                currentScreen = _uiState.value.currentScreen
            ) { result ->
                when (result) {
                    is IntentResult.Success -> {
                        handleIntentResponse(context, result.intentResponse, cleanInput)
                    }
                    is IntentResult.Error -> {
                        val errorMessage = "Failed to understand: ${result.message}"
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            navigationEvent = NavigationEvent.ShowError(errorMessage)
                        )
                        //speak(errorMessage)
                    }
                }
            }
        }
    }

    private fun handleIntentResponse(context: Context, intentResponse: IntentResponse, originalInput: String) {
        _uiState.value = _uiState.value.copy(
            lastIntent = intentResponse,
            isLoading = false
        )

        Log.d("EmailViewModel", """
            Intent Response:
            - Intent: ${intentResponse.intent}
            - Confidence: ${intentResponse.confidence}
            - Navigation: ${intentResponse.navigation_instruction}
            - Action: ${intentResponse.suggested_action}
            - Recipient Mentioned: ${intentResponse.recipient_mentioned}
        """.trimIndent())

        when (intentResponse.navigation_instruction) {
            "stay_and_edit" -> {
                handleEditIntent(context, originalInput)
            }
            "send_current_draft" -> {
                handleSendIntent(context)
            }
            "navigate_to_composer" -> {
                handleComposeIntent(context, originalInput)
            }
            "show_chat_interface" -> {
                handleOtherIntent(context, intentResponse, originalInput)
            }
            else -> {
                when (intentResponse.intent) {
                    "compose" -> handleComposeIntent(context, originalInput)
                    "edit" -> handleEditIntent(context, originalInput)
                    "send" -> handleSendIntent(context)
                    "other" -> handleOtherIntent(context, intentResponse, originalInput)
                    else -> {
                        val errorMessage = "Unknown intent: ${intentResponse.intent}"
                        _uiState.value = _uiState.value.copy(
                            navigationEvent = NavigationEvent.ShowError(errorMessage)
                        )
                        //speak(errorMessage)
                    }
                }
            }
        }
    }

    private fun handleComposeIntent(context: Context, input: String) {
        Log.d("EmailViewModel", "Handling compose intent")
        navigateToComposer()
        createDraft(context, input)
    }

    private fun handleEditIntent(context: Context, input: String) {
        Log.d("EmailViewModel", "Handling edit intent")
        val currentDraft = _uiState.value.currentDraft

        if (currentDraft?.draft_id != null) {
            editDraft(context, currentDraft.draft_id, input)
        } else {
            val errorMessage = "No draft available to edit. Create a draft first by saying 'write email to [person]'"
            _uiState.value = _uiState.value.copy(
                navigationEvent = NavigationEvent.ShowError(errorMessage)
            )
            //speak(errorMessage)
        }
    }

    private fun handleSendIntent(context: Context) {
        Log.d("EmailViewModel", "Handling send intent")
        val currentDraft = _uiState.value.currentDraft

        if (currentDraft?.draft_id != null) {
            if (currentDraft.to.isNullOrBlank() || currentDraft.body.isNullOrBlank()) {
                val errorMessage = "Draft is incomplete. Please add recipient and content before sending."
                _uiState.value = _uiState.value.copy(
                    navigationEvent = NavigationEvent.ShowError(errorMessage)
                )
                //speak(errorMessage)
                return
            }
            sendEmail(context, currentDraft.draft_id)
        } else {
            val errorMessage = "No draft available to send. Create a draft first."
            _uiState.value = _uiState.value.copy(
                navigationEvent = NavigationEvent.ShowError(errorMessage)
            )
            //speak(errorMessage)
        }
    }

    private fun handleOtherIntent(context: Context, intentResponse: IntentResponse, originalInput: String) {
        Log.d("EmailViewModel", "Handling other intent")

        when (intentResponse.navigation_instruction) {
            "navigate_to_composer" -> {
                navigateToComposer()
                createDraft(context, originalInput)
            }
            "navigate_to_draft_editor" -> {
                handleEditIntent(context, originalInput)
            }
            "send_current_email" -> {
                handleSendIntent(context)
            }
            "show_chat_interface" -> {
                val lowerInput = originalInput.lowercase()
                if ((lowerInput.contains("email") || lowerInput.contains("mail")) &&
                    (lowerInput.contains("send") || lowerInput.contains("write") || lowerInput.contains("draft"))) {
                    Log.d("EmailViewModel", "Chat interface but detected email intent - creating draft")
                    navigateToComposer()
                    createDraft(context, originalInput)
                } else {
                    _uiState.value = _uiState.value.copy(
                        navigationEvent = NavigationEvent.ToChat
                    )
                    notifyProcessingComplete()
                }
            }
            else -> {
                val errorMessage = intentResponse.suggested_action ?: "I didn't understand that command."
                _uiState.value = _uiState.value.copy(
                    navigationEvent = NavigationEvent.ShowError(errorMessage)
                )
                //speak(errorMessage)
                notifyProcessingComplete()
            }
        }
    }

    private fun navigateToComposer() {
        _uiState.value = _uiState.value.copy(
            currentScreen = "composer",
            navigationEvent = NavigationEvent.ToComposer
        )
    }

    fun createDraft(context: Context, prompt: String) {
        viewModelScope.launch {
            notifyProcessingStarted()

            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isProcessingBackend = true
            )

            Log.d("EmailViewModel", "🚀 Starting draft creation for prompt: '$prompt'")

            emailRepository.createDraft(context, prompt) { result ->
                when (result) {
                    is DraftResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isProcessingBackend = false,
                            currentDraft = result.draftResponse,
                            isDraftCreated = true,
                            navigationEvent = NavigationEvent.ShowSuccess("Email draft created successfully!")
                        )

                        Log.d("EmailViewModel", "✅ Draft created successfully: ${result.draftResponse.draft_id}")

                        // ✅ For new drafts, speak the full body
                        val textToSpeak = if (!result.draftResponse.body.isNullOrBlank()) {
                            "Here's your email: ${result.draftResponse.body}"
                        } else {
                            "Draft created successfully"
                        }

                        //speak(textToSpeak)
                        isFirstDraftCreation = false

                        // Processing complete will be sent after TTS finishes
                    }
                    is DraftResult.Error -> {
                        val errorMessage = "Failed to create draft"
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isProcessingBackend = false,
                            isDraftCreated = false,
                            currentDraft = null,
                            navigationEvent = NavigationEvent.ShowError(errorMessage)
                        )
                        //speak(errorMessage)
                        Log.e("EmailViewModel", "❌ Draft creation failed: ${result.message}")
                    }

                }
            }
        }
    }

    fun editDraft(context: Context, draftId: String, editPrompt: String) {
        viewModelScope.launch {
            notifyProcessingStarted()

            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isProcessingBackend = true
            )

            Log.d("EmailViewModel", "🚀 Starting draft edit for ID: '$draftId' with prompt: '$editPrompt'")

            emailRepository.editDraft(context, draftId, editPrompt) { result ->
                when (result) {
                    is DraftResult.Success -> {
                        Log.d("EmailViewModel", "📧 Received updated draft:")
                        Log.d("EmailViewModel", "   Subject: '${result.draftResponse.subject}'")
                        Log.d("EmailViewModel", "   To: '${result.draftResponse.to}'")
                        Log.d("EmailViewModel", "   Body preview: '${result.draftResponse.body?.take(50)}...'")

                        val updatedDraft = result.draftResponse.copy(
                            draft_id = result.draftResponse.draft_id,
                            to = result.draftResponse.to,
                            subject = result.draftResponse.subject,
                            body = result.draftResponse.body,
                            edit_summary = result.draftResponse.edit_summary,
                            raw_input = result.draftResponse.raw_input
                        )

                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isProcessingBackend = false,
                            currentDraft = updatedDraft, // Use the explicitly copied object
                            navigationEvent = NavigationEvent.ShowSuccess("Draft updated successfully!")
                        )

                        // ✅ Verify the state was updated
                        Log.d("EmailViewModel", "📧 UI State updated with subject: '${_uiState.value.currentDraft?.subject}'")

                        val textToSpeak = if (!result.draftResponse.edit_summary.isNullOrBlank()) {
                            result.draftResponse.edit_summary
                        } else {
                            "Draft updated successfully"
                        }

                        //speak(textToSpeak)
                        Log.d("EmailViewModel", "✅ Draft edited successfully")
                    }
                    is DraftResult.Error -> {
                        // ... existing error handling
                    }
                }
            }
        }
    }


    fun sendEmail(context: Context, draftId: String) {
        viewModelScope.launch {
            notifyProcessingStarted()

            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isProcessingBackend = true
            )

            Log.d("EmailViewModel", "🚀 Starting email send for draft ID: '$draftId'")

            emailRepository.confirmSend(context, draftId, "send") { result ->
                when (result) {
                    is SendResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isProcessingBackend = false,
                            navigationEvent = NavigationEvent.SendEmail,
                            currentDraft = null,
                            isDraftCreated = false
                        )

                        //speak("Email sent successfully")
                        Log.d("EmailViewModel", "✅ Email sent successfully")

                        // Processing complete will be sent after TTS finishes
                    }
                    is SendResult.Error -> {
                        val errorMessage = "Failed to send email: ${result.message}"
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isProcessingBackend = false,
                            navigationEvent = NavigationEvent.ShowError(errorMessage)
                        )

                        //speak(errorMessage)
                        Log.e("EmailViewModel", "❌ Email send failed: ${result.message}")
                    }
                }
            }
        }
    }

    // Utility Functions
    fun setSpeechActive(active: Boolean) {
        _uiState.value = _uiState.value.copy(isSpeechActive = active)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearDraft() {
        _uiState.value = _uiState.value.copy(
            currentDraft = null,
            isDraftCreated = false
        )
    }

    fun onNavigationHandled() {
        _uiState.value = _uiState.value.copy(navigationEvent = null)
    }




    // Getters
    fun getDraftTo(): String? = _uiState.value.currentDraft?.to
    fun getDraftSubject(): String? = _uiState.value.currentDraft?.subject
    fun getDraftBody(): String? = _uiState.value.currentDraft?.body
    fun getDraftId(): String? = _uiState.value.currentDraft?.draft_id
    fun isProcessingBackend(): Boolean = _uiState.value.isProcessingBackend
}