package com.example.heylisa.main

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.example.heylisa.ui.theme.HeyLisaTheme
import com.example.heylisa.util.PicovoiceWakeWord
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.heylisa.util.startContinuousSpeechRecognition
import com.example.heylisa.util.stopContinuousSpeechRecognition

class MainActivity : ComponentActivity() {

    private lateinit var wakeWordListener: PicovoiceWakeWord
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (!isGranted) {
                Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }

        enableEdgeToEdge()
        setContent {
            HeyLisaTheme {
                val spokenText = remember { mutableStateOf("Listening for wake word...") }
                val currentSpokenText = rememberUpdatedState(spokenText)
                val isListening = remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    wakeWordListener = PicovoiceWakeWord(
                        context = this@MainActivity,
                        onWakeWordDetected = {

                            wakeWordListener.stop()
                            currentSpokenText.value.value = ""

                            Handler(Looper.getMainLooper()).postDelayed({
                                startContinuousSpeechRecognition(this@MainActivity) { partial, final ->
                                    currentSpokenText.value.value = final ?: partial ?: ""
                                    isListening.value = true
                                }
                            }, 500)
                        }
                    )
                    wakeWordListener.start()
                }
                Main(currentSpokenText = currentSpokenText, isListening = isListening, onRestartWakeWord = {wakeWordListener.start()})
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeWordListener.stop()
    }
}

@Composable
fun Main( modifier: Modifier = Modifier, currentSpokenText: State<MutableState<String>>, isListening: MutableState<Boolean>, onRestartWakeWord: () -> Unit) {

    Scaffold(modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            if (isListening.value) {
                FloatingActionButton(
                    onClick = {
                        stopContinuousSpeechRecognition()
                        isListening.value = false
                        currentSpokenText.value.value = "Listening for wake word..."
                        onRestartWakeWord()
                    }
                ) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_media_pause),
                        contentDescription = "Pause",
                        tint = Color.White
                    )
                }
            }
        }) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)){
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = currentSpokenText.value.value, style = TextStyle(fontSize = 20.sp))
            }
        }
    }
}

@Composable
fun floatingButton(){
    val isListening = remember { mutableStateOf(false) }

}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    HeyLisaTheme {

    }
}







