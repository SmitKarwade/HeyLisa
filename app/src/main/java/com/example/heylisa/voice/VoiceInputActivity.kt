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
            var hasCalledDraft by remember { mutableStateOf(false) } // Add this flag
            val currentFinalText = finalText.value

            LaunchedEffect(currentFinalText) {
                Log.d("VoiceInputActivity", "LaunchedEffect triggered with text: ${finalText.value}")
                val text = finalText.value.lowercase()
                if (text.isNotEmpty() && (text.contains("send") || text.contains("draft "))) {
                    if (!showEmailCompose && !hasCalledDraft) { // Check both flags
                        Log.d("VoiceInputActivity", "Email command detected: $text. Creating draft...")
                        viewModel.handleSpeechResult(currentFinalText)

                        // Call createDraft HERE, not in the compose block
                        viewModel.createDraft(context = this@VoiceInputActivity, prompt = currentFinalText)

                        showEmailCompose = true
                        hasCalledDraft = true // Set flag to prevent multiple calls

                        Toast.makeText(this@VoiceInputActivity, "Creating draft", Toast.LENGTH_SHORT).show()
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
                        finish()
                    }
            ) {
                // Show either HeyLisaBar or EmailComposeScreen based on state
                if (showEmailCompose) {
                    // REMOVE this line: viewModel.createDraft(context = this@VoiceInputActivity, prompt = finalText.value)
                    // REMOVE this line: Toast.makeText(this@VoiceInputActivity, "Creating draft", Toast.LENGTH_SHORT).show()

                    EmailComposeScreen(
                        context = LocalContext.current,
                        onDismiss = {
                            showEmailCompose = false
                            hasCalledDraft = false // Reset the flag
                            finalText.value = ""
                            viewModel.clearDraft() // Clear draft state
                            Log.d("VoiceInputActivity", "Email compose dismissed, finalText cleared")
                        },
                        viewModel = viewModel,
                        initialText = finalText.value
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 50.dp)
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
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sendBroadcast(Intent("com.example.heylisa.RESTORE_WAKE_WORD"))
        unregisterReceiver(partialReceiver)
        unregisterReceiver(stateReceiver)
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
    val uiState by viewModel.uiState.collectAsState()

    // Track if we've already populated from draft response
    var hasPopulatedFromDraft by remember { mutableStateOf(false) }

    // Initialize fields from initialText
    LaunchedEffect(initialText) {
        val emailMatch = Regex("(?<=send an email to|draft an email to)\\s+([\\w\\.-]+@[\\w\\.-]+)").find(initialText.lowercase())
        toEmail = emailMatch?.groupValues?.getOrNull(1) ?: ""
    }

    // Populate fields from draft response when it becomes available
    LaunchedEffect(uiState.draftResponse) {
        uiState.draftResponse?.let { draft ->
            if (!hasPopulatedFromDraft) {
                toEmail = draft.to
                subject = draft.subject
                emailBody = draft.body
                hasPopulatedFromDraft = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                onClick = onDismiss,
                enabled = true
            )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f)
                .align(Alignment.Center)
                .padding(bottom = 60.dp, top = 20.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color.White
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxSize()
            ) {
                // Header Section
                Text(
                    text = "New Message",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Show loading state while creating draft
                if (uiState.isLoading && uiState.draftResponse == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Generating email draft...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // Show error if draft creation failed
                uiState.error?.let { error ->
                    Text(
                        text = "Error: $error",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                // To Field
                Text("To", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
                TextField(
                    value = toEmail,
                    onValueChange = { if (isEditing) toEmail = it },
                    enabled = isEditing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(8.dp)
                        ),
                    placeholder = { Text("Recipient email") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    textStyle = TextStyle(color = Color.Black)
                )

                // Subject Field
                Text("Subject", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
                TextField(
                    value = subject,
                    onValueChange = { if (isEditing) subject = it },
                    enabled = isEditing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(8.dp)
                        ),
                    placeholder = { Text("Email subject") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    textStyle = TextStyle(color = Color.Black)
                )

                // Body Field
                Text("Body", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
                TextField(
                    value = emailBody,
                    onValueChange = { if (isEditing) emailBody = it },
                    enabled = isEditing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 8.dp)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(8.dp)
                        ),
                    placeholder = { Text("Email content") },
                    maxLines = 10,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    textStyle = TextStyle(color = Color.Black)
                )

                // Show draft info if available
//                uiState.draftResponse?.let { draft ->
//                    Text(
//                        text = "✅ Draft generated successfully (ID: ${draft.draft_id.take(8)}...)",
//                        modifier = Modifier.padding(vertical = 4.dp),
//                        style = MaterialTheme.typography.bodySmall,
//                        color = Color.Green
//                    )
//                }

                // Action Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = {
                        viewModel.clearDraft() // Clear the draft state
                        onDismiss()
                    }) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { isEditing = !isEditing },
                        enabled = uiState.draftResponse != null // Only enable when draft is loaded
                    ) {
                        Text(if (isEditing) "Done" else "Edit")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            viewModel.clearDraft()
                            onDismiss()
                        },
                        enabled = toEmail.isNotEmpty() && emailBody.isNotEmpty() && !uiState.isLoading
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}