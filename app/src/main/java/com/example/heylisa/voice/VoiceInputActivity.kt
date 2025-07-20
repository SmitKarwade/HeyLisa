package com.example.heylisa.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
            val currentFinalText = finalText.value

            LaunchedEffect(currentFinalText) {
                Log.d("VoiceInputActivity", "LaunchedEffect triggered with text: ${finalText.value}")
                val text = finalText.value.lowercase()
                if (text.isNotEmpty() && (text.contains("send an email to") || text.contains("draft an email to"))) {
                    if (!showEmailCompose) { // Only attempt to show if not already shown
                        Log.d("VoiceInputActivity", "Email command detected: $text. Setting showEmailCompose = true")
                        viewModel.handleSpeechResult(currentFinalText) // Pass the captured value
                        showEmailCompose = true
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
                    EmailComposeScreen(
                        context = LocalContext.current,
                        onDismiss = {
                            showEmailCompose = false
                            finalText.value = ""
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

    // Initialize fields from initialText
    LaunchedEffect(initialText) {
        val emailMatch = Regex("(?<=send an email to|draft an email to)\\s+([\\w\\.-]+@[\\w\\.-]+)").find(initialText.lowercase())
        toEmail = emailMatch?.groupValues?.getOrNull(1) ?: "Unknown Recipient"
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
                .padding(bottom = 60.dp, top = 20.dp), // Minimal padding, similar to HeyLisaBar
            shape = RoundedCornerShape(12.dp),
            color = Color.White
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxSize()
            ) {
                // Header Section (Google-like top fields)
                Text(
                    text = "New Message",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // To Field
                Text("To", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
                TextField(
                    value = toEmail,
                    onValueChange = { if (isEditing) toEmail = it },
                    enabled = isEditing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
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
                )

                // Action Buttons (Google-like bottom row)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { isEditing = !isEditing }) {
                        Text(if (isEditing) "Done" else "Edit")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            viewModel.createDraft(context = context, prompt = "To: $toEmail\nSubject: $subject\n$emailBody")
                            onDismiss()
                        },
                        enabled = toEmail.isNotEmpty() && emailBody.isNotEmpty()
                    ) {
                        Text("Send")
                    }
                }

                // Draft Response
                uiState.draftResponse?.let { draft ->
                    Text(
                        text = "Draft Created: ${draft.draft_id}",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}