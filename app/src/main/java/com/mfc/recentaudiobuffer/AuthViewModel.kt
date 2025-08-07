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

// Create a new file, e.g., AuthViewModel.kt
package com.mfc.recentaudiobuffer

import android.app.Activity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

interface IAuthViewModel {
    val isSigningIn: StateFlow<Boolean>
    val authError: StateFlow<AuthError?>
    val signInButtonText: MutableState<String>

    fun onSignInClick(activity: Activity)
    fun onSignOutClick()
    fun clearAuthError()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authenticationManager: AuthenticationManager
) : ViewModel(), IAuthViewModel  {

    // Expose the states directly from the AuthenticationManager
    override val isSigningIn = authenticationManager.isSigningIn
    override val authError = authenticationManager.authError
    override val signInButtonText = authenticationManager.signInButtonText

    override fun onSignInClick(activity: Activity) {
        viewModelScope.launch {
            authenticationManager.onSignInClick(activity)
        }
    }

    override fun onSignOutClick() {
         viewModelScope.launch {
            authenticationManager.signOut() // Assuming you have a public signOut function
        }
    }

    override fun clearAuthError() {
        authenticationManager.clearAuthError()
    }
}

/**
 * A fake implementation of AuthViewModel for use in @Preview composables.
 * This allows us to define the exact state we want to preview without
 * needing Hilt or real authentication logic.
 */
class FakeAuthViewModel(
    initialIsSigningIn: Boolean,
    initialAuthError: AuthError?,
    initialSignInText: String
) : IAuthViewModel {

    // No more "override", just implement the interface properties
    override val isSigningIn = MutableStateFlow(initialIsSigningIn).asStateFlow()
    override val authError = MutableStateFlow(initialAuthError).asStateFlow()
    override val signInButtonText = mutableStateOf(initialSignInText)

    override fun onSignInClick(activity: Activity) {}
    override fun onSignOutClick() {}
    override fun clearAuthError() {}
}