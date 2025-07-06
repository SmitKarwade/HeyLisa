package com.example.heylisa.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heylisa.R

@Composable
fun HeyLisaBar(
    text: MutableState<String>,
    onTextChange: (String) -> Unit,
    onMicClick: () -> Unit,
    onSendClick: () -> Unit
) {
    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(Color(0xFF4BE1EC), Color(0xFFDA86FC))
    )

    Surface(
        shape = RoundedCornerShape(25.dp),
        shadowElevation = 6.dp,
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .height(70.dp)
            .background(Color.Transparent)
            .border(
                width = 2.dp,
                brush = gradientBrush,
                shape = RoundedCornerShape(30)
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add",
                tint = Color.Gray
            )

            Spacer(modifier = Modifier.width(8.dp))

            AutoScrollTextField(text = text, modifier = Modifier.weight(1f))

            IconButton(
                onClick = {
                    if (text.value.isEmpty()) {
                        onMicClick()
                    } else {
                        onSendClick()
                    }
                }
            ) {
                Icon(
                    painter = painterResource(
                        id = if (text.value.isEmpty()) R.drawable.mic else R.drawable.send_icon
                    ),
                    contentDescription = if (text.value.isEmpty()) "Mic" else "Send",
                    tint = Color(0xFF6A78C2)
                )
            }
        }
    }
}

@Composable
fun AutoScrollTextField(text: MutableState<String>,modifier: Modifier) {
    val cstFont = FontFamily(
        Font(R.font.poppins_regular)
    )
    val scrollState = rememberScrollState()

    LaunchedEffect(text.value) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Box(
        modifier = modifier
            .heightIn(min = 56.dp, max = 200.dp)
            .verticalScroll(scrollState)
            .background(Color.Transparent)
    ) {
        TextField(
            value = text.value,
            onValueChange = { newText -> text.value = newText },
            placeholder = { Text(text = "Ask Lisa", color = Color.Gray) },
            modifier = modifier
                .fillMaxWidth()
                .background(Color.Transparent),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedTextColor = Color.Black,
                cursorColor = Color.Black,
            ),
            textStyle = TextStyle(
                fontSize = 16.sp,
                color = Color.Black,
                fontFamily = cstFont
            ),
            singleLine = false
        )
    }
}


@Composable
@Preview
fun HeyLisaBarPreview() {
    val text = remember { mutableStateOf("") }
    HeyLisaBar(text = text, onTextChange = {}, onMicClick = {}, onSendClick = {})
}

