package com.example.heylisa.model

// IntentRequest.kt
data class IntentRequest(
    val user_input: String,
    val current_screen: String
)

// IntentResponse.kt
data class IntentResponse(
    val intent: String,
    val confidence: Double,
    val urgency: String,
    val recipient_mentioned: Boolean,
    val time_mentioned: Boolean,
    val email_type: String,
    val suggested_action: String,
    val current_screen: String,
    val navigation_instruction: String,
    val timestamp: String,
    val error: String? = null
)
