package com.example.heylisa.voice

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.heylisa.R

@Composable
fun HeyLisaBar(
    text: MutableState<String>,
    onTextChange: (String) -> Unit,
    onMicClick: () -> Unit,
    onSendClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(30),
        shadowElevation = 6.dp,
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .height(60.dp)
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

            TextField(
                value = text.value,
                onValueChange = onTextChange,
                placeholder = { Text(text = "Ask Lisa", color = Color.Gray) },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.Black

                ),
                singleLine = true,
                maxLines = 1
            )

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
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

