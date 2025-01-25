package com.mfc.recentaudiobuffer

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

class SettingsActivity : ComponentActivity() {
    private val logTag = "SettingsActivity"

    private val settingsViewModel: SettingsViewModel by viewModels()

    @SuppressLint("UnrememberedMutableState")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val config by settingsViewModel.config.collectAsState()
                val state = remember { SettingsScreenState(config) }

                // Observe changes to config and update bufferTimeLengthTemp.
                // Config changes lazily so this is needed
                LaunchedEffect(config) {
                    state.updateBufferTimeLengthTemp(config.BUFFER_TIME_LENGTH_S)
                }

                SettingsScreen(
                    sampleRate = config.SAMPLE_RATE_HZ,
                    bitDepth = config.BIT_DEPTH,
                    bufferTimeLengthTemp = state.bufferTimeLengthTemp,
                    isMaxExceeded = mutableStateOf(state.isMaxExceeded),
                    isBufferTimeLengthNull = mutableStateOf(state.isBufferTimeLengthNull),
                    errorMessage = mutableStateOf(state.errorMessage),
                    isSubmitEnabled = mutableStateOf(state.isSubmitEnabled),
                    onSampleRateChanged = { value ->
                        Log.d(logTag, "onSampleRateChanged to $value")
                        settingsViewModel.updateSampleRate(value)
                        state.validateSettings(config.copy(SAMPLE_RATE_HZ = value))
                        sendSettingsUpdatedBroadcast()
                    },
                    onBitDepthChanged = { value ->
                        Log.d(logTag, "onBitDepthChanged to $value")
                        settingsViewModel.updateBitDepth(value)
                        state.validateSettings(config.copy(BIT_DEPTH = value))
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
                        this.finish()
                    },
                    justExit = {
                        this.finish()
                    }
                )
            }
        }
    }

    private fun sendSettingsUpdatedBroadcast() {
        val intent = Intent(this, MyBufferService::class.java)
        intent.action = "com.mfc.recentaudiobuffer.SETTINGS_UPDATED"
        sendBroadcast(intent)
    }
}