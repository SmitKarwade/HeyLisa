package com.example.heylisa.main

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.heylisa.R
import com.example.heylisa.util.VoskWakeWordService
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransparentScaffoldWithToolbar(
    context: Context,
    googleSignInClient: GoogleSignInClient,
    onSignOut: () -> Unit,
    onNavigateToSettings: () -> Unit = {} // Add navigation callback
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val gradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF4F60B6), Color(0xFF6A78C2), Color(0xFFEAE8F3)),
    )

    val cstFont = FontFamily(
        Font(R.font.poppins_regular)
    )
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // Function to restart Vosk service
    fun restartVoskService() {
        try {
            // Stop the service
            context.stopService(Intent(context, VoskWakeWordService::class.java))

            // Start the service again after a small delay
            val intent = Intent(context, VoskWakeWordService::class.java)
            ContextCompat.startForegroundService(context, intent)

            Toast.makeText(context, "Wake word service restarted", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Error restarting service: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(280.dp),
                drawerContainerColor = Color.White,
                drawerContentColor = Color.Black,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = statusBarPadding, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
                ) {
                    Text(
                        text = "Recents",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp),
                        fontFamily = cstFont
                    )
                    HorizontalDivider(
                        Modifier,
                        DividerDefaults.Thickness,
                        color = Color.Black.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Not available for now",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = Color.Black.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Menu",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp),
                        fontFamily = cstFont
                    )
                    HorizontalDivider(
                        Modifier.padding(vertical = 8.dp),
                        DividerDefaults.Thickness,
                        color = Color.Black.copy(alpha = 0.3f)
                    )

                    // Refresh option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    drawerState.close()
                                    restartVoskService()
                                }
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = Color.Black.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Refresh Service",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Black.copy(alpha = 0.8f)
                        )
                    }

                    // Settings option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    drawerState.close()
                                    onNavigateToSettings()
                                }
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.Black.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Black.copy(alpha = 0.8f)
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f)) // Push content to bottom
                    Button(
                        onClick = onSignOut,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black.copy(alpha = 0.7f)),
                        border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.3f))
                    ) {
                        Text("Sign Out", fontSize = 16.sp, fontFamily = cstFont)
                    }
                }
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(text = "") },
                        navigationIcon = {
                            IconButton(onClick = {
                                scope.launch { drawerState.open() }
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.vector_ellipsis),
                                    contentDescription = "Open Drawer",
                                    tint = Color.White
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { Toast.makeText(context, "Profile", Toast.LENGTH_SHORT).show() }) {
                                Icon(
                                    painter = painterResource(R.drawable.common_user),
                                    contentDescription = "User",
                                    tint = Color.White
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = Color.White
                        )
                    )
                },
                containerColor = Color.Transparent,
                contentColor = Color.White,
                content = { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Say,\nhey lisa!",
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = cstFont,
                            color = Color.White
                        )
                    }
                }
            )
        }
    }
}

// Settings Screen Composable
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = statusBarPadding)
            .background(Color.White)
    ) {
        // Simple app bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    painter = painterResource(R.drawable.back_nav),
                    contentDescription = "Back",
                    tint = Color.Black,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        // Settings content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Settings Screen",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.Black.copy(alpha = 0.7f),
                fontSize = 18.sp
            )
        }
    }
}

@Composable
fun ModelDownloadDialog(show: Boolean, progress: Float, isUnzipping: Boolean) {
    if (show) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(if (isUnzipping) "Unzipping Model" else "Downloading Model")
            },
            text = {
                Column {
                    Text(if (isUnzipping) "Extracting files..." else "Downloading required files...")
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        color = ProgressIndicatorDefaults.linearColor,
                        trackColor = ProgressIndicatorDefaults.linearTrackColor,
                        strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                    )
                    Text("${(progress * 100).toInt()}%")
                }
            },
            confirmButton = {}
        )
    }
}