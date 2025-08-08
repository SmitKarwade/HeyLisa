package com.example.heylisa.main

import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.RECORD_AUDIO
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.*
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import com.example.heylisa.auth.getGoogleSignInClient
import com.example.heylisa.util.VoskWakeWordService
import android.os.*
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import com.example.heylisa.auth.App
import com.example.heylisa.ui.theme.HeyLisaTheme
import com.example.heylisa.util.*
import com.example.heylisa.voice.VoiceInputActivity
import java.io.File
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

class MainActivity : ComponentActivity() {

    private var isAudioPermissionGranted = false
    private var isNotificationPermissionGranted = false

    private var isLoggedIn by mutableStateOf(false) // Track login state
    private lateinit var googleSignInClient: GoogleSignInClient // Add GoogleSignInClient
    private var isModelInitializing by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isAudioPermissionGranted = isGranted
        if (isGranted) checkNotificationPermission()
        else Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isNotificationPermissionGranted = isGranted
        if (!isGranted) Toast.makeText(this, "Notification permission is required.", Toast.LENGTH_LONG).show()
        if (isAudioPermissionGranted && isGranted) handleSetup()
    }

    private val assistantRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Assistant role granted.", Toast.LENGTH_LONG).show()
        } else {
            val intent = Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
            startActivity(intent)
        }
    }

    // Model initialization receiver - keeps the loading animation
    private val modelInitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.heylisa.MODEL_INIT_STARTED" -> {
                    Log.d("MainActivity", "📦 Model initialization started")
                    isModelInitializing = true
                }
                "com.example.heylisa.MODEL_INIT_FINISHED" -> {
                    Log.d("MainActivity", "✅ Model initialization finished")
                    isModelInitializing = false
                }
                "com.example.heylisa.MODEL_INIT_FAILED" -> {
                    Log.d("MainActivity", "❌ Model initialization failed")
                    isModelInitializing = false
                    Toast.makeText(this@MainActivity, "Model initialization failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true

        FirebaseApp.initializeApp(this);
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = true;

        // Register model initialization receiver
        val filter = IntentFilter().apply {
            addAction("com.example.heylisa.MODEL_INIT_STARTED")
            addAction("com.example.heylisa.MODEL_INIT_FINISHED")
            addAction("com.example.heylisa.MODEL_INIT_FAILED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(modelInitReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(modelInitReceiver, filter)
        }

        val email = intent.getStringExtra("email")
        if (email != null) {
            Log.d("Token", "Received from login: Email = $email")
            isLoggedIn = true
        }

        googleSignInClient = getGoogleSignInClient(this)

        setContent {
            HeyLisaTheme {
                if (!isLoggedIn) {
                    App() // Show login screen if not logged in
                } else {
                    MainScreen(
                        context = this,
                        googleSignInClient = googleSignInClient,
                        onSignOut = { signOut() },
                        isModelInitializing = isModelInitializing
                    )
                }
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            checkAndRequestPermission()
        }, 500)
    }

    private fun checkAndRequestPermission() {
        if (checkSelfPermission(RECORD_AUDIO) != PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(RECORD_AUDIO)
        } else {
            isAudioPermissionGranted = true
            checkNotificationPermission()
        }
    }

    private fun checkNotificationPermission() {
        // Only request notification permission on Android 13+ (API 33)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(POST_NOTIFICATIONS) != PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(POST_NOTIFICATIONS)
            } else {
                isNotificationPermissionGranted = true
                handleSetup()
            }
        } else {
            // Android 11-12 don't need runtime notification permission
            isNotificationPermissionGranted = true
            handleSetup()
        }
    }

    // Simplified setup - no model download/unzip needed
    private fun handleSetup() {
        Log.d("MainActivity", "🚀 Handling setup - requesting permissions")
        requestAllDefaultAssistantRequirements()
        maybeStartServiceIfReady()
    }

    private fun allRequirementsMet(): Boolean {
        val roleManager = getSystemService(RoleManager::class.java)
        val isAssistant = roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        val canDraw = Settings.canDrawOverlays(this)
        val voiceService = Settings.Secure.getString(contentResolver, "voice_interaction_service")
        Log.d("HeyLisa", "Voice Service: $voiceService")
        val isVoiceService = voiceService?.contains("com.example.heylisa/.custom.LisaVoiceInteractionService") == true
        Log.d("HeyLisa", "Assistant: $isAssistant, Can Draw: $canDraw, Logged in: $isLoggedIn")
        return isAssistant && canDraw && isLoggedIn && isAudioPermissionGranted && isNotificationPermissionGranted
    }

    private fun startWakeWordService() {
        Log.d("MainActivity", "🎤 Starting VoskWakeWordService")
        val serviceIntent = Intent(this, VoskWakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun isVoiceInteractionServiceSet(): Boolean {
        val currentService = Settings.Secure.getString(contentResolver, "voice_interaction_service")
        return currentService?.contains("com.example.heylisa/.custom.LisaVoiceInteractionService") == true
    }

    private fun maybeStartServiceIfReady() {
        if (allRequirementsMet()) {
            Log.d("MainActivity", "✅ All requirements met - starting wake word service")
            startWakeWordService()
        } else {
            Log.d("Setup", "⚠️ Lisa is not ready yet. Complete all setup steps.")
        }
    }

    private fun requestAllDefaultAssistantRequirements() {
        val shared = getSharedPreferences("setup_state", Context.MODE_PRIVATE)

        // 1. Request Assistant Role
        val roleManager = getSystemService(RoleManager::class.java)
        if (!roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            assistantRoleLauncher.launch(intent)
            return // Wait for result before continuing
        }

        // 2. Request SYSTEM_ALERT_WINDOW
        if (!Settings.canDrawOverlays(this)) {
            val overlayIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(overlayIntent)
            Toast.makeText(this, "Please enable 'Draw over other apps'", Toast.LENGTH_LONG).show()
            return
        }

        // 3. Prompt for Voice Interaction Service if not already done
        val alreadyPrompted = shared.getBoolean("voice_interaction_prompted", false)
        if (!isVoiceInteractionServiceSet() && !alreadyPrompted) {
            Toast.makeText(
                this,
                "Please set Lisa as your default Voice Interaction Service",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            shared.edit().putBoolean("voice_interaction_prompted", true).apply()
            return
        }

        maybeStartServiceIfReady()
    }

    // Sign-out method
    private fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener {
            isLoggedIn = false // Reset login state
            Toast.makeText(this, "Signed out successfully", Toast.LENGTH_SHORT).show()
            // Navigate back to login screen by restarting activity with cleared state
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        maybeStartServiceIfReady()
    }

    fun launchVoiceInputActivity() {
        val intent = Intent(this, VoiceInputActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION

            putExtra("manual_trigger", true)
            putExtra("launched_from_swipe", true)
        }

        startActivity(intent)
        Log.d("MainActivity", "🚀 Launched VoiceInputActivity with chat dialog from swipe")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(modelInitReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("MainActivity", "Model init receiver not registered: ${e.message}")
        }
    }
}

@Composable
fun MainScreen(
    context: Context,
    googleSignInClient: GoogleSignInClient,
    onSignOut: () -> Unit,
    isModelInitializing: Boolean
) {
    var swipeProgress by remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = swipeProgress,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        swipeProgress = 0f
                        Log.d("MainScreen", "🔍 Swipe started")
                    },
                    onDragEnd = {
                        if (swipeProgress >= 0.1f) {
                            Log.d("MainScreen", "🚀 Swipe threshold reached (${(swipeProgress * 100).toInt()}%) - launching chat dialog")
                            (context as? MainActivity)?.launchVoiceInputActivity()
                        } else {
                            Log.d("MainScreen", "❌ Swipe not enough (${(swipeProgress * 100).toInt()}%)")
                        }
                        swipeProgress = 0f
                    }
                ) { _, dragAmount ->
                    if (dragAmount.y < 0) { // Upward drag
                        val newProgress = swipeProgress + (-dragAmount.y / size.height) * 2f
                        swipeProgress = minOf(1f, newProgress)

                        if (swipeProgress >= 0.05f && swipeProgress - (-dragAmount.y / size.height) * 2f < 0.05f) {
                            // First time crossing threshold - you can add haptic feedback here
                            Log.d("MainScreen", "✨ Swipe threshold reached!")
                        }
                    }
                }
            }
    ) {
        var currentScreen by remember { mutableStateOf("main") }
        val context = LocalContext.current

        when (currentScreen) {
            "main" -> {
                TransparentScaffoldWithToolbar(
                    context = context,
                    googleSignInClient = googleSignInClient,
                    isModelInitializing = isModelInitializing,
                    onSignOut = onSignOut,
                    onNavigateToSettings = { currentScreen = "settings" }
                )
            }
            "settings" -> {
                SettingsScreen(
                    onNavigateBack = { currentScreen = "main" }
                )
            }
        }

        if (animatedProgress > 0f) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                // Swipe indicator text
                if (animatedProgress >= 0.03f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Blue.copy(alpha = 0.1f)
                        ) {
                            Text(
                                text = if (animatedProgress >= 0.05f) "Release to open Hey Lisa" else "Swipe up to open Hey Lisa",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = Color.Black,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // Progress bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (animatedProgress >= 0.05f) 6.dp else 4.dp)
                        .background(
                            if (animatedProgress >= 0.05f) Color.Blue.copy(alpha = 0.3f) else Color.Blue.copy(alpha = animatedProgress),
                            RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                        )
                )
            }
        }

        if (isModelInitializing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PulsingLoadingDots()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Initializing Hey Lisa...",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}