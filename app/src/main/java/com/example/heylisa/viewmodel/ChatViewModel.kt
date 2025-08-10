package com.example.heylisa.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import com.example.heylisa.model.ChatMessage
import com.example.heylisa.model.ChatState
import com.example.heylisa.model.MessageType
import com.example.heylisa.model.DeliveryStatus
import com.example.heylisa.util.VoskWakeWordService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ChatViewModel : ViewModel() {

    private val _chatState = MutableStateFlow(ChatState())
    val chatState: StateFlow<ChatState> = _chatState.asStateFlow()

    // Add new state to differentiate between wake word and speech recognition
    private val _isWakeWordListening = MutableStateFlow(true)
    private val _isSpeechRecognizing = MutableStateFlow(false)

    fun showChatDialog() {
        _chatState.update { it.copy(showDialog = true) }
        // Clear previous messages and start fresh
        _chatState.update { it.copy(messages = emptyList()) }
        addSystemMessage("Hey! I'm Lisa. How can I help you today? 👋")

        // Reset listening states when dialog opens
        _isWakeWordListening.value = true
        _isSpeechRecognizing.value = false
    }

    fun hideChatDialog(context: Context) {
        _chatState.update { it.copy(showDialog = false) }
        // Cancel all ongoing operations and return to wake word
        cancelAllOperations(context)
        // Clear all messages
        _chatState.update { it.copy(messages = emptyList()) }

        // Reset states
        _isWakeWordListening.value = false
        _isSpeechRecognizing.value = false
    }

    private fun cancelAllOperations(context: Context) {
        // Stop speech recognition
        context.sendBroadcast(Intent(VoskWakeWordService.ACTION_STOP_SPEECH_RECOGNITION))

        // Reset to wake word detection
        context.sendBroadcast(Intent("com.example.heylisa.RESTORE_WAKE_WORD"))
    }

    fun addUserMessage(message: String): String? {
        // Filter out unwanted messages
        if (message.isBlank() || isUnwantedMessage(message)) return null

        val userMessage = ChatMessage(
            content = message,
            isFromUser = true,
            deliveryStatus = DeliveryStatus.SENT // ✅ Start with SENT status
        )

        _chatState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                currentInput = ""
            )
        }

        return userMessage.id // ✅ Return message ID for tracking
    }

    // ✅ New method to update message delivery status
    fun updateMessageDeliveryStatus(messageId: String, status: DeliveryStatus) {
        _chatState.update { state ->
            val updatedMessages = state.messages.map { message ->
                if (message.id == messageId) {
                    message.copy(deliveryStatus = status)
                } else {
                    message
                }
            }
            state.copy(messages = updatedMessages)
        }
    }

    fun addAssistantMessage(message: String, messageType: MessageType = MessageType.TEXT) {
        // Filter out generic success messages - we'll handle email content separately
        if (message.isBlank() || isGenericMessage(message)) return

        val assistantMessage = ChatMessage(
            content = message,
            isFromUser = false,
            messageType = messageType,
            deliveryStatus = DeliveryStatus.NONE // Assistant messages don't need delivery status
        )

        _chatState.update { state ->
            state.copy(messages = state.messages + assistantMessage)
        }
    }

    // New method to add email draft content
    fun addEmailDraftMessage(emailContent: String) {
        if (emailContent.isBlank()) return

        val emailMessage = ChatMessage(
            content = emailContent,
            isFromUser = false,
            messageType = MessageType.EMAIL_DRAFT,
            deliveryStatus = DeliveryStatus.NONE
        )

        _chatState.update { state ->
            state.copy(messages = state.messages + emailMessage)
        }
    }

    private fun addSystemMessage(message: String) {
        val systemMessage = ChatMessage(
            content = message,
            isFromUser = false,
            messageType = MessageType.SYSTEM_STATUS,
            deliveryStatus = DeliveryStatus.NONE
        )

        _chatState.update { state ->
            state.copy(messages = state.messages + systemMessage)
        }
    }

    // Filter unwanted user messages
    private fun isUnwantedMessage(message: String): Boolean {
        val unwantedPhrases = listOf(
            "no speech detected",
            "speech not detected",
            "no voice detected",
            "voice not detected",
            "listening timeout",
            "recording timeout",
            "audio timeout"
        )

        return unwantedPhrases.any {
            message.lowercase().contains(it.lowercase())
        }
    }

    private fun isGenericMessage(message: String): Boolean {
        val genericPhrases = listOf(
            "email draft created successfully",
            "draft created",
            "processing complete"
        )

        return genericPhrases.any {
            message.lowercase().contains(it.lowercase())
        }
    }

    fun updateCurrentInput(input: String) {
        // Don't update if it's an unwanted message
        if (isUnwantedMessage(input)) return

        _chatState.update { it.copy(currentInput = input) }
    }

    // Updated method to handle wake word vs speech recognition states
    fun setListening(isListening: Boolean) {
        _chatState.update { it.copy(isListening = isListening) }
    }

    // New methods to properly differentiate states
    fun setWakeWordListening(isListening: Boolean) {
        _isWakeWordListening.value = isListening
        _isSpeechRecognizing.value = false
        _chatState.update { it.copy(isListening = false) } // Don't show "Listening" for wake word
    }

    fun setSpeechRecognizing(isRecognizing: Boolean) {
        _isSpeechRecognizing.value = isRecognizing
        _isWakeWordListening.value = false
        _chatState.update { it.copy(isListening = isRecognizing) } // Show "Listening" only for speech
    }

    fun setProcessing(isProcessing: Boolean) {
        _chatState.update { it.copy(isProcessing = isProcessing) }
    }

    // Updated status text logic
    fun getStatusText(): String {
        val state = _chatState.value
        return when {
            state.isProcessing -> "Processing..."
            _isSpeechRecognizing.value -> "Listening..."
            _isWakeWordListening.value -> "Say 'Hey Lisa'"
            else -> "Ready to help"
        }
    }
}