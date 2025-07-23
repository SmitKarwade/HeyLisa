package com.example.heylisa.model

// IntentRequest.kt
data class IntentRequest(
    val user_input: String,
    val current_screen: String = "home"
)

// IntentResponse.kt
data class IntentResponse(
    val intent: String,                   // "compose", "edit", "send", "other"
    val confidence: Float,                // 0.0 to 1.0
    val suggested_action: String,         // What the app should do
    val navigation_instruction: String,   // Navigation command for app
    val recipient_mentioned: Boolean      // Whether recipient was detected
)