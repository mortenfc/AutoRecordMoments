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
                SettingsScreenView()
            }
        }
    }

    override fun onStart() {
        Log.i(logTag, "onStart() called")
        super.onStart()
        authenticationManager.registerLauncher(this)
    }

    private fun sendSettingsUpdatedBroadcast() {
        val intent = Intent(this, MyBufferService::class.java)
        intent.action = "com.mfc.recentaudiobuffer.SETTINGS_UPDATED"
        sendBroadcast(intent)
    }

    @SuppressLint("UnrememberedMutableState")
    @androidx.compose.runtime.Composable
    fun SettingsScreenView(settingsViewModel: SettingsViewModel = hiltViewModel()) {
        val config by settingsViewModel.config.collectAsState()
        val auth = FirebaseAuth.getInstance()
        val state = remember { SettingsScreenState(config) }
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
            state.updateBufferTimeLengthTemp(config.bufferTimeLengthS)
        }

        // Observe the isSaving state and finish the activity when saving is complete
        LaunchedEffect(isSaving, hasSaved) {
            Log.d(logTag, "LaunchedEffect isSaving, hasSaved: $isSaving, $hasSaved")
            if (!isSaving && hasSaved) {
                finish()
            }
        }

        SettingsScreen(signInButtonText = authenticationManager.signInButtonText,
            onSignInClick = { authenticationManager.onSignInClick() },
            sampleRate = config.sampleRateHz,
            bitDepth = config.bitDepth,
            bufferTimeLengthTemp = state.bufferTimeLengthTemp,
            isMaxExceeded = mutableStateOf(state.isMaxExceeded),
            isBufferTimeLengthNull = mutableStateOf(state.isBufferTimeLengthNull),
            errorMessage = mutableStateOf(state.errorMessage),
            isSubmitEnabled = mutableStateOf(state.isSubmitEnabled),
            onSampleRateChanged = { value ->
                Log.d(logTag, "onSampleRateChanged to $value")
                settingsViewModel.updateSampleRate(value)
                state.validateSettings(config.copy(sampleRateHz = value))
                sendSettingsUpdatedBroadcast()
            },
            onBitDepthChanged = { value ->
                Log.d(logTag, "onBitDepthChanged to $value")
                settingsViewModel.updateBitDepth(value)
                state.validateSettings(config.copy(bitDepth = value))
                sendSettingsUpdatedBroadcast()
            },
            onBufferTimeLengthChanged = { value ->
                Log.d(logTag, "onBufferTimeLengthChanged to $value")
                // Temporary value updater for error recompose
                state.updateBufferTimeLengthTemp(value)
                state.validateSettings(config)
            },
            onSubmit = { value ->
                Log.d(logTag, "onSubmit onBufferTimeLengthChanged to $value")
                // Only persist bufferTimeLength value on submit
                settingsViewModel.updateBufferTimeLength(value)
                sendSettingsUpdatedBroadcast()
                hasSaved = true
            },
            justExit = {
                this.finish()
            },
            config = config
        )
    }
}