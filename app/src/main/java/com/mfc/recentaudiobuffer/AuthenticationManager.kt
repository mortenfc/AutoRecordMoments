/*
 * Auto Record Moments
 * Copyright (C) 2025 Morten Fjord Christensen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.mfc.recentaudiobuffer

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

sealed class AuthError {
    data class Generic(val message: String) : AuthError()
    object NoAccountsFound : AuthError()
}

class AuthenticationManager @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val auth: FirebaseAuth,
    private val settingsRepository: SettingsRepository,
    private val firestore: FirebaseFirestore
) {
    val signInButtonText: MutableState<String> = mutableStateOf("Sign In")

    private val _isSigningIn = MutableStateFlow(false)
    val isSigningIn = _isSigningIn.asStateFlow()

    private val _authError = MutableStateFlow<AuthError?>(null)
    val authError = _authError.asStateFlow()

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        updateInitialSignInStatus()
        auth.addAuthStateListener { firebaseAuth ->
            Timber.d("AuthStateListener triggered")
            onSuccessSignInOut(firebaseAuth.currentUser)
        }
    }

    fun deleteAccount(onComplete: (success: Boolean, error: String?) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            onComplete(false, "No user is signed in.")
            return
        }

        managerScope.launch(Dispatchers.IO) {
            try {
                // Step 1: Delete Firestore data
                firestore.collection("users").document(user.uid).delete().await()
                Timber.d("User data deleted from Firestore.")

                // Step 2: Delete Firebase Auth account
                user.delete().await()
                Timber.d("User account deleted from Firebase Auth.")

                // Step 3: Clear local credential state to ensure user can sign in again
                val credentialManager = CredentialManager.create(applicationContext)
                credentialManager.clearCredentialState(ClearCredentialStateRequest())

                withContext(Dispatchers.Main) {
                    onComplete(true, null) // Signal success
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete account.")
                withContext(Dispatchers.Main) {
                    // Provide a user-friendly error message
                    onComplete(
                        false,
                        "Failed to delete account. Please try signing out and signing back in again."
                    )
                }
            }
        }
    }

    fun onSignInClick(activity: Activity) {
        managerScope.launch {
            val user = auth.currentUser
            if (user != null) {
                signOut()
            } else {
                signIn(activity)
            }
        }
    }

    private suspend fun signIn(activity: Activity) {
        // Prevent multiple sign-in attempts
        if (_isSigningIn.value) return

        val credentialManager = CredentialManager.create(activity)
        val serverClientId = activity.getString(R.string.default_web_client_id)

        val googleIdOption = GetGoogleIdOption.Builder().setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId).setAutoSelectEnabled(true).build()

        val credentialRequest =
            GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

        try {
            _isSigningIn.value = true
            val result: GetCredentialResponse =
                credentialManager.getCredential(activity, credentialRequest)
            handleSignInSuccess(result)
        } catch (e: NoCredentialException) {
            Timber.e(e, "No credential found.")
            _authError.value = AuthError.NoAccountsFound
        } catch (e: GetCredentialException) {
            Timber.e(e, "GetCredentialException")
            _authError.value = AuthError.Generic("Sign-in failed. Please try again.")
        } catch (e: Exception) {
            Timber.e(e, "An unexpected error occurred during sign-in.")
            _authError.value = AuthError.Generic("An unexpected error occurred.")
        } finally {
            _isSigningIn.value = false
        }
    }

    private fun handleSignInSuccess(result: GetCredentialResponse) {
        val credential = result.credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            try {
                // 3. Extract the token and sign in to Firebase
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                firebaseAuthWithGoogle(googleIdToken)
            } catch (e: Exception) {
                Timber.e(e, "Could not create Google ID token from credential data.")
                _authError.value = AuthError.Generic("An error occurred with your Google Account.")
            }
        } else {
            Timber.e("Unexpected credential type: ${credential::class.java.name}")
            _authError.value = AuthError.Generic("Sign-in failed. Unexpected credential type.")
        }
    }

    suspend fun signOut() {
        // This clears the user's sign-in state to allow account selection next time.
        val credentialManager = CredentialManager.create(applicationContext)
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
        auth.signOut()
        // The AuthStateListener will handle the UI update
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        managerScope.launch(Dispatchers.IO) {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            try {
                auth.signInWithCredential(credential).await()
                // Success is handled by the AuthStateListener
            } catch (e: Exception) {
                Timber.e(e, "signInWithCredential failed")
                _authError.value = AuthError.Generic("Authentication failed: ${e.message}")
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

    fun clearAuthError() {
        _authError.value = null
    }
}