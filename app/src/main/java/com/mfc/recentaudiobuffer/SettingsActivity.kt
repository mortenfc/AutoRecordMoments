package com.mfc.recentaudiobuffer

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.firebase.auth.FirebaseAuth
import com.mfc.recentaudiobuffer.ui.theme.RecentAudioBufferTheme
import dagger.hilt.android.AndroidEntryPoint
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
            Log.d(logTag, "LaunchedEffect config: $config")
            state.value.updateBufferTimeLengthTemp(config.bufferTimeLengthS)
            state.value.validateSettings()
        }

        // Observe the isSaving state and finish the activity when saving is complete
        LaunchedEffect(isSaving, hasSaved) {
            Log.d(logTag, "LaunchedEffect isSaving, hasSaved: $isSaving, $hasSaved")
            if (!isSaving && hasSaved) {
                finish()
            }
        }

        SettingsScreen(signInButtonText = authenticationManager.signInButtonText,
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
                // Only state settings on submit
                state.value.updateSettings(settingsViewModel)
                hasSaved = true
            },
            justExit = {
                this.finish()
            }
        )
    }
}