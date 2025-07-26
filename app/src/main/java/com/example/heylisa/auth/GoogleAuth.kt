package com.example.heylisa.auth

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.heylisa.R
import com.example.heylisa.viewmodel.AuthViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope

@Composable
fun App(viewModel: AuthViewModel = viewModel()) {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(40.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GoogleSignInButton(viewModel = viewModel)
        }
    }
}

@Composable
fun GoogleSignInButton(viewModel: AuthViewModel) {
    val context = LocalContext.current
    val googleSignInClient = remember { getGoogleSignInClient(context) }
    val uiState = viewModel.uiState.collectAsState()

    val cstFont = FontFamily(
        Font(R.font.poppins_regular)
    )

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleSignInResult(context, result.data)
    }

    // Show error toast when error occurs
    LaunchedEffect(uiState.value.error) {
        uiState.value.error?.let { error ->
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }


    when {
        uiState.value.isLoading -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Custom gradient circular progress
                GradientCircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 4.dp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Signing in...",
                    color = Color.Black,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = cstFont
                )
            }
        }
        uiState.value.isSignedIn -> Text("Signed in as: ${uiState.value.email}")
        else -> Button(
            onClick = {
                val signInIntent = googleSignInClient.signInIntent
                launcher.launch(signInIntent)
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black
            ),
            border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f)),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(10.dp),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 4.dp,
                pressedElevation = 6.dp,
                disabledElevation = 0.dp
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Google logo from drawable
                Icon(
                    painter = painterResource(id = R.drawable.google),
                    contentDescription = "Google Logo",
                    modifier = Modifier.size(20.dp),
                    tint = Color.Unspecified
                )

                Text(
                    text = "Login via Google",
                    color = Color.Black,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = cstFont
                )
            }
        }
    }
}

@Composable
fun GradientCircularProgressIndicator(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 4.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rotation")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Canvas(modifier = modifier) {
        val canvasSize = size.minDimension
        val radius = (canvasSize - strokeWidth.toPx()) / 2
        val center = Offset(size.width / 2, size.height / 2)

        // Background circle
        drawCircle(
            color = Color.Gray.copy(alpha = 0.2f),
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth.toPx())
        )

        // Gradient arc
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(
                    Color(0xFF4285F4), // Google Blue
                    Color(0xFFEA4335), // Google Red
                    Color(0xFFFBBC05), // Google Yellow
                    Color(0xFF34A853), // Google Green
                    Color(0xFF4285F4)  // Back to blue for seamless loop
                ),
                center = center
            ),
            startAngle = angle - 90f, // Start from top
            sweepAngle = 270f, // 3/4 of the circle
            useCenter = false,
            style = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round
            ),
            size = Size(radius * 2, radius * 2),
            topLeft = Offset(
                center.x - radius,
                center.y - radius
            )
        )
    }
}


fun getGoogleSignInClient(context: android.content.Context): GoogleSignInClient {
    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestIdToken("643617418308-66gtgeohdts3fv1844jfu7sf80j4q5sn.apps.googleusercontent.com") // Web Client ID
        .requestEmail()
        .requestServerAuthCode("643617418308-66gtgeohdts3fv1844jfu7sf80j4q5sn.apps.googleusercontent.com")
        .requestScopes(
            Scope("https://www.googleapis.com/auth/gmail.readonly"),
            Scope("https://www.googleapis.com/auth/userinfo.email"),
            Scope("https://www.googleapis.com/auth/userinfo.profile"),
            Scope("https://www.googleapis.com/auth/gmail.send"),
            Scope("openid")
        )
        .build()
    return GoogleSignIn.getClient(context, gso)
}