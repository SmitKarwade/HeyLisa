package com.example.heylisa.auth

import android.content.Context
import android.content.Intent
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.heylisa.main.MainActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

@Composable
fun App() {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GoogleSignInButton()
        }
    }
}

@Composable
fun GoogleSignInButton() {
    val context = LocalContext.current
    val googleSignInClient = remember { getGoogleSignInClient(context) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken
            val email = account.email
            val serverAuthCode = account.serverAuthCode // Get the authorization code
            println("Signed in as: $email, ID Token: $idToken, Server Auth Code: $serverAuthCode")
            // or authcode null dialog
            if (serverAuthCode != null) {
                exchangeAuthCodeForToken(context, serverAuthCode, email)
            }
            navigateToMainActivity(context, idToken, email)
        } catch (e: ApiException) {
            println("Sign-in failed: ${e.statusCode}, Message: ${e.message}")
        }
    }

    Button(onClick = {
        val signInIntent = googleSignInClient.signInIntent
        launcher.launch(signInIntent)
    }) {
        Text("Sign in with Google")
    }
}

fun exchangeAuthCodeForToken(context: Context, authCode: String, email: String?) {
    println("Authorization Code: $authCode")
    
}

fun getGoogleSignInClient(context: android.content.Context): GoogleSignInClient {
    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestIdToken("643617418308-66gtgeohdts3fv1844jfu7sf80j4q5sn.apps.googleusercontent.com") // Web Client ID
        .requestEmail()
        .requestScopes(
            Scope("https://www.googleapis.com/auth/gmail.readonly") // Gmail scope
        )
        .build()
    return GoogleSignIn.getClient(context, gso)
}

fun navigateToMainActivity(context: Context, idToken: String?, email: String?) {
    val intent = Intent(context, MainActivity::class.java).apply {
        putExtra("idToken", idToken)
        putExtra("email", email)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    context.startActivity(intent)
}