/*
 * # Auto Record Moments
 * # Copyright (C) 2025 Morten Fjord Christensen
 * #
 * # This program is free software: you can redistribute it and/or modify
 * # it under the terms of the GNU Affero General Public License as published by
 * # the Free Software Foundation, either version 3 of the License, or
 * # (at your option) any later version.
 * #
 * # This program is distributed in the hope that it will be useful,
 * # but WITHOUT ANY WARRANTY; without even the implied warranty of
 * # MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * # GNU Affero General Public License for more details.
 * #
 * # You should have received a copy of the GNU Affero General Public License
 * # along with this program.  If not, see <https://www.gnu.org/licenses/>.
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
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _authError = MutableStateFlow<String?>(null)
    val authError = _authError.asStateFlow()

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        updateInitialSignInStatus()
        auth.addAuthStateListener { firebaseAuth ->
            Timber.d("AuthStateListener triggered")
            onSuccessSignInOut(firebaseAuth.currentUser)
        }
    }

    // ✅ The sign-in logic is now in a single suspend function
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
        val credentialManager = CredentialManager.create(activity)
        val serverClientId = activity.getString(R.string.default_web_client_id)

        // 1. Build the request for a Google ID token
        val googleIdOption = GetGoogleIdOption.Builder().setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId).setAutoSelectEnabled(true).build()

        val credentialRequest =
            GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

        try {
            // 2. Launch the credential picker
            val result: GetCredentialResponse =
                credentialManager.getCredential(activity, credentialRequest)
            handleSignInSuccess(result)

        } catch (e: GetCredentialException) {
            Timber.e(e, "GetCredentialException")
            _authError.value = "Sign-in failed. Please try again."
        } catch (e: Exception) {
            Timber.e(e, "An unexpected error occurred during sign-in.")
            _authError.value = "An unexpected error occurred."
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
                _authError.value = "An error occurred with your Google Account."
            }
        } else {
            Timber.e("Unexpected credential type: ${credential::class.java.name}")
            _authError.value = "Sign-in failed. Unexpected credential type."
        }
    }

    private suspend fun signOut() {
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
                _authError.value = "Authentication failed: ${e.message}"
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