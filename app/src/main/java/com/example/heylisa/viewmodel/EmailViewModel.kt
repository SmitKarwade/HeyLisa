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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class EmailViewModel(
    private val emailRepository: EmailRepository = EmailRepository()
) : ViewModel() {

    // UI State
    data class EmailUiState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val currentScreen: String = "home",

        // Intent & Navigation
        val lastIntent: IntentResponse? = null,
        val navigationEvent: NavigationEvent? = null,

        // Email Draft
        val currentDraft: DraftResponse? = null,
        val isDraftCreated: Boolean = false,

        // Speech Recognition
        val isSpeechActive: Boolean = false,
        val lastSpeechResult: String? = null,

        // Inbox
        //val inboxEmails: List<InboxResponse.EmailMessage> = emptyList()
    )

    // Result Classes
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

    // Navigation Events
    sealed class NavigationEvent {
        object ToComposer : NavigationEvent()
        object ToInbox : NavigationEvent()
        object ToDrafts : NavigationEvent()
        object ToSent : NavigationEvent()
        object ToChat : NavigationEvent()
        object SendEmail : NavigationEvent()
        object ShowSchedulePicker : NavigationEvent()
        data class ShowError(val message: String) : NavigationEvent()
    }

    private val _uiState = MutableStateFlow(EmailUiState())
    val uiState: StateFlow<EmailUiState> = _uiState

    // Main function to process user input
    fun processUserInput(context: Context, input: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                lastSpeechResult = input
            )

            // Get intent from AI
            emailRepository.getIntent(
                context = context,
                userInput = input,
                currentScreen = _uiState.value.currentScreen
            ) { result ->
                when (result) {
                    is IntentResult.Success -> {
                        handleIntentResponse(context, result.intentResponse, input)
                    }
                    is IntentResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = result.message
                        )
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

        when (intentResponse.navigation_instruction) {
            "navigate_to_composer" -> {
                navigateToComposer()
                if (intentResponse.intent == "compose") {
                    createDraft(context, originalInput)
                }
            }

            "navigate_to_inbox" -> {
                navigateToInbox(context)
            }

            "navigate_to_drafts" -> {
                navigateToDrafts()
            }

            "stay_and_edit" -> {
                val currentDraft = _uiState.value.currentDraft
                if (currentDraft?.draft_id != null) {
                    editDraft(context, currentDraft.draft_id, originalInput)
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "No draft available to edit"
                    )
                }
            }

            "send_current_draft" -> {
                val currentDraft = _uiState.value.currentDraft
                if (currentDraft?.draft_id != null) {
                    sendEmail(context, currentDraft.draft_id)
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "No draft available to send"
                    )
                }
            }

            "save_and_go_to_drafts" -> {
                // Draft is already saved, just navigate
                navigateToDrafts()
            }

            "show_schedule_picker" -> {
                _uiState.value = _uiState.value.copy(
                    navigationEvent = NavigationEvent.ShowSchedulePicker
                )
            }

            "show_chat_interface" -> {
                _uiState.value = _uiState.value.copy(
                    navigationEvent = NavigationEvent.ToChat
                )
            }

            else -> {
                _uiState.value = _uiState.value.copy(
                    error = intentResponse.suggested_action
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
                            isDraftCreated = true
                        )
                        Log.d("EmailViewModel", "Draft created successfully")
                    }
                    is DraftResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = result.message
                        )
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
                            error = null
                        )
                        Log.d("EmailViewModel", "Draft edited successfully")
                    }
                    is DraftResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = result.message
                        )
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
                            currentDraft = null // Clear draft after sending
                        )
                        Log.d("EmailViewModel", "Email sent successfully")
                    }
                    is SendResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = result.message
                        )
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
                        _uiState.value = _uiState.value.copy(
                            //inboxEmails = result.inboxResponse.messages
                        )
                        //Log.d("EmailViewModel", "Inbox loaded: ${result.inboxResponse.messages.size} emails")
                    }
                    is InboxResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            error = result.message
                        )
                        Log.e("EmailViewModel", "Inbox fetch failed: ${result.message}")
                    }
                }
            }
        }
    }

    // Speech Recognition
    fun setSpeechActive(active: Boolean) {
        _uiState.value = _uiState.value.copy(isSpeechActive = active)
    }

    // Utility Functions
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

    // Getters for easy access
    fun getDraftTo(): String? = _uiState.value.currentDraft?.to
    fun getDraftSubject(): String? = _uiState.value.currentDraft?.subject
    fun getDraftBody(): String? = _uiState.value.currentDraft?.body
    fun getDraftId(): String? = _uiState.value.currentDraft?.draft_id
}