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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.heylisa.constant.Noisy
import com.example.heylisa.viewmodel.EmailViewModel
import com.example.heylisa.request.DraftResponse

class VoiceInputActivity : ComponentActivity() {

    private val isListening = mutableStateOf(false)
    private val partialText = mutableStateOf("")
    private val finalText = mutableStateOf("")

    private val partialReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.heylisa.PARTIAL_TEXT" -> {
                    val text = intent.getStringExtra("text") ?: return
                    if (text !in Noisy.noisyWords && text.isNotBlank()) {
                        partialText.value = text
                        Log.d("VoiceInputActivity", "Partial: $text")
                    }
                }

                "com.example.heylisa.RECOGNIZED_TEXT" -> {
                    val final = intent.getStringExtra("result") ?: return
                    Log.d("VoiceInputActivity", "Final text received: $final")
                    if (final !in Noisy.noisyWords && final.isNotBlank()) {
                        finalText.value = final.trim()
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

        setupWindow()
        registerBroadcastReceivers()

        enableEdgeToEdge()
        setContent {
            VoiceInputScreen()
        }
    }

    private fun setupWindow() {
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerBroadcastReceivers() {
        val partialFilter = IntentFilter().apply {
            addAction("com.example.heylisa.PARTIAL_TEXT")
            addAction("com.example.heylisa.CLEAR_TEXT")
            addAction("com.example.heylisa.RECOGNIZED_TEXT")
        }
        registerReceiver(partialReceiver, partialFilter, RECEIVER_EXPORTED)

        val stateFilter = IntentFilter("com.example.heylisa.STATE_UPDATE")
        registerReceiver(stateReceiver, stateFilter, RECEIVER_EXPORTED)
    }

    @Composable
    fun VoiceInputScreen() {
        val emailViewModel: EmailViewModel = viewModel()
        var showEmailCompose by remember { mutableStateOf(false) }
        val uiState by emailViewModel.uiState.collectAsState()

        // Handle navigation events from ViewModel
        LaunchedEffect(uiState.navigationEvent) {
            uiState.navigationEvent?.let { event ->
                handleNavigationEvent(event) { shouldShowCompose ->
                    showEmailCompose = shouldShowCompose
                }
                emailViewModel.onNavigationHandled()
            }
        }

        // Process voice input
        LaunchedEffect(finalText.value) {
            val text = finalText.value.trim()
            if (text.isNotEmpty()) {
                Log.d("VoiceInputActivity", "Processing voice input: '$text'")
                emailViewModel.processUserInput(this@VoiceInputActivity, text)
                finalText.value = "" // Clear to prevent reprocessing
            }
        }

        // Main UI Layout
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .clickable {
                    if (!showEmailCompose) {
                        finish()
                    }
                }
        ) {
            // Voice Input Bar at bottom
            VoiceInputBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                emailViewModel = emailViewModel
            )

            // Email Compose Overlay
            if (showEmailCompose) {
                EmailComposeScreen(
                    onDismiss = {
                        showEmailCompose = false
                        emailViewModel.clearDraft()
                    },
                    emailViewModel = emailViewModel
                )
            }

            // Loading Overlay - Only show when not in compose mode
            if (uiState.isLoading && !showEmailCompose) {
                LoadingOverlay()
            }
        }
    }

    @Composable
    fun VoiceInputBar(
        modifier: Modifier = Modifier,
        emailViewModel: EmailViewModel
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            HeyLisaBar(
                text = partialText,
                onMicClick = { /* Mic functionality handled by broadcast receiver */ },
                onSendClick = {
                    if (partialText.value.isNotEmpty()) {
                        emailViewModel.processUserInput(this@VoiceInputActivity, partialText.value)
                        partialText.value = ""
                    }
                },
                onTextChange = { partialText.value = it },
                isListening = isListening.value
            )
        }
    }

    @Composable
    fun LoadingOverlay() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.padding(32.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Processing your request...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    @Composable
    fun EmailComposeScreen(
        onDismiss: () -> Unit,
        emailViewModel: EmailViewModel
    ) {
        val context = LocalContext.current
        val uiState by emailViewModel.uiState.collectAsState()
        val currentDraft = uiState.currentDraft

        // Handle voice commands in composer - FIXED to avoid mis-triggering send
        LaunchedEffect(finalText.value) {
            val text = finalText.value.trim()
            if (text.isNotEmpty() && currentDraft != null) {
                Log.d("VoiceInputActivity", "Processing voice command in composer: '$text'")
                // Process only if not a send command while editing
                if (!text.lowercase().contains("send")) {
                    emailViewModel.processUserInput(context, text)
                } else {
                    // Handle send explicitly
                    currentDraft.draft_id?.let { draftId ->
                        emailViewModel.sendEmail(context, draftId)
                    }
                }
                finalText.value = ""
            }
        }

        // Force UI refresh after state change (e.g., after edit)
        LaunchedEffect(uiState.currentDraft) {
            if (uiState.currentDraft != null) {
                // Optional: Add animation or refresh logic if needed
                Log.d("VoiceInputActivity", "Draft updated in UI")
            }
        }

        // Create interaction source to prevent graying out on touch
        val interactionSource = remember { MutableInteractionSource() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    // Empty click handler - prevents closing but removes ripple effect
                }
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(16.dp)
                    .align(Alignment.Center),
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxSize()
                ) {
                    // Header
                    ComposeHeader(onDismiss = onDismiss)

                    Spacer(modifier = Modifier.height(16.dp))

                    // Voice command hint
                    //VoiceCommandHint()

                    Spacer(modifier = Modifier.height(16.dp))

                    // Email content or loading
                    if (currentDraft != null) {
                        EmailContent(
                            draft = currentDraft,
                            isLoading = uiState.isLoading,
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Action buttons
                        ActionButtons(
                            currentDraft = currentDraft,
                            isLoading = uiState.isLoading,
                            onDismiss = onDismiss,
                            onSend = { draftId ->
                                emailViewModel.sendEmail(context, draftId)
                            }
                        )
                    } else {
                        // Loading state
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Generating email draft...",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ComposeHeader(onDismiss: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Email Draft",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            IconButton(onClick = onDismiss) {
                Text(
                    text = "✕",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    @Composable
    fun VoiceCommandHint() {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "💡 Voice Commands: \"Make it shorter\", \"Change subject to...\", \"Send this email\"",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }

    @Composable
    fun EmailContent(
        draft: DraftResponse,
        isLoading: Boolean,
        modifier: Modifier = Modifier
    ) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // To Field
            EmailDisplayField(
                label = "To",
                value = draft.to ?: "",
                placeholder = "Recipient will appear here"
            )

            // Subject Field
            EmailDisplayField(
                label = "Subject",
                value = draft.subject ?: "",
                placeholder = "Subject will appear here"
            )

            // Body Field with scroll
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Body",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        if (isLoading && draft.body.isNullOrBlank()) {
                            // Show loading in body area
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Generating content...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        } else {
                            // Scrollable text content
                            val scrollState = rememberScrollState()

                            Text(
                                text = draft.body?.takeIf { it.isNotBlank() }
                                    ?: "Email content will appear here...",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (draft.body?.isNotBlank() == true) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun EmailDisplayField(
        label: String,
        value: String,
        placeholder: String
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            OutlinedTextField(
                value = value,
                onValueChange = { },
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        placeholder,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(8.dp),
                singleLine = true
            )
        }
    }

    @Composable
    fun ActionButtons(
        currentDraft: DraftResponse?,
        isLoading: Boolean,
        onDismiss: () -> Unit,
        onSend: (String) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            OutlinedButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text("Cancel")
            }

            Button(
                onClick = {
                    currentDraft?.draft_id?.let(onSend)
                },
                enabled = currentDraft != null &&
                        !isLoading &&  // Disable during loading to prevent auto-send overlap
                        !currentDraft.to.isNullOrBlank() &&
                        !currentDraft.body.isNullOrBlank()
            ) {
                if (isLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text("Processing...")
                    }
                } else {
                    Text("Send Email")
                }
            }
        }
    }

    private fun handleNavigationEvent(
        event: EmailViewModel.NavigationEvent,
        onShowComposeChange: (Boolean) -> Unit
    ) {
        when (event) {
            EmailViewModel.NavigationEvent.ToComposer -> {
                onShowComposeChange(true)
                showToast("Opening email composer...")
            }
            EmailViewModel.NavigationEvent.ToInbox -> {
                showToast("Opening inbox...")
            }
            EmailViewModel.NavigationEvent.ToDrafts -> {
                showToast("Opening drafts...")
            }
            EmailViewModel.NavigationEvent.ToSent -> {
                showToast("Opening sent mail...")
            }
            EmailViewModel.NavigationEvent.SendEmail -> {
                showToast("Email sent successfully!")
                onShowComposeChange(false)
            }
            EmailViewModel.NavigationEvent.ToChat -> {
                showToast("I didn't understand that. Try: 'send email to [person]' or 'draft email about [topic]'")
            }
            is EmailViewModel.NavigationEvent.ShowError -> {
                showToast(event.message)
            }
            is EmailViewModel.NavigationEvent.ShowSuccess -> {
                showToast(event.message)
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.d("VoiceInputActivity", "Toast: $message")
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
