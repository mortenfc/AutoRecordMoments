package com.mfc.recentaudiobuffer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.util.Log

class SettingsActivity : ComponentActivity() {
    val logTag = "SettingsActivity"

    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val config by settingsViewModel.config.collectAsState()
                SettingsScreen(
                    config = config,
                    onSampleRateChanged = { value ->
                        Log.d(logTag, "onSampleRateChanged to $value")
                        settingsViewModel.updateSampleRate(value)
                        sendSettingsUpdatedBroadcast()
                    },
                    onBitDepthChanged = { value ->
                        Log.d(logTag, "onBitDepthChanged to $value")
                        settingsViewModel.updateBitDepth(value)
                        sendSettingsUpdatedBroadcast()
                    },
                    onSubmit = { value ->
                        Log.d(logTag, "onSubmit onBufferTimeLengthChanged to $value")
                        settingsViewModel.updateBufferTimeLength(value)
                        sendSettingsUpdatedBroadcast()
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