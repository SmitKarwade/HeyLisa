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

import com.example.heylisa.constant.Noisy
import com.example.heylisa.viewmodel.EmailViewModel

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
            val emailViewModel: EmailViewModel = viewModel()
            var showEmailCompose by remember { mutableStateOf(false) }
            val currentFinalText = finalText.value
            val uiState by emailViewModel.uiState.collectAsState()

            LaunchedEffect(uiState.navigationEvent) {
                uiState.navigationEvent?.let { event ->
                    when (event) {
                        EmailViewModel.NavigationEvent.ToComposer -> {
                            showEmailCompose = true
                            Log.d("VoiceInputActivity", "Navigating to composer")
                            Toast.makeText(this@VoiceInputActivity, "Opening composer...", Toast.LENGTH_SHORT).show()
                        }
                        EmailViewModel.NavigationEvent.ToInbox -> {
                            // Could show inbox or navigate elsewhere
                            Toast.makeText(this@VoiceInputActivity, "Opening inbox...", Toast.LENGTH_SHORT).show()
                        }
                        EmailViewModel.NavigationEvent.SendEmail -> {
                            Toast.makeText(this@VoiceInputActivity, "Email sent!", Toast.LENGTH_LONG).show()
                            showEmailCompose = false
                            finalText.value = ""
                        }
                        is EmailViewModel.NavigationEvent.ShowError -> {
                            Toast.makeText(this@VoiceInputActivity, event.message, Toast.LENGTH_LONG).show()
                        }
                        else -> {
                            Log.d("VoiceInputActivity", "Unhandled navigation event: $event")
                        }
                    }
                    emailViewModel.onNavigationHandled()
                }
            }


            LaunchedEffect(currentFinalText) {
                Log.d("VoiceInputActivity", "LaunchedEffect triggered with text: ${finalText.value}")
                val text = finalText.value.trim()

                if (text.isNotEmpty()) {
                    Log.d("VoiceInputActivity", "Processing user input via AI: $text")

                    // Let AI determine what to do with this input
                    emailViewModel.processUserInput(this@VoiceInputActivity, text)

                    // Clear the final text to prevent re-processing
                    finalText.value = ""
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
                            // Process the typed text through AI as well
                            if (partialText.value.isNotEmpty()) {
                                emailViewModel.processUserInput(this@VoiceInputActivity, partialText.value)
                            }
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
                            emailViewModel.clearDraft()
                            Log.d("VoiceInputActivity", "Email compose dismissed")
                        },
                        emailViewModel = emailViewModel
                    )
                }
            }
        }
    }

    @Composable
    fun EmailComposeScreen(
        context: Context,
        onDismiss: () -> Unit,
        emailViewModel: EmailViewModel
    ) {
        var isEditing by remember { mutableStateOf(false) }
        val uiState by emailViewModel.uiState.collectAsState()

        // Get current draft data
        val currentDraft = uiState.currentDraft
        val toEmail = currentDraft?.to ?: ""
        val subject = currentDraft?.subject ?: ""
        val emailBody = currentDraft?.body ?: ""
        val currentDraftId = currentDraft?.draft_id

        // Handle voice commands for editing
        LaunchedEffect(finalText.value) {
            val text = finalText.value.lowercase().trim()
            if (text.isNotEmpty() && currentDraftId != null) {
                Log.d("VoiceInputActivity", "Processing voice command in composer: $text")

                // Process any voice command through AI
                emailViewModel.processUserInput(context, text)

                // Clear to prevent re-processing
                finalText.value = ""
            }
        }

        // Show toast messages based on state changes
        LaunchedEffect(uiState.error) {
            uiState.error?.let { error ->
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                emailViewModel.clearError()
            }
        }

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
                    // Header Section
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Email Draft",
                            style = MaterialTheme.typography.titleLarge
                        )

                        Row {
                            // Manual Edit Toggle
                            if (currentDraft != null) {
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
                                    Text(if (isEditing) "Done" else "Edit")
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

                    // Voice command hint
                    Text(
                        text = "💡 Say things like: \"Make it shorter\", \"Change subject\", \"Send this email\"",
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

                    // Loading state
                    if (uiState.isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (currentDraft == null) "Generating email draft..." else "Processing your request...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    // Email form fields - Now read-only unless manually editing
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // To Field
                        EmailField(
                            label = "To",
                            value = toEmail,
                            onValueChange = { /* Manual editing disabled in this version */ },
                            enabled = false, // Make read-only, voice commands handle changes
                            placeholder = "Recipient email"
                        )

                        // Subject Field
                        EmailField(
                            label = "Subject",
                            value = subject,
                            onValueChange = { /* Manual editing disabled in this version */ },
                            enabled = false,
                            placeholder = "Email subject"
                        )

                        // Body Field
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Body",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Black,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )

                            OutlinedTextField(
                                value = emailBody,
                                onValueChange = { /* Manual editing disabled in this version */ },
                                enabled = false,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(),
                                placeholder = {
                                    Text(
                                        "Email content will appear here...",
                                        color = Color.Gray
                                    )
                                },
                                maxLines = Int.MAX_VALUE,
                                singleLine = false,
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = Color.Black,
                                    disabledBorderColor = MaterialTheme.colorScheme.outline
                                ),
                                shape = RoundedCornerShape(8.dp),
                                textStyle = TextStyle(
                                    color = Color.Black
                                )
                            )
                        }
                    }

                    // Action Buttons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        // Cancel Button
                        Button(
                            onClick = onDismiss
                        ) {
                            Text("Cancel")
                        }

                        // Send Button
                        Button(
                            onClick = {
                                if (currentDraftId != null) {
                                    emailViewModel.sendEmail(context, currentDraftId)
                                } else {
                                    Toast.makeText(context, "No draft to send", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = currentDraft != null && !uiState.isLoading
                        ) {
                            Text("Send")
                        }
                    }
                }
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(partialReceiver)
            unregisterReceiver(stateReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("VoiceInputActivity", "Receiver not registered: ${e.message}")
        }
    }
}