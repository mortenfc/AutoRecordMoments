package com.mfc.recentaudiobuffer

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import com.google.firebase.auth.FirebaseAuth
import com.mfc.recentaudiobuffer.ui.theme.RecentAudioBufferTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {
    private val logTag = "SettingsActivity"

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
        Log.i(logTag, "onStart() called")
        super.onStart()
        authenticationManager.registerLauncher(this)
    }

    @SuppressLint("UnrememberedMutableState")
    @androidx.compose.runtime.Composable
    fun SettingsScreenInitializer(settingsViewModel: SettingsViewModel = hiltViewModel()) {
        val scope = rememberCoroutineScope()
        val config by settingsViewModel.config.collectAsState()
        val auth = FirebaseAuth.getInstance()
        val state = remember { mutableStateOf(SettingsScreenState(config)) }
        val isSaving by settingsViewModel.isSaving.collectAsState()
        var hasSaved by remember { mutableStateOf(false) }

        // Re-fetch settings when user logs in
        LaunchedEffect(auth.currentUser) {
            Log.d(logTag, "LaunchedEffect auth.currentUser: ${auth.currentUser}")
            if (auth.currentUser != null) {
                settingsViewModel.refreshSettings()
            }
        }

        // Sync config with state of BUFFER_TIME_LENGTH_S
        LaunchedEffect(config) {
            // When the config from the ViewModel changes,
            // create a new state object with the correct, loaded values.
            Log.d(logTag, "Config updated from ViewModel, re-creating UI state.")
            state.value = SettingsScreenState(config)
        }

        // Observe the isSaving state and finish the activity when saving is complete
        LaunchedEffect(isSaving, hasSaved) {
            Log.d(logTag, "LaunchedEffect isSaving, hasSaved: $isSaving, $hasSaved")
            if (!isSaving && hasSaved) {
                finish()
            }
        }

        SettingsScreen(
            signInButtonText = authenticationManager.signInButtonText,
            state = state,
            onSignInClick = { authenticationManager.onSignInClick() },
            onSampleRateChanged = { value ->
                Log.d(logTag, "onSampleRateChanged to $value")
                state.value.updateSampleRateTemp(value)
                state.value.validateSettings()
            },
            onBitDepthChanged = { value ->
                Log.d(logTag, "onBitDepthChanged to $value")
                state.value.updateBitDepthTemp(value)
                state.value.validateSettings()
            },
            onBufferTimeLengthChanged = { value ->
                Log.d(logTag, "onBufferTimeLengthChanged to $value")
                // Temporary value updater for error recompose
                state.value.updateBufferTimeLengthTemp(value)
                state.value.validateSettings()
            },
            onSubmit = {
                // Launch a coroutine for the save and restart logic
                scope.launch {
                    val configBeforeUpdate = settingsViewModel.config.value

                    // 1. Call updateSettings and get the list of jobs
                    val updateJobs = state.value.updateSettings(settingsViewModel)

                    // 2. Wait for all save operations to complete
                    updateJobs.joinAll()

                    // 3. Now the ViewModel's state is guaranteed to be updated
                    val configAfterUpdate = settingsViewModel.config.value

                    val service = RecentAudioBufferApplication.getSharedViewModel().myBufferService
                    val isSessionActive = service != null

                    if (configBeforeUpdate != configAfterUpdate && isSessionActive) {
                        Log.d(
                            logTag,
                            "onSubmit(): Settings changed, quicksaving and restarting recording"
                        )
                        // The service calls can be run on the main thread if they are fast,
                        // or you can ensure they are safe to call from this coroutine context.
                        service!!.stopRecording()
                        service.quickSaveBuffer()
                        service.resetBuffer()
                        service.startRecording()
                    } else if (configBeforeUpdate != configAfterUpdate) {
                        Log.w(
                            logTag,
                            "onSubmit(): Settings changed, but no recording session found"
                        )
                    } else {
                        Log.i(logTag, "onSubmit(): No settings changed")
                    }

                    hasSaved = true
                }
            },
            justExit = {
                this.finish()
            })
    }
}