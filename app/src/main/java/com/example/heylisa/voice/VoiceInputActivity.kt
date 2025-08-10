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
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.heylisa.constant.Noisy
import com.example.heylisa.viewmodel.EmailViewModel
import com.example.heylisa.viewmodel.ChatViewModel
import com.example.heylisa.model.ChatMessage
import com.example.heylisa.model.ChatState
import com.example.heylisa.model.DeliveryStatus
import com.example.heylisa.model.MessageType
import com.example.heylisa.request.DraftResponse
import com.example.heylisa.service.CustomTtsService
import com.example.heylisa.util.VoskWakeWordService

class VoiceInputActivity : ComponentActivity() {

    private val isListening = mutableStateOf(false)
    private val isTtsSpeaking = mutableStateOf(false)

    // ViewModels - initialize later
    private lateinit var chatViewModel: ChatViewModel
    private lateinit var emailViewModel: EmailViewModel

    private val partialReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.heylisa.PARTIAL_TEXT" -> {
                    val text = intent.getStringExtra("text") ?: return
                    if (text !in Noisy.noisyWords && text.isNotBlank()) {
                        if (::chatViewModel.isInitialized) {
                            chatViewModel.updateCurrentInput(text)
                        }
                        Log.d("VoiceInputActivity", "Partial: $text")
                    }
                }

                "com.example.heylisa.RECOGNIZED_TEXT" -> {
                    val final = intent.getStringExtra("result") ?: return
                    Log.d("VoiceInputActivity", "Final text received: $final")

                    // ✅ Add extra filtering here too
                    if (final !in Noisy.noisyWords &&
                        final.isNotBlank() &&
                        !final.lowercase().contains("no speech") &&
                        !final.lowercase().contains("timeout")) {

                        if (::chatViewModel.isInitialized && ::emailViewModel.isInitialized) {
                            chatViewModel.addUserMessage(final.trim())
                            emailViewModel.processUserInput(this@VoiceInputActivity, final.trim())
                        }
                    }
                }

                "com.example.heylisa.CLEAR_TEXT" -> {
                    if (::chatViewModel.isInitialized) {
                        chatViewModel.updateCurrentInput("")
                    }
                }

                "com.example.heylisa.SPEECH_START_ERROR" -> {
                    val error = intent.getStringExtra("error") ?: "Unknown error"
                    Log.e("VoiceInputActivity", "Speech start error: $error")
                    if (::chatViewModel.isInitialized) {
                        chatViewModel.addAssistantMessage("❌ Sorry, I couldn't start listening: $error")
                    }
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
                            if (::chatViewModel.isInitialized) {
                                chatViewModel.showChatDialog()
                                // Wake word detected, but not yet in speech recognition
                                chatViewModel.setWakeWordListening(false)
                            }
                        }
                        "speech_recognition_started", "whisper_recording_started" -> {
                            // ✅ This is actual speech recognition - show "Listening"
                            isListening.value = true
                            if (::chatViewModel.isInitialized) {
                                chatViewModel.setSpeechRecognizing(true)
                            }
                            Log.d("VoiceInputActivity", "Speech recognition started")
                        }
                        "wake_word_listening" -> {
                            // ✅ Back to wake word detection - show "Say Hey Lisa"
                            isListening.value = false
                            if (::chatViewModel.isInitialized) {
                                chatViewModel.setWakeWordListening(true)
                            }
                            Log.d("VoiceInputActivity", "Back to wake word listening")
                        }
                    }
                }

                CustomTtsService.TTS_STARTED -> {
                    isTtsSpeaking.value = true
                    Log.d("VoiceInputActivity", "TTS started speaking")
                }

                CustomTtsService.TTS_FINISHED, CustomTtsService.TTS_ERROR -> {
                    isTtsSpeaking.value = false
                    Log.d("VoiceInputActivity", "TTS finished speaking")
                }

                "com.example.heylisa.PROCESSING_STARTED" -> {
                    if (::chatViewModel.isInitialized) {
                        chatViewModel.setProcessing(true)
                    }
                }

                "com.example.heylisa.PROCESSING_COMPLETE" -> {
                    if (::chatViewModel.isInitialized) {
                        chatViewModel.setProcessing(false)
                    }
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

    @Composable
    fun VoiceInputScreen() {
        val context = LocalContext.current

        // Initialize ViewModels in composable
        emailViewModel = viewModel<EmailViewModel>(
            factory = EmailViewModelFactory(context.applicationContext)
        )
        chatViewModel = viewModel<ChatViewModel>()

        val chatState by chatViewModel.chatState.collectAsState()
        val uiState by emailViewModel.uiState.collectAsState()

        // ✅ Track current processing message ID
        var currentProcessingMessageId by remember { mutableStateOf<String?>(null) }

        // Handle wake word launch or manual trigger
        LaunchedEffect(Unit) {
            val isFromWakeWord = intent?.getBooleanExtra("launched_from_wake_word", false) ?: false
            val isManualTrigger = intent?.getBooleanExtra("manual_trigger", false) ?: false

            if (isFromWakeWord || isManualTrigger) {
                chatViewModel.showChatDialog()
            }
        }

        // Handle email processing results
        LaunchedEffect(uiState.navigationEvent) {
            uiState.navigationEvent?.let { event ->
                when (event) {
                    // In your email processing results handler
                    is EmailViewModel.NavigationEvent.ShowSuccess -> {
                        // Update delivery status
                        currentProcessingMessageId?.let { messageId ->
                            chatViewModel.updateMessageDeliveryStatus(messageId, DeliveryStatus.DELIVERED)
                        }

                        // ✅ Enhanced success message handling
                        val successMessage = when {
                            event.message.contains("sent", ignoreCase = true) -> "✅ Email sent successfully!"
                            event.message.contains("draft", ignoreCase = true) -> {
                                // Handle draft creation
                                uiState.currentDraft?.let { draft ->
                                    val emailContent = formatEmailForChat(draft)
                                    chatViewModel.addEmailDraftMessage(emailContent)
                                }
                                return@let // Don't add additional message for drafts
                            }
                            event.message.contains("delivered", ignoreCase = true) -> "✅ Email delivered successfully!"
                            else -> "✅ ${event.message}"
                        }

                        chatViewModel.addAssistantMessage(successMessage)
                        currentProcessingMessageId = null
                    }

                    is EmailViewModel.NavigationEvent.ShowError -> {
                        // ✅ Update message delivery status to FAILED
                        currentProcessingMessageId?.let { messageId ->
                            chatViewModel.updateMessageDeliveryStatus(messageId, DeliveryStatus.FAILED)
                        }

                        // Check if it's an email sending error
                        if (event.message.contains("email", ignoreCase = true) ||
                            event.message.contains("send", ignoreCase = true)) {
                            chatViewModel.addAssistantMessage("❌ Failed to send email: ${event.message}")
                        } else {
                            chatViewModel.addAssistantMessage("❌ ${event.message}")
                        }

                        currentProcessingMessageId = null
                    }
                    else -> {
                        // Handle other navigation events
                    }
                }
                emailViewModel.onNavigationHandled()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // ✅ Updated ChatDialog call with new parameters
            ChatDialog(
                chatViewModel = chatViewModel,
                emailViewModel = emailViewModel,
                currentProcessingMessageId = currentProcessingMessageId,
                onMessageSent = { messageId ->
                    currentProcessingMessageId = messageId
                },
                onDismiss = {
                    chatViewModel.hideChatDialog(context)
                    finish()
                }
            )
        }
    }

    private fun formatEmailForChat(draft: DraftResponse): String {
        return buildString {
            appendLine("Email Draft Created")
            appendLine()
            appendLine("To: ${draft.to ?: "Not specified"}")
            appendLine()
            appendLine("Subject: ${draft.subject ?: "No subject"}")
            appendLine()
            appendLine("Message:")
            appendLine(draft.body ?: "No content")
        }
    }

    @Composable
    fun ChatDialog(
        chatViewModel: ChatViewModel,
        emailViewModel: EmailViewModel,
        currentProcessingMessageId: String?,
        onMessageSent: (String) -> Unit,
        onDismiss: () -> Unit
    ) {
        val chatState by chatViewModel.chatState.collectAsState()

        AnimatedVisibility(
            visible = chatState.showDialog,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(300, easing = EaseOutCubic)
            ) + fadeIn(animationSpec = tween(300)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(250, easing = EaseInCubic)
            ) + fadeOut(animationSpec = tween(250))
        ) {
            Dialog(
                onDismissRequest = onDismiss,
                properties = DialogProperties(
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false
                )
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp
                ) {
                    ChatContent(
                        chatState = chatState,
                        chatViewModel = chatViewModel,
                        emailViewModel = emailViewModel,
                        currentProcessingMessageId = currentProcessingMessageId,
                        onMessageSent = onMessageSent,
                        onClose = onDismiss
                    )
                }
            }
        }
    }

    @Composable
    fun ChatContent(
        chatState: ChatState,
        chatViewModel: ChatViewModel,
        emailViewModel: EmailViewModel,
        currentProcessingMessageId: String?,
        onMessageSent: (String) -> Unit,
        onClose: () -> Unit
    ) {
        val context = LocalContext.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header with close button
            ChatHeader(
                onClose = onClose,
                statusText = chatViewModel.getStatusText(),
                isListening = chatState.isListening,
                isProcessing = chatState.isProcessing
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Chat messages (scrollable)
            ChatMessages(
                messages = chatState.messages,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Chat voice input bar
            ChatVoiceInputBar(
                currentInput = chatState.currentInput,
                isListening = chatState.isListening,
                isProcessing = chatState.isProcessing,
                onInputChange = chatViewModel::updateCurrentInput,
                onMicClick = {
                    if (!chatState.isListening && !chatState.isProcessing) {
                        val intent = Intent(VoskWakeWordService.ACTION_START_SPEECH_RECOGNITION)
                        context.sendBroadcast(intent)
                    }
                },
                onSendClick = {
                    if (chatState.currentInput.isNotEmpty()) {
                        // ✅ Track the message ID for delivery status
                        val messageId = chatViewModel.addUserMessage(chatState.currentInput)
                        messageId?.let { onMessageSent(it) }
                        emailViewModel.processUserInput(context, chatState.currentInput)
                    }
                }
            )
        }
    }

    @Composable
    fun ChatHeader(
        onClose: () -> Unit,
        statusText: String,
        isListening: Boolean,
        isProcessing: Boolean
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Hey Lisa",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when {
                        isProcessing -> {
                            val infiniteTransition = rememberInfiniteTransition(label = "processing_pulse")
                            val alpha by infiniteTransition.animateFloat(
                                initialValue = 0.3f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "pulse"
                            )
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        Color(0xFFFF9800).copy(alpha = alpha),
                                        CircleShape
                                    )
                            )
                        }
                        isListening -> {
                            val infiniteTransition = rememberInfiniteTransition(label = "status_pulse")
                            val alpha by infiniteTransition.animateFloat(
                                initialValue = 0.3f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1000),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "pulse"
                            )
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        Color.Red.copy(alpha = alpha),
                                        CircleShape
                                    )
                            )
                        }
                        statusText.contains("Hey Lisa", ignoreCase = true) -> {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        Color.Blue,
                                        CircleShape
                                    )
                            )
                        }
                        else -> {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        Color.Green,
                                        CircleShape
                                    )
                            )
                        }
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    @Composable
    fun ChatMessages(
        messages: List<ChatMessage>,
        modifier: Modifier = Modifier
    ) {
        val listState = rememberLazyListState()

        // Auto-scroll to bottom when new messages arrive
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        LazyColumn(
            modifier = modifier,
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages) { message ->
                ChatMessageBubble(message = message)
            }
        }
    }

    @Composable
    fun ChatMessageBubble(message: ChatMessage) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (message.isFromUser) {
                Arrangement.End
            } else {
                Arrangement.Start
            }
        ) {
            if (message.isFromUser) {
                Spacer(modifier = Modifier.width(48.dp))
            }

            Surface(
                modifier = Modifier.widthIn(max = if (message.messageType == MessageType.EMAIL_DRAFT) 320.dp else 280.dp),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (message.isFromUser) 16.dp else 4.dp,
                    bottomEnd = if (message.isFromUser) 4.dp else 16.dp
                ),
                color = when {
                    message.isFromUser -> Color(0xFF6A78C2)
                    message.messageType == MessageType.EMAIL_DRAFT -> Color(0xFFE8F4FD)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            message.isFromUser -> Color.White
                            message.messageType == MessageType.EMAIL_DRAFT -> Color(0xFF1976D2)
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )

                    // ✅ Bottom row with time and delivery status
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = message.getFormattedTime(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (message.isFromUser) {
                                Color.White.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            }
                        )

                        // ✅ Add delivery ticks for user messages only
                        if (message.isFromUser) {
                            Spacer(modifier = Modifier.width(4.dp))
                            DeliveryTicks(
                                status = message.deliveryStatus,
                                modifier = Modifier
                            )
                        }
                    }
                }
            }

            if (!message.isFromUser) {
                Spacer(modifier = Modifier.width(48.dp))
            }
        }
    }

    // Add this to your VoiceInputActivity.kt

    @Composable
    fun DeliveryTicks(
        status: DeliveryStatus,
        modifier: Modifier = Modifier
    ) {
        when (status) {
            DeliveryStatus.SENT -> {
                // Single tick
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_menu_agenda), // You can use a better tick icon
                    contentDescription = "Sent",
                    modifier = modifier.size(12.dp),
                    tint = Color.White.copy(alpha = 0.7f)
                )
            }
            DeliveryStatus.DELIVERED -> {
                // Double tick
                Row(
                    horizontalArrangement = Arrangement.spacedBy((-4).dp)
                ) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_menu_agenda),
                        contentDescription = "Delivered",
                        modifier = modifier.size(12.dp),
                        tint = Color.Blue.copy(alpha = 0.8f) // Blue ticks for delivered
                    )
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_menu_agenda),
                        contentDescription = "Delivered",
                        modifier = modifier.size(12.dp),
                        tint = Color.Blue.copy(alpha = 0.8f)
                    )
                }
            }
            DeliveryStatus.FAILED -> {
                // Red exclamation for failed
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_dialog_alert),
                    contentDescription = "Failed",
                    modifier = modifier.size(12.dp),
                    tint = Color.Red.copy(alpha = 0.8f)
                )
            }
            DeliveryStatus.NONE -> {
                // No indicator for assistant messages
            }
        }
    }

    @Composable
    fun ChatVoiceInputBar(
        currentInput: String,
        isListening: Boolean,
        isProcessing: Boolean,
        onInputChange: (String) -> Unit,
        onMicClick: () -> Unit,
        onSendClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Input field
                OutlinedTextField(
                    value = currentInput,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = when {
                                isListening -> "Listening..."
                                isProcessing -> "Processing..."
                                else -> "Type or speak..."
                            },
                            color = Color.Black.copy(0.5f)
                        )
                    },
                    enabled = !isListening && !isProcessing,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6A78C2),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    maxLines = 3
                )

                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = when {
                        isListening -> Color.Red.copy(alpha = 0.1f)
                        isProcessing -> Color(0xFFFF9800).copy(alpha = 0.1f) // ✅ Orange for processing
                        else -> Color(0xFF6A78C2).copy(alpha = 0.1f)
                    }
                ) {
                    IconButton(
                        onClick = onMicClick,
                        enabled = !isProcessing
                    ) {
                        when {
                            isListening -> {
                                // Pulsing animation for listening
                                val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
                                val scale by infiniteTransition.animateFloat(
                                    initialValue = 1f,
                                    targetValue = 1.2f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(600),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "pulse"
                                )
                                Icon(
                                    painter = painterResource(id = android.R.drawable.ic_btn_speak_now),
                                    contentDescription = "Listening",
                                    tint = Color.Red,
                                    modifier = Modifier.scale(scale)
                                )
                            }
                            isProcessing -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFFFF9800) // ✅ Orange for processing
                                )
                            }
                            else -> {
                                Icon(
                                    painter = painterResource(id = android.R.drawable.ic_btn_speak_now),
                                    contentDescription = "Start speaking",
                                    tint = Color(0xFF6A78C2)
                                )
                            }
                        }
                    }
                }


                // Send button
                if (currentInput.isNotEmpty() && !isListening && !isProcessing) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = Color(0xFF6A78C2)
                    ) {
                        IconButton(onClick = onSendClick) {
                            Icon(
                                painter = painterResource(id = android.R.drawable.ic_menu_send),
                                contentDescription = "Send",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
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
            addAction("com.example.heylisa.PROCESSING_STARTED")
            addAction("com.example.heylisa.PROCESSING_COMPLETE")
            addAction(CustomTtsService.TTS_STARTED)
            addAction(CustomTtsService.TTS_FINISHED)
            addAction(CustomTtsService.TTS_ERROR)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(partialReceiver, partialFilter, RECEIVER_EXPORTED)
            registerReceiver(stateReceiver, stateFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(partialReceiver, partialFilter)
            registerReceiver(stateReceiver, stateFilter)
        }
    }

    private fun setupWindow() {
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
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

        try {
            sendBroadcast(Intent("com.example.heylisa.RESTORE_WAKE_WORD"))
        } catch (e: Exception) {
            Log.w("VoiceInputActivity", "Failed to send restore signal: ${e.message}")
        }
    }
}