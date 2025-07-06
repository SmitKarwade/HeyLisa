package com.example.heylisa.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import com.example.heylisa.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransparentScaffoldWithToolbar() {
    // Set your drawable image as the background

    val gradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF4F60B6), Color(0xFF6A78C2), Color(0xFFEAE8F3)),
    )
    
    val cstFont = FontFamily(
        Font(R.font.poppins_regular)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
    ) {
//        Image(
//            painter = painterResource(id = R.drawable.voice_grad),
//            contentDescription = null,
//            contentScale = ContentScale.Crop,
//            modifier = Modifier.fillMaxSize().alpha(0.8f)
//        )

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = "") }, // Empty title
                    navigationIcon = {
                        IconButton(onClick = { /* Left Action */ }) {
                            Icon(
                                painter = painterResource(R.drawable.vector_ellipsis),
                                contentDescription = "Favorite",
                                tint = Color.White
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { /* Action 2 */ }) {
                            Icon(
                                painter = painterResource(R.drawable.common_user),
                                contentDescription = "Settings",
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
