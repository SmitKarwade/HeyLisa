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
import com.example.heylisa.request.AuthClient
import com.example.heylisa.request.AuthRequest
import com.example.heylisa.request.AuthResponse
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import retrofit2.Call
import retrofit2.Response
import javax.security.auth.callback.Callback

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
            println("Signed in as: $email, ID Token: $idToken")
            Log.d("code", "Server Auth Code: $serverAuthCode")
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
    val request = AuthRequest(server_auth_code = authCode)
    val call = AuthClient.authApi.exchangeAuthCodeForToken(request)

    call.enqueue(object : retrofit2.Callback<AuthResponse> {
        override fun onResponse(call: Call<AuthResponse>, response: Response<AuthResponse>) {
            if (response.isSuccessful) {
                val authResponse = response.body()
                val accessToken = authResponse?.access_token
                if (accessToken != null) {
                    println("Access Token: $accessToken")
                } else {
                    println("Access Token not found in response")
                }
            } else {
                println("Token exchange failed: ${response.code()} - ${response.errorBody()?.string()}")
            }
        }

        override fun onFailure(call: Call<AuthResponse>, t: Throwable) {
            println("Token exchange failed: ${t.message}")
        }
    })
}

fun getGoogleSignInClient(context: android.content.Context): GoogleSignInClient {
    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestIdToken("643617418308-66gtgeohdts3fv1844jfu7sf80j4q5sn.apps.googleusercontent.com") // Web Client ID
        .requestEmail()
        .requestServerAuthCode("643617418308-66gtgeohdts3fv1844jfu7sf80j4q5sn.apps.googleusercontent.com")
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