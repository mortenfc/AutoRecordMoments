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

    private val settingsUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i("SettingsActivity", "Settings Updated Broadcast Received")
            if (intent?.action == "SETTINGS_UPDATED") {
                // Settings have been updated.
                // We don't need to do anything here because the UI is already
                // observing the config StateFlow from the SettingsViewModel.
                // The UI will automatically update when the config changes.
            }
        }
    }

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
                    onBufferTimeLengthChanged = { value ->
                        Log.d(logTag, "onBufferTimeLengthChanged to $value")
                        settingsViewModel.updateBufferTimeLength(value)
                        sendSettingsUpdatedBroadcast()
                    },
                    onSubmit = {
                        Log.d(logTag, "onSubmit")
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

    override fun onResume() {
        super.onResume()
        val intentFilter = IntentFilter("SETTINGS_UPDATED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsUpdatedReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(settingsUpdatedReceiver, intentFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(settingsUpdatedReceiver)
    }
}