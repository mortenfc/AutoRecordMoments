package com.mfc.recentaudiobuffer

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.SignInClient
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject

class AuthenticationManager @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val auth: FirebaseAuth,
    private val settingsRepository: SettingsRepository
) {
    val signInButtonText: MutableState<String> = mutableStateOf("Sign In")
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var googleSignInLauncher: ActivityResultLauncher<Intent>
    private var oneTapLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
    private val oneTapClient: SignInClient = Identity.getSignInClient(applicationContext)
    private var currentActivity: ComponentActivity? = null
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        setupGoogleSignIn()
        updateInitialSignInStatus()
        auth.addAuthStateListener { firebaseAuth ->
            Timber.d("AuthStateListener triggered")
            onSuccessSignInOut(firebaseAuth.currentUser)
        }
    }

    fun registerLauncher(activity: ComponentActivity) {
        Timber.d("registerLauncher called")
        currentActivity = activity
        oneTapLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            Timber.d("One Tap ActivityResult received")
            if (result.resultCode == Activity.RESULT_OK) {
                try {
                    val credential = oneTapClient.getSignInCredentialFromIntent(result.data)
                    val googleIdToken = credential.googleIdToken
                    if (googleIdToken != null) {
                        val firebaseCredential =
                            GoogleAuthProvider.getCredential(googleIdToken, null)
                        managerScope.launch(Dispatchers.IO) {
                            val authResult = auth.signInWithCredential(firebaseCredential).await()
                            onSuccessSignInOut(authResult.user)
                        }
                    }
                } catch (e: ApiException) {
                    Timber.e("Error during One Tap sign-in $e")
                    // Fallback to traditional sign-in
                    signIn()
                }
            } else {
                Timber.e("One Tap sign-in failed with result code: ${result.resultCode}")
                // Fallback to traditional sign-in
                signIn()
            }
        }

        googleSignInLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result: ActivityResult ->
            Timber.d("Traditional ActivityResult received")
            onSignInResult(result)
        }
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(applicationContext.getString(R.string.default_web_client_id))
            .requestEmail().build()
        googleSignInClient = GoogleSignIn.getClient(applicationContext, gso)
    }

    fun onSignInClick() {
        val user = auth.currentUser
        if (user != null) {
            signOut()
        } else {
            beginOneTapSignIn()
        }
    }

    private fun beginOneTapSignIn() {
        val request = BeginSignInRequest.builder().setGoogleIdTokenRequestOptions(
            BeginSignInRequest.GoogleIdTokenRequestOptions.builder().setSupported(true)
                .setServerClientId(applicationContext.getString(R.string.default_web_client_id))
                .setFilterByAuthorizedAccounts(false).build()
        ).setAutoSelectEnabled(true).build()

        oneTapClient.beginSignIn(request).addOnSuccessListener { result ->
            Timber.d("beginSignIn successful")
            if (currentActivity?.isFinishing == false && currentActivity?.isDestroyed == false) {
                try {
                    val intentSenderRequest =
                        IntentSenderRequest.Builder(result.pendingIntent.intentSender).build()
                    oneTapLauncher?.launch(intentSenderRequest)
                } catch (e: Exception) {
                    Timber.e("Error launching One Tap intent $e")
                    // Fallback to traditional sign-in
                    signIn()
                }
            } else {
                Timber.e("Activity is finishing or destroyed, not launching One Tap")
            }
        }.addOnFailureListener { e ->
            Timber.e("beginSignIn failed $e")
            // Fallback to traditional sign-in
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
                Timber.e("signInWithCredential:failure ${task.exception}")
            }
        }
    }

    private fun onSuccessSignInOut(user: FirebaseUser?) {
        signInButtonText.value = if (user != null) "Sign Out" else "Sign In"
        if (user != null) {
            managerScope.launch(Dispatchers.IO) {
                settingsRepository.pullFromFirestore()
            }
        }
    }

    private fun updateInitialSignInStatus() {
        val user = auth.currentUser
        onSuccessSignInOut(user)
    }

    private fun onSignInResult(result: ActivityResult) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            firebaseAuthWithGoogle(account)
        } catch (e: ApiException) {
            logSignInError(e)
        } catch (e: Exception) {
            logSignInError(result)
        }
    }

    private fun logSignInError(e: ApiException) {
        Timber.w("Google sign in failed: $e")
        Timber.w("Status Code: ${e.statusCode}")
        Timber.w("Message: ${e.message}")
    }

    private fun logSignInError(result: ActivityResult) {
        Timber.e(
            "Google sign in failed with error code, data: ${result.resultCode}, ${result.data?.extras}"
        )
        val bundle = result.data?.extras
        if (bundle != null) {
            for (key in bundle.keySet()) {
                val value = bundle.get(key)
                Timber.e("Bundle data - Key: $key, Value: $value")
            }
        }
    }
}