package com.example.heylisa.model

import androidx.compose.runtime.Stable
import java.text.SimpleDateFormat
import java.util.*

@Stable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val messageType: MessageType = MessageType.TEXT,
    val deliveryStatus: DeliveryStatus = if (isFromUser) DeliveryStatus.SENT else DeliveryStatus.NONE // ✅ Add delivery status
) {
    fun getFormattedTime(): String {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        return formatter.format(Date(timestamp))
    }
}

enum class MessageType {
    TEXT,
    EMAIL_DRAFT,
    SYSTEM_STATUS
}

enum class DeliveryStatus {
    NONE,        // For assistant messages
    SENT,        // Single tick - message sent
    DELIVERED,   // Double tick - message delivered/processed successfully
    FAILED       // For failed messages (optional)
}

@Stable
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val isListening: Boolean = false,
    val isProcessing: Boolean = false,
    val currentInput: String = "",
    val showDialog: Boolean = false,
    val isWakeWordActive: Boolean = true
)