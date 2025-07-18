package com.mfc.recentaudiobuffer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING"
        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"
        const val ACTION_SAVE_RECORDING = "ACTION_SAVE_RECORDING"
    }

    @OptIn(UnstableApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val myBufferService = RecentAudioBufferApplication.getSharedViewModel().myBufferService

        when (intent.action) {
            ACTION_STOP_RECORDING -> {
                Timber.d("Received ACTION_STOP_RECORDING")
                if (myBufferService != null) {
                    myBufferService.stopRecording()
                } else {
                    startService(context, MyBufferService.ACTION_STOP_RECORDING_SERVICE)
                }
            }

            ACTION_START_RECORDING -> {
                Timber.d("Received ACTION_START_RECORDING")
                if (myBufferService != null) {
                    myBufferService.startRecording()
                } else {
                    startService(context, MyBufferService.ACTION_START_RECORDING_SERVICE)
                }
            }

            ACTION_SAVE_RECORDING -> {
                Timber.d("Received ACTION_SAVE_RECORDING")
                val grantedUri = FileSavingUtils.getCachedGrantedUri()

                if (FileSavingUtils.isUriValidAndAccessible(context, grantedUri)) {
                    // Permission exists, proceed with saving as before
                    Timber.d("Valid URI permission found. Proceeding with save.")
                    if (myBufferService != null) {
                        val pendingResult = goAsync()
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                myBufferService.quickSaveBuffer()
                            } finally {
                                pendingResult.finish()
                            }
                        }
                    } else {
                        startService(context, MyBufferService.ACTION_SAVE_RECORDING_SERVICE)
                    }
                } else {
                    // Permission does NOT exist, launch MainActivity to request it
                    Timber.d("No valid URI permission. Launching MainActivity to request it.")
                    val mainActivityIntent = Intent(context, MainActivity::class.java).apply {
                        action = MainActivity.ACTION_REQUEST_DIRECTORY_PERMISSION
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    context.startActivity(mainActivityIntent)
                }
            }
        }
    }

    private fun startService(context: Context, action: String) {
        val serviceIntent = Intent(context, MyBufferService::class.java)
        serviceIntent.action = action
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}