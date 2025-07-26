package com.example.heylisa.main

import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.RECORD_AUDIO
import android.app.DownloadManager
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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsControllerCompat
import com.example.heylisa.auth.App
import com.example.heylisa.ui.theme.HeyLisaTheme
import com.example.heylisa.util.*
import java.io.File
import com.google.android.gms.auth.api.signin.GoogleSignInClient

class MainActivity : ComponentActivity() {

    private var modelDownloadId: Long = -1L
    private var isReceiverRegistered = false

    private var isAudioPermissionGranted = false
    private var isNotificationPermissionGranted = false

    private var showDialog by mutableStateOf(false)
    private var progressState by mutableFloatStateOf(0f)
    private var isUnzipping by mutableStateOf(false)
    private var showConfirmationDialog by mutableStateOf(false)
    private var isLoggedIn by mutableStateOf(false) // Track login state

    private lateinit var googleSignInClient: GoogleSignInClient // Add GoogleSignInClient

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isAudioPermissionGranted = isGranted
        if (isGranted) checkNotificationPermission()
        else Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isNotificationPermissionGranted = isGranted
        if (!isGranted) Toast.makeText(this, "Notification permission is required.", Toast.LENGTH_LONG).show()
        if (isAudioPermissionGranted && isGranted) handleModelSetup()
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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

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
                        showDialog = showDialog,
                        progressState = progressState,
                        isUnzipping = isUnzipping,
                        showConfirmationDialog = showConfirmationDialog,
                        onDismissDialog = { showConfirmationDialog = false },
                        onStartDownload = { modelDownload(this) },
                        googleSignInClient = googleSignInClient,
                        onSignOut = { signOut() }
                    )
                }
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            checkAndRequestPermission()
        }, 500)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkAndRequestPermission() {
        if (checkSelfPermission(RECORD_AUDIO) != PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(RECORD_AUDIO)
        } else {
            isAudioPermissionGranted = true
            checkNotificationPermission()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkNotificationPermission() {
        if (checkSelfPermission(POST_NOTIFICATIONS) != PERMISSION_GRANTED) {
            requestNotificationPermissionLauncher.launch(POST_NOTIFICATIONS)
        } else {
            isNotificationPermissionGranted = true
            handleModelSetup()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun handleModelSetup() {
        val modelDir = File(filesDir, "vosk-model")
        val modelZip = File(getExternalFilesDir(null), "vosk-model-en-us-0.22.zip")

        when {
            modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true -> {}
            modelZip.exists() -> unzipAndTrack(modelZip)
            else -> showConfirmationDialog = true
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun modelDownload(context: Context) {
        val modelUrl = "https://github.com/SmitKarwade/VoskModel/releases/download/v1.0/vosk-model-en-us-0.22.zip"
        val fileName = "vosk-model-en-us-0.22.zip"
        val destination = File(context.getExternalFilesDir(null), fileName)

        if (!destination.exists()) {
            val request = DownloadManager.Request(modelUrl.toUri()).apply {
                setTitle("Downloading Vosk Model")
                setDescription("Please wait while we download the speech model.")
                setDestinationUri(Uri.fromFile(destination))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            }

            val dm = context.getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            modelDownloadId = dm.enqueue(request)

            showDialog = true
            progressState = 0f
            isUnzipping = false

            val handler = Handler(Looper.getMainLooper())
            handler.post(object : Runnable {
                override fun run() {
                    val query = DownloadManager.Query().setFilterById(modelDownloadId)
                    val cursor = dm.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val downloadedBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        if (totalBytes > 0) progressState = downloadedBytes.toFloat() / totalBytes

                        when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                            DownloadManager.STATUS_SUCCESSFUL -> handler.removeCallbacks(this)
                            DownloadManager.STATUS_FAILED -> {
                                Toast.makeText(context, "Download failed", Toast.LENGTH_LONG).show()
                                showDialog = false
                                handler.removeCallbacks(this)
                            }
                            else -> handler.postDelayed(this, 500)
                        }
                    }
                    cursor?.close()
                }
            })

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == modelDownloadId) {
                        isReceiverRegistered = false
                        Log.d("HeyLisa", "Model download complete, starting unzip...")
                        unzipAndTrack(destination)
                    }
                    try {
                        unregisterReceiver(this)
                    } catch (_: IllegalArgumentException) {}
                }
            }

            if (!isReceiverRegistered) {
                registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
                isReceiverRegistered = true
            }
        }
    }

    private fun unzipAndTrack(zipFile: File) {
        isUnzipping = true
        progressState = 0.01f

        unzipModel(
            context = this,
            zipFile = zipFile,
            onProgress = {
                showDialog = true
                progressState = it
            },
            onDone = {
                showDialog = false
                val prefs = getSharedPreferences("setup_state", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("setup_done", true).apply()

                requestAllDefaultAssistantRequirements()

                maybeStartServiceIfReady()
            }
        )
    }

    private fun allRequirementsMet(): Boolean {
        val roleManager = getSystemService(RoleManager::class.java)
        val isAssistant = roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        val canDraw = Settings.canDrawOverlays(this)
        val voiceService = Settings.Secure.getString(contentResolver, "voice_interaction_service")
        Log.d("HeyLisa", "Voice Service: $voiceService")
        val isVoiceService = voiceService?.contains("com.example.heylisa/.custom.LisaVoiceInteractionService") == true
        Log.d("HeyLisa", "Assistant: $isAssistant, Can Draw: $canDraw, Logged in: $isLoggedIn")
        return isAssistant && canDraw && isLoggedIn
    }

    private fun startWakeWordService() {
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
            startWakeWordService()
        } else {
            Log.d("Setup", "Lisa is not ready yet. Complete all setup steps.")
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
}

@Composable
fun MainScreen(
    context: Context,
    showDialog: Boolean,
    progressState: Float,
    isUnzipping: Boolean,
    showConfirmationDialog: Boolean,
    onDismissDialog: () -> Unit,
    onStartDownload: () -> Unit,
    googleSignInClient: GoogleSignInClient,
    onSignOut: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        var currentScreen by remember { mutableStateOf("main") }
        val context = LocalContext.current

        when (currentScreen) {
            "main" -> {
                TransparentScaffoldWithToolbar(
                    context = context,
                    googleSignInClient = googleSignInClient,
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

        ModelDownloadDialog(
            show = showDialog,
            progress = progressState,
            isUnzipping = isUnzipping
        )

        if (showConfirmationDialog) {
            AlertDialog(
                onDismissRequest = onDismissDialog,
                title = { Text("Download Required") },
                text = { Text("The Vosk model (~1.8 GB) is required to enable offline voice assistant features. Do you want to download it now?") },
                confirmButton = {
                    TextButton(onClick = {
                        onDismissDialog()
                        onStartDownload()
                    }) {
                        Text("Yes")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        onDismissDialog()
                        Toast.makeText(context, "Model download is required to proceed.", Toast.LENGTH_LONG).show()
                    }) {
                        Text("No")
                    }
                }
            )
        }

//        WakeWordServiceControl(
//            context = context,
//            modifier = Modifier
//                .align(Alignment.BottomCenter)
//                .padding(24.dp)
//        )
    }
}