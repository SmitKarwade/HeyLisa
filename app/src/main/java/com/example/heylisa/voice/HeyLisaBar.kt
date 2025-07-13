package com.example.heylisa.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PaintingStyle.Companion.Stroke
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heylisa.R

@Composable
fun HeyLisaBar(
    text: MutableState<String>,
    onTextChange: (String) -> Unit,
    onMicClick: () -> Unit,
    onSendClick: () -> Unit,
    isListening: Boolean
) {
//    val gradientBrush = Brush.horizontalGradient(
//        colors = listOf(Color(0xFF4BE1EC), Color(0xFFDA86FC))
//    )

    AnimatedGradientBorderBox(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .height(70.dp),
        borderWidth = 3.dp,
        cornerRadius = 25.dp
    )

    Box(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .height(70.dp)
            .padding(2.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(25.dp),
            shadowElevation = 6.dp,
            color = Color.White,
            modifier = Modifier
                .fillMaxSize()
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
                    if (text.value.isEmpty()) {
                        ListeningMicIcon(isListening = isListening)
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.send_icon),
                            contentDescription = "Send",
                            tint = Color(0xFF6A78C2)
                        )
                    }
                }
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
fun AnimatedGradientBorderBox(
    modifier: Modifier = Modifier,
    borderWidth: Dp = 4.dp,
    cornerRadius: Dp = 24.dp,
) {
    val infiniteTransition = rememberInfiniteTransition()
    val animatedOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Canvas(modifier = modifier) {
        val stroke = borderWidth.toPx()
        val rect = size.toRect().deflate(stroke / 2)
        val radius = cornerRadius.toPx()

        val gradientBrush = Brush.linearGradient(
            colors = listOf(
                Color(0xFFDA86FC),
                Color(0xFF906BD4),
                Color(0xFF4BE1EC)
            ),
            start = Offset(x = rect.left + rect.width * animatedOffset, y = rect.top),
            end = Offset(x = rect.left + rect.width * (animatedOffset + 0.2f) % rect.width, y = rect.bottom)
        )

        drawRoundRect(
            brush = gradientBrush,
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = stroke)
        )
    }
}


@Composable
fun ListeningMicIcon(isListening: Boolean) {
    val infiniteTransition = rememberInfiniteTransition()
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Icon(
        painter = painterResource(id = R.drawable.mic),
        contentDescription = "Mic",
        tint = Color(0xFF6A78C2),
        modifier = Modifier
            .size(24.dp)
            .graphicsLayer {
                scaleX = if (isListening) scale else 1f
                scaleY = if (isListening) scale else 1f
            }
    )
}



@Composable
@Preview
fun HeyLisaBarPreview() {
    val text = remember { mutableStateOf("") }
    HeyLisaBar(text = text, onTextChange = {}, onMicClick = {}, onSendClick = {}, isListening = true)
}

