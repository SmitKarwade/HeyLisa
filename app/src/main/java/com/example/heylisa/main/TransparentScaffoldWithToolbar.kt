package com.example.heylisa.main

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.heylisa.R
import com.example.heylisa.util.VoskWakeWordService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransparentScaffoldWithToolbar(context: Context) {

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val gradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF4F60B6), Color(0xFF6A78C2), Color(0xFFEAE8F3)),
    )

    val cstFont = FontFamily(
        Font(R.font.poppins_regular)
    )
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

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
                        modifier = Modifier.padding(16.dp)
                    )
                    HorizontalDivider(
                        Modifier,
                        DividerDefaults.Thickness,
                        color = Color.Black.copy(alpha = 0.3f)
                    )
                    Text(
                        text = "Not available for now",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                        color = Color.Black.copy(alpha = 0.7f)
                    )
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
                            IconButton(onClick = { Toast.makeText(context, "Profile", Toast.LENGTH_SHORT).show()}) {
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

@Composable
fun WakeWordServiceControl(modifier: Modifier = Modifier, context: Context) {
    val isRunning = remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = {
            val intent = Intent(context, VoskWakeWordService::class.java)
            ContextCompat.startForegroundService(context, intent)
            isRunning.value = true
        }) {
            Text("Start Wake Word Detection")
        }

        if (isRunning.value) {
            Button(onClick = {
                context.stopService(Intent(context, VoskWakeWordService::class.java))
                isRunning.value = false
            }) {
                Text("Stop Service")
            }
        }
    }
}


