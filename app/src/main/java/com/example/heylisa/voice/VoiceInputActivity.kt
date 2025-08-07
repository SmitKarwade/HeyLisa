package com.example.heylisa.voice

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.heylisa.constant.Noisy
import com.example.heylisa.viewmodel.EmailViewModel
import com.example.heylisa.request.DraftResponse
import com.example.heylisa.service.CustomTtsService
import com.example.heylisa.util.VoskWakeWordService

class VoiceInputActivity : ComponentActivity() {

    private val isListening = mutableStateOf(false)
    private val partialText = mutableStateOf("")
    private val finalText = mutableStateOf("")
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

                "com.example.heylisa.SPEECH_START_ERROR" -> {
                    val error = intent.getStringExtra("error") ?: "Unknown error"
                    Log.e("VoiceInputActivity", "Speech start error: $error")
                    showToast("Failed to start listening: $error")
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
                            // Don't set isListening here yet - wait for speech_recognition_started
                        }
                        "speech_recognition_started" -> {
                            isListening.value = true
                            Log.d("VoiceInputActivity", "Speech recognition started - animation should begin")
                        }
                        "wake_word_listening" -> {
                            isListening.value = false
                            Log.d("VoiceInputActivity", "Back to wake word listening - animation should stop")
                        }
                    }
                }

                CustomTtsService.TTS_STARTED -> {
                    isTtsSpeaking.value = true
                    Log.d("VoiceInputActivity", "Custom TTS started speaking")
                }
                CustomTtsService.TTS_FINISHED, CustomTtsService.TTS_ERROR -> {
                    isTtsSpeaking.value = false
                    Log.d("VoiceInputActivity", "Custom TTS finished speaking")
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

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerBroadcastReceivers() {
        val partialFilter = IntentFilter().apply {
            addAction("com.example.heylisa.PARTIAL_TEXT")
            addAction("com.example.heylisa.CLEAR_TEXT")
            addAction("com.example.heylisa.RECOGNIZED_TEXT")
            addAction("com.example.heylisa.SPEECH_START_ERROR")
        }

        val stateFilter = IntentFilter().apply {
            addAction("com.example.heylisa.STATE_UPDATE")
            addAction("com.example.heylisa.TTS_STARTED")
            addAction("com.example.heylisa.TTS_FINISHED")
            addAction("com.example.heylisa.TTS_ERROR")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(partialReceiver, partialFilter, RECEIVER_EXPORTED)
            registerReceiver(stateReceiver, stateFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(partialReceiver, partialFilter)
            registerReceiver(stateReceiver, stateFilter)
        }
    }

    @Composable
    fun VoiceInputScreen() {
        val context = LocalContext.current
        val emailViewModel: EmailViewModel = viewModel(
            factory = EmailViewModelFactory(context.applicationContext)
        )

        val uiState by emailViewModel.uiState.collectAsState()
        val showEmailCompose = uiState.isDraftCreated && uiState.currentDraft != null

        LaunchedEffect(uiState.navigationEvent) {
            uiState.navigationEvent?.let { event ->
                handleNavigationEvent(event) {}
                emailViewModel.onNavigationHandled()
            }
        }

        LaunchedEffect(finalText.value, isTtsSpeaking.value) {
            val text = finalText.value.trim()
            if (text.isNotEmpty() && !isTtsSpeaking.value) {
                Log.d("VoiceInputActivity", "Processing voice input: '$text'")
                emailViewModel.processUserInput(this@VoiceInputActivity, text)
                finalText.value = ""
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding() // ✅ Handle keyboard at root level
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // ✅ MAIN CONTENT AREA
                if (showEmailCompose) {
                    EmailComposePopup(
                        emailViewModel = emailViewModel,
                        onDismiss = { emailViewModel.clearDraft() },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clickable { finish() }
                    )
                }

                // ✅ BOTTOM VOICE INPUT BAR - Always visible
                VoiceInputBar(emailViewModel = emailViewModel)
            }

            // Loading overlay when processing (not composing)
            if (uiState.isLoading && !showEmailCompose) {
                LoadingOverlay()
            }
        }
    }


    @Composable
    fun EmailComposePopup(
        emailViewModel: EmailViewModel,
        onDismiss: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val context = LocalContext.current
        val uiState by emailViewModel.uiState.collectAsState()
        val currentDraft = uiState.currentDraft

        // Handle voice input in compose mode
        LaunchedEffect(finalText.value, isTtsSpeaking.value) {
            val text = finalText.value.trim()
            if (text.isNotEmpty() && currentDraft != null && !isTtsSpeaking.value) {
                Log.d("VoiceInputActivity", "Processing voice command in composer: '$text'")
                emailViewModel.processUserInput(context, text)
                finalText.value = ""
            }
        }

        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            shadowElevation = 8.dp,
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxSize()
            ) {
                // Header
                ComposeHeader(onDismiss = onDismiss)
                Spacer(modifier = Modifier.height(16.dp))

                // ✅ SCROLLABLE CONTENT AREA - Takes remaining space
                Box(
                    modifier = Modifier.weight(1f) // ✅ Takes all available space above buttons
                ) {
                    if (currentDraft != null) {
                        EmailContent(
                            draft = currentDraft,
                            isLoading = uiState.isLoading,
                            modifier = Modifier.fillMaxSize() // ✅ Fill the available space
                        )
                    } else {
                        // Loading state
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
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

                // ✅ BUTTONS ALWAYS AT BOTTOM - No weight, fixed position
                Spacer(modifier = Modifier.height(16.dp))
                if (currentDraft != null) {
                    ActionButtons(
                        currentDraft = currentDraft,
                        isLoading = uiState.isLoading,
                        onDismiss = onDismiss,
                        onSend = { draftId -> emailViewModel.sendEmail(context, draftId) }
                    )
                }
            }
        }
    }


    @Composable
    fun VoiceInputBar(emailViewModel: EmailViewModel) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = Color.Transparent
        ) {
            Box(
                modifier = Modifier.padding(bottom = 16.dp, start = 8.dp, end = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                HeyLisaBar(
                    text = partialText,
                    onMicClick = { safeStartSpeechRecognition() },
                    onSendClick = {
                        if (partialText.value.isNotEmpty() && !isTtsSpeaking.value) {
                            emailViewModel.processUserInput(this@VoiceInputActivity, partialText.value)
                            partialText.value = ""
                        }
                    },
                    onTextChange = { partialText.value = it },
                    isListening = isListening.value && !isTtsSpeaking.value
                )
            }
        }
    }

    private fun safeStartSpeechRecognition() {
        if (!isListening.value && !isTtsSpeaking.value) {
            try {
                Log.d("VoiceInputActivity", "🎤 Requesting manual speech recognition")

                // Send intent to service to start speech recognition
                val intent = Intent(VoskWakeWordService.ACTION_START_SPEECH_RECOGNITION)
                sendBroadcast(intent)

            } catch (e: Exception) {
                Log.e("VoiceInputActivity", "Failed to request speech recognition: ${e.message}")
                showToast("Unable to start listening. Please try again.")
            }
        } else {
            Log.d("VoiceInputActivity", "🛑 Speech recognition conditions not met")
            if (isListening.value) {
                showToast("Already listening...")
            } else if (isTtsSpeaking.value) {
                showToast("Please wait for voice response to finish")
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }

    @Composable
    fun EmailContent(
        draft: DraftResponse,
        isLoading: Boolean,
        modifier: Modifier = Modifier
    ) {
        // ✅ Make the entire content scrollable within the available space
        //val scrollState = rememberScrollState()

        Column(
            modifier = modifier
//                .verticalScroll(scrollState) // ✅ Scrollable within allocated space
                .padding(vertical = 4.dp)
                .imePadding(), // ✅ Handle keyboard here instead
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EmailDisplayField(
                label = "To",
                value = draft.to ?: "",
                placeholder = "Recipient will appear here"
            )

            EmailDisplayField(
                label = "Subject",
                value = draft.subject ?: "",
                placeholder = "Subject will appear here"
            )

            // Body Field
            Column {
                Text(
                    text = "Body",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        if (isLoading && draft.body.isNullOrBlank()) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
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
                            val bodyScrollState = rememberScrollState()
                            Text(
                                text = draft.body?.takeIf { it.isNotBlank() }
                                    ?: "Email content will appear here...",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(bodyScrollState),
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
                    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
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
                Text("Cancel", color = Color(0xFF6A78C2))
            }
            Button(
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6A78C2),
                    contentColor = Color.White
                ),
                onClick = { currentDraft?.draft_id?.let(onSend) },
                enabled = currentDraft != null &&
                        !isLoading &&
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
                            color = Color(0xFF6A78C2)
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
        // Handle navigation events as needed
        when (event) {
            EmailViewModel.NavigationEvent.ToComposer -> {}
            EmailViewModel.NavigationEvent.ToInbox -> {}
            EmailViewModel.NavigationEvent.ToDrafts -> {}
            EmailViewModel.NavigationEvent.ToSent -> {}
            EmailViewModel.NavigationEvent.SendEmail -> {}
            EmailViewModel.NavigationEvent.ToChat -> {}
            is EmailViewModel.NavigationEvent.ShowError -> {}
            is EmailViewModel.NavigationEvent.ShowSuccess -> {}
        }
    }

    // SAFE WINDOW SETUP - NO OVERLAY FLAGS OR WINDOW TYPES
    private fun setupWindow() {
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true

        // Safe approach for showing over lockscreen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        // Only safe flags for regular activities
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.d("VoiceInputActivity", "Toast: $message")
    }

    override fun onStop() {
        super.onStop()
        partialText.value = ""
        finalText.value = ""
    }


    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(partialReceiver)
            unregisterReceiver(stateReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("VoiceInputActivity", "Receiver not registered: ${e.message}")
        }

        try {
            sendBroadcast(Intent("com.example.heylisa.RESTORE_WAKE_WORD"))
        } catch (e: Exception) {
            Log.w("VoiceInputActivity", "Failed to send restore signal: ${e.message}")
        }
    }
}