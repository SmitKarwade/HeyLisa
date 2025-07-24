package com.example.heylisa.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heylisa.model.IntentResponse
import com.example.heylisa.repository.EmailRepository
import com.example.heylisa.request.ConfirmSendResponse
import com.example.heylisa.request.DraftResponse
import com.example.heylisa.request.InboxResponse
import com.example.heylisa.service.TtsService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class EmailViewModel(
    private val context: Context,
    private val emailRepository: EmailRepository = EmailRepository()
) : ViewModel() {

    private val ttsService: TtsService

    init {
        ttsService = TtsService(context.applicationContext) {
            Log.d("EmailViewModel", "TTS Service is ready from ViewModel.")
            // You could optionally speak a welcome message here
        }
    }

    private fun speak(text: String) {
        // This helper centralizes the call to the ttsService
        ttsService.speak(text)
    }

    override fun onCleared() {
        super.onCleared()
        ttsService.shutdown()
        Log.d("EmailViewModel", "ViewModel cleared, TTS shutdown.")
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
        val lastSpeechResult: String? = null
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
                        speak(errorMessage)
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

        // Handle based on navigation instruction first for simplicity
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
                        _uiState.value = _uiState.value.copy(
                            navigationEvent = NavigationEvent.ShowError("Unknown intent: ${intentResponse.intent}")
                        )
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
            _uiState.value = _uiState.value.copy(
                navigationEvent = NavigationEvent.ShowError(
                    "No draft available to edit. Create a draft first by saying 'write email to [person]'"
                )
            )
        }
    }

    private fun handleSendIntent(context: Context) {
        Log.d("EmailViewModel", "Handling send intent")
        val currentDraft = _uiState.value.currentDraft

        if (currentDraft?.draft_id != null) {
            // Validate draft has required content
            if (currentDraft.to.isNullOrBlank() || currentDraft.body.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(
                    navigationEvent = NavigationEvent.ShowError(
                        "Draft is incomplete. Please add recipient and content before sending."
                    )
                )
                return
            }
            sendEmail(context, currentDraft.draft_id)
        } else {
            _uiState.value = _uiState.value.copy(
                navigationEvent = NavigationEvent.ShowError(
                    "No draft available to send. Create a draft first."
                )
            )
        }
    }

    private fun handleOtherIntent(context: Context, intentResponse: IntentResponse, originalInput: String) {
        Log.d("EmailViewModel", "Handling other intent")

        // Check for navigation instructions
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
                // Final check if this might be an email-related query
                val lowerInput = originalInput.lowercase()
                if ((lowerInput.contains("email") || lowerInput.contains("mail")) &&
                    (lowerInput.contains("send") || lowerInput.contains("write") || lowerInput.contains("draft"))) {
                    Log.d("EmailViewModel", "Chat interface but detected email intent - creating draft")
                    navigateToComposer()
                    createDraft(context, originalInput)
                } else {
                    // Handle as general query
                    _uiState.value = _uiState.value.copy(
                        navigationEvent = NavigationEvent.ToChat
                    )
                }
            }
            else -> {
                _uiState.value = _uiState.value.copy(
                    navigationEvent = NavigationEvent.ShowError(
                        intentResponse.suggested_action ?: "I didn't understand that command."
                    )
                )
            }
        }
    }

    // Navigation Functions
    private fun navigateToComposer() {
        _uiState.value = _uiState.value.copy(
            currentScreen = "composer",
            navigationEvent = NavigationEvent.ToComposer
        )
    }

    private fun navigateToInbox(context: Context) {
        _uiState.value = _uiState.value.copy(
            currentScreen = "inbox",
            navigationEvent = NavigationEvent.ToInbox
        )
        fetchInboxEmails(context)
    }

    private fun navigateToDrafts() {
        _uiState.value = _uiState.value.copy(
            currentScreen = "drafts",
            navigationEvent = NavigationEvent.ToDrafts
        )
    }

    // Email Operations
    fun createDraft(context: Context, prompt: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            emailRepository.createDraft(context, prompt) { result ->
                when (result) {
                    is DraftResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            currentDraft = result.draftResponse,
                            isDraftCreated = true,
                            navigationEvent = NavigationEvent.ShowSuccess("Email draft created successfully!")
                        )
                        Log.d("EmailViewModel", "Draft created: ${result.draftResponse.draft_id}")
                        speak(result.draftResponse.body)
                    }
                    is DraftResult.Error -> {
                        val errorMessage = "Failed to create draft: ${result.message}"
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            navigationEvent = NavigationEvent.ShowError(errorMessage)
                        )
                        speak(errorMessage)
                        Log.e("EmailViewModel", "Draft creation failed: ${result.message}")
                    }
                }
            }
        }
    }

    fun editDraft(context: Context, draftId: String, editPrompt: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            emailRepository.editDraft(context, draftId, editPrompt) { result ->
                when (result) {
                    is DraftResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            currentDraft = result.draftResponse,
                            navigationEvent = NavigationEvent.ShowSuccess("Draft updated successfully!")
                        )
                        speak(result.draftResponse.body)
                        Log.d("EmailViewModel", "Draft edited: ${result.draftResponse.draft_id}")
                    }
                    is DraftResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            navigationEvent = NavigationEvent.ShowError("Failed to edit draft: ${result.message}")
                        )
                        speak("Failed to edit draft: ${result.message}")
                        Log.e("EmailViewModel", "Draft edit failed: ${result.message}")
                    }
                }
            }
        }
    }

    fun sendEmail(context: Context, draftId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            emailRepository.confirmSend(context, draftId, "send") { result ->
                when (result) {
                    is SendResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            navigationEvent = NavigationEvent.SendEmail,
                            currentDraft = null,
                            isDraftCreated = false
                        )
                        Log.d("EmailViewModel", "Email sent successfully")
                    }
                    is SendResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            navigationEvent = NavigationEvent.ShowError("Failed to send email: ${result.message}")
                        )
                        speak("Failed to send email: ${result.message}")
                        Log.e("EmailViewModel", "Email send failed: ${result.message}")
                    }
                }
            }
        }
    }

    private fun fetchInboxEmails(context: Context) {
        viewModelScope.launch {
            emailRepository.fetchInboxEmails(context) { result ->
                when (result) {
                    is InboxResult.Success -> {
                        Log.d("EmailViewModel", "Inbox loaded successfully")
                    }
                    is InboxResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            navigationEvent = NavigationEvent.ShowError("Failed to load inbox: ${result.message}")
                        )
                        Log.e("EmailViewModel", "Inbox fetch failed: ${result.message}")
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
}