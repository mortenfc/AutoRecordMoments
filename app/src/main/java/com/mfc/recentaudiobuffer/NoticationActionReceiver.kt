package com.mfc.recentaudiobuffer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_RECORDING -> {
                Timber.d("Received ACTION_STOP_RECORDING")
                startService(context, MyBufferService.ACTION_STOP_RECORDING_SERVICE)
            }

            ACTION_START_RECORDING -> {
                Timber.d("Received ACTION_START_RECORDING")
                startService(context, MyBufferService.ACTION_START_RECORDING_SERVICE)
            }

            ACTION_SAVE_RECORDING -> {
                Timber.d("Received ACTION_SAVE_RECORDING")
                val grantedUri = FileSavingUtils.getCachedGrantedUri(context)

                if (FileSavingUtils.isUriValidAndAccessible(context, grantedUri)) {
                    Timber.d("Valid URI permission found. Proceeding with quick save.")
                    // The service needs to be running to save, so we just send an intent.
                    // The service will then handle the entire save process.
                    startService(context, MyBufferService.ACTION_SAVE_RECORDING_SERVICE)
                } else {
                    // Permission does NOT exist, launch MainActivity to request it.
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
        val serviceIntent = Intent(context, MyBufferService::class.java).apply {
            this.action = action
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
