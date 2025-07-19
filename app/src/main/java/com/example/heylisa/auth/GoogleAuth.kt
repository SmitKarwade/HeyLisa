package com.example.heylisa.auth

import android.content.Context
import android.content.Intent
import android.util.Log
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
            // Successfully signed in
            val idToken = account.idToken // Use this token for backend verification
            val email = account.email
            // Handle the signed-in user and navigate to MainActivity
            println("Signed in as: $email, ID Token: $idToken")
            navigateToMainActivity(context, idToken, email)
        } catch (e: ApiException) {
            Log.e("Token","Sign-in failed: ${e.statusCode}, Message: ${e.message}")
        }
    }

    Button(onClick = {
        val signInIntent = googleSignInClient.signInIntent
        launcher.launch(signInIntent)
    }) {
        Text("Sign in with Google")
    }
}

fun getGoogleSignInClient(context: android.content.Context): GoogleSignInClient {
    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestIdToken("643617418308-66gtgeohdts3fv1844jfu7sf80j4q5sn.apps.googleusercontent.com") // Replace with your Web Client ID
        .requestEmail()
        .build()
    return GoogleSignIn.getClient(context, gso)
}

fun navigateToMainActivity(context: Context, idToken: String?, email: String?) {
    val intent = Intent(context, MainActivity::class.java).apply {
        // Pass authentication data as extras if needed
        putExtra("idToken", idToken)
        putExtra("email", email)
        // Add flags to clear the back stack and start fresh
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    context.startActivity(intent)
}