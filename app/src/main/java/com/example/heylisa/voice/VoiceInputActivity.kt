package com.example.heylisa.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.heylisa.auth.AuthViewModel
import com.example.heylisa.constant.Noisy
import kotlinx.coroutines.delay

class VoiceInputActivity : ComponentActivity() {

    private val isListening = mutableStateOf(false)
    private val partialText = mutableStateOf("")
    private val finalText = mutableStateOf("")

    private val partialReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.heylisa.PARTIAL_TEXT" -> {
                    val text = intent.getStringExtra("text") ?: return
                    if (text !in Noisy.noisyWords) {
                        partialText.value = text
                    } else {
                        Log.d("VoiceInputActivity", "🚫 Skipping noisy partial: $text")
                    }
                }

                "com.example.heylisa.RECOGNIZED_TEXT" -> {
                    val final = intent.getStringExtra("result") ?: return
                    Log.d("VoiceInputActivity", "Received final text: $final")
                    if (final !in Noisy.noisyWords) {
                        finalText.value = final
                    } else {
                        Log.d("VoiceInputActivity", "🚫 Skipping noisy final: $final")
                    }
                }

                "com.example.heylisa.CLEAR_TEXT" -> {
                    partialText.value = ""
                }
            }
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.heylisa.STATE_UPDATE") {
                val state = intent.getStringExtra("state") ?: return
                when (state) {
                    "wake_word_detected" -> {
                        Log.d("VoiceInputActivity", "Wake word detected")
                    }

                    "speech_recognition_started" -> {
                        isListening.value = true
                        Log.d("VoiceInputActivity", "Speech recognition started")
                    }

                    "wake_word_listening" -> {
                        isListening.value = false
                        Log.d("VoiceInputActivity", "Back to wake word listening")
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true


        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val filter = IntentFilter().apply {
            addAction("com.example.heylisa.PARTIAL_TEXT")
            addAction("com.example.heylisa.CLEAR_TEXT")
            addAction("com.example.heylisa.RECOGNIZED_TEXT")
        }
        registerReceiver(partialReceiver, filter, RECEIVER_EXPORTED)

        val stateFilter = IntentFilter("com.example.heylisa.STATE_UPDATE")
        registerReceiver(stateReceiver, stateFilter, RECEIVER_EXPORTED)

        enableEdgeToEdge()
        setContent {
            val viewModel: AuthViewModel = viewModel()
            var showEmailCompose by remember { mutableStateOf(false) }
            var hasCalledDraft by remember { mutableStateOf(false) }
            val currentFinalText = finalText.value

            LaunchedEffect(currentFinalText) {
                Log.d(
                    "VoiceInputActivity",
                    "LaunchedEffect triggered with text: ${finalText.value}"
                )
                val text = finalText.value.lowercase()
                if (text.isNotEmpty() && (text.contains("send") || text.contains("draft "))) {
                    if (!showEmailCompose && !hasCalledDraft) {
                        Log.d(
                            "VoiceInputActivity",
                            "Email command detected: $text. Creating draft..."
                        )
                        viewModel.handleSpeechResult(currentFinalText)
                        viewModel.createDraft(
                            context = this@VoiceInputActivity,
                            prompt = currentFinalText
                        )
                        showEmailCompose = true
                        hasCalledDraft = true
                        Toast.makeText(
                            this@VoiceInputActivity,
                            "Creating draft",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            LaunchedEffect(Unit) {
                isListening.value = true
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x00000000))
                    .clickable {
                        if (!showEmailCompose) {
                            finish()
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color(0x00000000)),
                    contentAlignment = Alignment.Center
                ) {
                    HeyLisaBar(
                        text = partialText,
                        onMicClick = { },
                        onSendClick = {
                            partialText.value = ""
                        },
                        onTextChange = {
                            partialText.value = it
                        },
                        isListening = isListening.value
                    )
                }

                if (showEmailCompose) {
                    EmailComposeScreen(
                        context = LocalContext.current,
                        onDismiss = {
                            showEmailCompose = false
                            hasCalledDraft = false
                            finalText.value = ""
                            viewModel.clearDraft()
                            Log.d(
                                "VoiceInputActivity",
                                "Email compose dismissed, finalText cleared"
                            )
                        },
                        viewModel = viewModel,
                        initialText = finalText.value
                    )
                }
            }
        }
    }

    @Composable
    fun EmailComposeScreen(
        context: Context,
        onDismiss: () -> Unit,
        viewModel: AuthViewModel,
        initialText: String
    ) {
        var isEditing by remember { mutableStateOf(false) }
        var toEmail by remember { mutableStateOf("") }
        var subject by remember { mutableStateOf("") }
        var emailBody by remember { mutableStateOf("") }
        var currentDraftId by remember { mutableStateOf<String?>(null) }
        var isVoiceEditing by remember { mutableStateOf(false) }
        val uiState by viewModel.uiState.collectAsState()

        // Track if we've already populated from draft response
        var hasPopulatedFromDraft by remember { mutableStateOf(false) }

        // NEW: Listen for edit commands from voice
        LaunchedEffect(finalText.value) {
            val text = finalText.value.lowercase()
            if (text.isNotEmpty() && currentDraftId != null && !isVoiceEditing) {
                // Check for edit commands
                if (text.contains("edit it") || text.contains("change") || text.contains("modify it")) {
                    Log.d("VoiceInputActivity", "Edit command detected: $text")
                    isVoiceEditing = true

                    // Send edit request to API
                    viewModel.editDraft(
                        context = context,
                        draftId = currentDraftId!!,
                        editPrompt = finalText.value
                    )

                    Toast.makeText(context, "Processing edit command...", Toast.LENGTH_SHORT).show()

                    // Clear the final text to prevent re-triggering
                    finalText.value = ""
                }
            }
        }

        // Populate fields from draft response when it becomes available
        LaunchedEffect(uiState.draftResponse) {
            uiState.draftResponse?.let { draft ->
                if (!hasPopulatedFromDraft) {
                    toEmail = draft.to
                    subject = draft.subject
                    emailBody = draft.body
                    currentDraftId = draft.draft_id
                    hasPopulatedFromDraft = true
                } else if (isVoiceEditing) {
                    // Update fields when voice editing response comes back
                    toEmail = draft.to
                    subject = draft.subject
                    emailBody = draft.body
                    currentDraftId = draft.draft_id
                    isVoiceEditing = false
                    Toast.makeText(context, "Email updated via voice command", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Rest of your EmailComposeScreen code...
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = 40.dp,
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 100.dp
                    )
                    .fillMaxHeight()
                    .align(Alignment.TopCenter),
                shape = RoundedCornerShape(12.dp),
                color = Color.White
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxSize()
                ) {
                    // Header Section with Edit and Close Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "New Message",
                            style = MaterialTheme.typography.titleLarge
                        )

                        Row {
                            // Only show manual Edit button
                            if (currentDraftId != null) {
                                Button(
                                    onClick = {
                                        isEditing = !isEditing
                                        Toast.makeText(
                                            context,
                                            if (isEditing) "Manual editing enabled" else "Manual editing disabled",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    enabled = !uiState.isLoading,
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text(if (isEditing) "✏️ Done" else "✏️ Edit")
                                }
                            }

                            // Close Button
                            Button(
                                onClick = onDismiss,
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("✕")
                            }
                        }
                    }

                    // Voice editing status
                    if (isVoiceEditing) {
                        Text(
                            text = "🎤 Processing voice edit command...",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(8.dp)
                        )
                    }

                    // Loading state
                    if (uiState.isLoading && uiState.draftResponse == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (hasPopulatedFromDraft) "Updating email draft..." else "Generating email draft...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    // Error display
                    uiState.error?.let { error ->
                        Text(
                            text = "Error: $error",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .background(
                                    MaterialTheme.colorScheme.errorContainer,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(8.dp)
                        )
                    }

                    // Email form fields
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // To Field
                        EmailField(
                            label = "To",
                            value = toEmail,
                            onValueChange = { if (isEditing) toEmail = it },
                            enabled = isEditing,
                            placeholder = "Recipient email"
                        )

                        // Subject Field
                        EmailField(
                            label = "Subject",
                            value = subject,
                            onValueChange = { if (isEditing) subject = it },
                            enabled = isEditing,
                            placeholder = "Email subject"
                        )

                        // Body Field - Custom scrollable implementation
                        // Body Field - Better scrollable implementation
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Body",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Black,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )

                            OutlinedTextField(
                                value = emailBody,
                                onValueChange = { if (isEditing) emailBody = it },
                                enabled = isEditing,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(),  // Fill remaining space
                                placeholder = {
                                    Text(
                                        "Email content",
                                        color = Color.Gray
                                    )
                                },
                                maxLines = Int.MAX_VALUE,
                                singleLine = false,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.Black,
                                    unfocusedTextColor = Color.Black,
                                    disabledTextColor = Color.Black,
                                    focusedBorderColor = if (isEditing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                ),
                                shape = RoundedCornerShape(8.dp),
                                textStyle = TextStyle(
                                    color = Color.Black
                                )
                            )
                        }
                    }

                    // Action Buttons - Changed second button from Edit to Cancel
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        // Cancel Button (replaces the second Edit button)
                        Button(
                            onClick = {
                                if (isEditing) {
                                    isEditing = false
                                    Toast.makeText(context, "Editing cancelled", Toast.LENGTH_SHORT)
                                        .show()
                                } else {
                                    onDismiss()
                                }
                            }
                        ) {
                            Text(if (isEditing) "Cancel Edit" else "Cancel")
                        }

                        Button(
                            onClick = {
                                if (currentDraftId != null) {
                                    viewModel.confirmSendEmail(
                                        context = context,
                                        draftId = currentDraftId!!,
                                        action = "send"
                                    )
                                }
                            },
                            enabled = toEmail.isNotEmpty() &&
                                    subject.isNotEmpty() &&
                                    emailBody.isNotEmpty() &&
                                    !uiState.isLoading
                        ) { Text("Send") }
                    }
                }
            }
        }

        // Handle voice editing commands
        if (isVoiceEditing && currentDraftId != null) {
            LaunchedEffect(isVoiceEditing) {
                // Auto-disable voice editing after 30 seconds
                delay(30000)
                isVoiceEditing = false
                Toast.makeText(context, "Voice editing timed out", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Composable
    fun EmailField(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        enabled: Boolean,
        placeholder: String,
        modifier: Modifier = Modifier,
        maxLines: Int = 1
    ) {
        Column(modifier = modifier) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Black,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            TextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .border(
                        1.dp,
                        if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(8.dp)
                    ),
                placeholder = {
                    Text(
                        placeholder,
                        color = Color.Gray
                    )
                },
                maxLines = maxLines,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black,
                    disabledTextColor = Color.Black
                ),
                shape = RoundedCornerShape(8.dp),
                textStyle = TextStyle(
                    color = Color.Black
                )
            )
        }
    }
}