package com.example.heylisa.custom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import com.example.heylisa.ui.theme.HeyLisaTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HeyLisaTheme {
                Surface {
                    Text("Hey Lisa Assistant Settings")
                }
            }
        }
    }
}
