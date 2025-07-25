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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.heylisa.constant.Noisy
import com.example.heylisa.viewmodel.EmailViewModel
import com.example.heylisa.request.DraftResponse

class VoiceInputActivity : ComponentActivity() {

    private val isListening = mutableStateOf(false)
    private val partialText = mutableStateOf("")
    private val finalText = mutableStateOf("")

    // Add TTS state tracking
    private val isTtsSpeaking = mutableStateOf(false)

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
            when (intent?.action) {
                "com.example.heylisa.STATE_UPDATE" -> {
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
                // Add TTS state handling
                "com.example.heylisa.TTS_STARTED" -> {
                    isTtsSpeaking.value = true
                    Log.d("VoiceInputActivity", "TTS started speaking")
                }
                "com.example.heylisa.TTS_FINISHED", "com.example.heylisa.TTS_ERROR" -> {
                    isTtsSpeaking.value = false
                    Log.d("VoiceInputActivity", "TTS finished speaking")
                }
            }
        }
    }

    class EmailViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(EmailViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return EmailViewModel(context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerBroadcastReceivers() {
        val partialFilter = IntentFilter().apply {
            addAction("com.example.heylisa.PARTIAL_TEXT")
            addAction("com.example.heylisa.CLEAR_TEXT")
            addAction("com.example.heylisa.RECOGNIZED_TEXT")
        }
        registerReceiver(partialReceiver, partialFilter, RECEIVER_EXPORTED)

        val stateFilter = IntentFilter().apply {
            addAction("com.example.heylisa.STATE_UPDATE")
            // Add TTS state actions
            addAction("com.example.heylisa.TTS_STARTED")
            addAction("com.example.heylisa.TTS_FINISHED")
            addAction("com.example.heylisa.TTS_ERROR")
        }
        registerReceiver(stateReceiver, stateFilter, RECEIVER_EXPORTED)
    }

    @Composable
    fun VoiceInputScreen() {
        val context = LocalContext.current
        val emailViewModel: EmailViewModel = viewModel(
            factory = EmailViewModelFactory(context.applicationContext)
        )
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

        // Process voice input - MODIFIED to only process when not TTS speaking
        LaunchedEffect(finalText.value, isTtsSpeaking.value) {
            val text = finalText.value.trim()
            if (text.isNotEmpty() && !isTtsSpeaking.value) {
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

            // TTS Speaking Indicator
            if (isTtsSpeaking.value) {
//                TtsSpeakingIndicator(
//                    modifier = Modifier.align(Alignment.TopCenter)
//                )
            }
        }
    }

    // Add TTS speaking indicator
    @Composable
    fun TtsSpeakingIndicator(modifier: Modifier = Modifier) {
        Card(
            modifier = modifier
                .padding(16.dp)
                .fillMaxWidth(0.8f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Lisa is speaking...",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyMedium
                )
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
                    if (partialText.value.isNotEmpty() && !isTtsSpeaking.value) {
                        emailViewModel.processUserInput(this@VoiceInputActivity, partialText.value)
                        partialText.value = ""
                    }
                },
                onTextChange = { partialText.value = it },
                isListening = isListening.value && !isTtsSpeaking.value // Modify listening state
            )
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

        LaunchedEffect(currentDraft?.subject, currentDraft?.to, currentDraft?.body) {
            if (currentDraft != null) {
                Log.d("VoiceInputActivity", "🔄 Draft data changed:")
                Log.d("VoiceInputActivity", "   Draft ID: '${currentDraft.draft_id}'")
                Log.d("VoiceInputActivity", "   Subject: '${currentDraft.subject}'")
                Log.d("VoiceInputActivity", "   To: '${currentDraft.to}'")
                Log.d("VoiceInputActivity", "   Body preview: '${currentDraft.body?.take(50)}...'")
                Log.d("VoiceInputActivity", "   Edit Summary: '${currentDraft.edit_summary}'")
            }
        }

        key(currentDraft?.draft_id, currentDraft?.subject, currentDraft?.to, currentDraft?.body) {
            LaunchedEffect(finalText.value, isTtsSpeaking.value) {
                val text = finalText.value.trim()
                if (text.isNotEmpty() && currentDraft != null && !isTtsSpeaking.value) {
                    Log.d("VoiceInputActivity", "Processing voice command in composer: '$text'")

                    // Process the command through ViewModel
                    emailViewModel.processUserInput(context, text)
                    finalText.value = ""
                }
            }

            // Force UI refresh after state change (e.g., after edit)
            LaunchedEffect(uiState.currentDraft) {
                if (uiState.currentDraft != null) {
                    // Optional: Add animation or refresh logic if needed
                    Log.d("VoiceInputActivity", "📱 UI refreshed with draft: ${uiState.currentDraft?.draft_id}")
                    Log.d("VoiceInputActivity", "📱 Current subject in UI: '${uiState.currentDraft?.subject}'")
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
                            key(currentDraft.draft_id, currentDraft.subject, currentDraft.to) {
                                EmailContent(
                                    draft = currentDraft,
                                    isLoading = uiState.isLoading,
                                    modifier = Modifier.weight(1f)
                                )
                            }


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
                        containerColor = Color.White
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
                            .padding(12.dp),
                        contentAlignment = Alignment.Center // ✅ Add contentAlignment here
                    ) {
                        if (isLoading && draft.body.isNullOrBlank()) {
                            // Show loading in body area - Remove the inner Box
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center // ✅ Add vertical centering
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
        LaunchedEffect(value) {
            Log.d("EmailDisplayField", "📝 $label field received value: '$value'")
        }

        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            key(value) {
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