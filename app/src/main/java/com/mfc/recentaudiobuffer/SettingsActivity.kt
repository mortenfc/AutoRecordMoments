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

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.mfc.recentaudiobuffer.ui.theme.RecentAudioBufferTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {
    @Inject
    lateinit var authenticationManager: AuthenticationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            RecentAudioBufferTheme {
                SettingsScreenInitializer()
            }
        }
    }

    override fun onStart() {
        Timber.i("onStart() called")
        super.onStart()
    }

    @SuppressLint("UnrememberedMutableState")
    @androidx.compose.runtime.Composable
    fun SettingsScreenInitializer(settingsViewModel: SettingsViewModel = hiltViewModel()) {
        val scope = rememberCoroutineScope()
        val config by settingsViewModel.config.collectAsState()
        val state = remember { mutableStateOf(SettingsScreenState(config)) }
        val isSaving by settingsViewModel.isSaving.collectAsState()
        var hasSaved by remember { mutableStateOf(false) }
        var showDeleteConfirmationDialog by remember { mutableStateOf(false) }

        // Sync config with state of BUFFER_TIME_LENGTH_S
        LaunchedEffect(config) {
            // When the config from the ViewModel changes,
            // create a new state object with the correct, loaded values.
            Timber.d("Config updated from ViewModel, re-creating UI state.")
            state.value = SettingsScreenState(config)
        }

        // Observe the isSaving state and finish the activity when saving is complete
        LaunchedEffect(isSaving, hasSaved) {
            Timber.d("LaunchedEffect isSaving, hasSaved: $isSaving, $hasSaved")
            if (!isSaving && hasSaved) {
                finish()
            }
        }

        SettingsScreen(
            state = state,
            signInButtonText = authenticationManager.signInButtonText,
            onSignInClick = { authenticationManager.onSignInClick(this) },
            onDeleteAccountClick = { showDeleteConfirmationDialog = true },
            authError = authenticationManager.authError.collectAsState().value,
            onDismissSignInErrorDialog = { authenticationManager.clearAuthError() },
            onSampleRateChanged = { value ->
                Timber.d("onSampleRateChanged to $value")
                state.value.updateSampleRateTemp(value)
                state.value.validateSettings()
            },
            onBitDepthChanged = { value ->
                Timber.d("onBitDepthChanged to $value")
                state.value.updateBitDepthTemp(value)
                state.value.validateSettings()
            },
            onBufferTimeLengthChanged = { value ->
                Timber.d("onBufferTimeLengthChanged to $value")
                // Temporary value updater for error recompose
                state.value.updateBufferTimeLengthTemp(value)
                state.value.validateSettings()
            },
            onAiAutoClipChanged = { value ->
                Timber.d("onBufferTimeLengthChanged to $value")
                // Temporary value updater for error recompose
                state.value.updateIsAiAutoClipEnabled(value)
            },
            onSubmit = {
                // Launch a coroutine for the save and restart logic
                scope.launch {
                    val configBeforeUpdate = settingsViewModel.config.value

                    // 1. Call updateSettings and get the list of jobs
                    val updateJobs = state.value.uploadSettingsToAppView(settingsViewModel)

                    // 2. Wait for all save operations to complete
                    updateJobs.joinAll()

                    // 3. Now the ViewModel's state is guaranteed to be updated
                    val configAfterUpdate = settingsViewModel.config.value

                    if (configBeforeUpdate != configAfterUpdate && MyBufferService.isServiceRunning.get()) {
                        Timber.d("Settings changed and service is running. Sending restart command.")
                        val intent =
                            Intent(this@SettingsActivity, MyBufferService::class.java).apply {
                                action = MyBufferService.ACTION_RESTART_WITH_NEW_SETTINGS
                            }
                        startService(intent)
                    } else if (configBeforeUpdate != configAfterUpdate) {
                        Timber.w(
                            "onSubmit(): Settings changed, but no recording session found"
                        )
                    } else {
                        Timber.i("onSubmit(): No settings changed")
                    }

                    hasSaved = true
                }
            },
            justExit = {
                this.finish()
            })

        if (showDeleteConfirmationDialog) {
            DeleteAccountConfirmationDialog(onDismissRequest = {
                showDeleteConfirmationDialog = false
            }, onConfirm = {
                showDeleteConfirmationDialog = false
                // Call the manager to delete the account
                authenticationManager.deleteAccount { success, error ->
                    if (success) {
                        Toast.makeText(
                            this, "Account deleted successfully.", Toast.LENGTH_SHORT
                        ).show()
                        // The AuthStateListener will automatically update the UI to "Sign In"
                    } else {
                        Toast.makeText(this, error ?: "An error occurred.", Toast.LENGTH_LONG)
                            .show()
                    }
                }
            })
        }
    }
}