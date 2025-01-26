package com.mfc.recentaudiobuffer

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

class AuthenticationManager @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val auth: FirebaseAuth,
    private val settingsRepository: SettingsRepository
) {
    val signInButtonText: MutableState<String> = mutableStateOf("Sign In")
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var googleSignInLauncher: ActivityResultLauncher<Intent>
    private val logTag = "AuthenticationManager"

    // Shared Flow for login state
    private val _isUserLoggedIn = MutableSharedFlow<Boolean>(replay = 1)
    val isUserLoggedIn = _isUserLoggedIn.asSharedFlow()

    init {
        setupGoogleSignIn()
        updateInitialSignInStatus()
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(applicationContext.getString(R.string.default_web_client_id))
            .requestEmail().build()
        googleSignInClient = GoogleSignIn.getClient(applicationContext, gso)
    }

    fun setGoogleSignInLauncher(launcher: ActivityResultLauncher<Intent>) {
        googleSignInLauncher = launcher
    }

    fun onSignInClick() {
        val user = auth.currentUser
        if (user != null) {
            signOut()
        } else {
            signIn()
        }
    }

    private fun signIn() {
        val signInIntent = googleSignInClient.signInIntent
        googleSignInLauncher.launch(signInIntent)
    }

    private fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener {
            auth.signOut()
            onSuccessSignInOut(null)
        }
    }

    private fun firebaseAuthWithGoogle(acct: GoogleSignInAccount) {
        val credential = GoogleAuthProvider.getCredential(acct.idToken, null)
        auth.signInWithCredential(credential).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val user = auth.currentUser
                onSuccessSignInOut(user)
            } else {
                logSignInError("signInWithCredential:failure", task.exception)
            }
        }
    }

    private fun onSuccessSignInOut(user: FirebaseUser?) {
        signInButtonText.value = if (user != null) "Sign Out" else "Sign In"
        // Emit the new login state to the shared flow
        _isUserLoggedIn.tryEmit(user != null)
        if (user != null) {
            CoroutineScope(Dispatchers.IO).launch {
                settingsRepository.syncSettings(user.uid)
            }
        }
    }

    private fun updateInitialSignInStatus() {
        val user = auth.currentUser
        onSuccessSignInOut(user)
    }

    fun onSignInResult(result: ActivityResult) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            firebaseAuthWithGoogle(account)
        } catch (e: ApiException) {
            logSignInError("Google sign in failed", e)
        } catch (e: Exception) {
            logSignInError(result)
        }
    }

    private fun logSignInError(message: String, e: ApiException? = null) {
        if (e != null) {
            Log.w(logTag, "Google sign in failed: $message", e)
            Log.w(logTag, "Statuscode: ${e.statusCode}")
            Log.w(logTag, "Message: ${e.message}")
        } else {
            Log.w(logTag, "Google sign in failed: $message")
        }
    }

    private fun logSignInError(result: ActivityResult) {
        Log.e(
            logTag,
            "Google sign in failed with error code, data: ${result.resultCode}, ${result.data?.extras}"
        )
        val bundle = result.data?.extras
        if (bundle != null) {
            for (key in bundle.keySet()) {
                val value = bundle.get(key)
                Log.e(logTag, "Bundle data - Key: $key, Value: $value")
            }
        }
    }

    private fun logSignInError(message: String, e: Exception? = null) {
        Log.w(logTag, message, e)
    }
}