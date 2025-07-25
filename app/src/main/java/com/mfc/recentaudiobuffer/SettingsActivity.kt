package com.mfc.recentaudiobuffer

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
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
import com.google.firebase.auth.FirebaseAuth
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
        authenticationManager.registerLauncher(this)
    }

    @SuppressLint("UnrememberedMutableState")
    @androidx.compose.runtime.Composable
    fun SettingsScreenInitializer(settingsViewModel: SettingsViewModel = hiltViewModel()) {
        val scope = rememberCoroutineScope()
        val config by settingsViewModel.config.collectAsState()
        val state = remember { mutableStateOf(SettingsScreenState(config)) }
        val isSaving by settingsViewModel.isSaving.collectAsState()
        var hasSaved by remember { mutableStateOf(false) }

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
            onSignInClick = { authenticationManager.onSignInClick() },
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
    }
}