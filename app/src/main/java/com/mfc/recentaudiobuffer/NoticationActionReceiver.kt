package com.mfc.recentaudiobuffer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class NotificationActionReceiver : BroadcastReceiver() {
    private val logTag = "NotificationActionReceiver"

    companion object {
        const val ACTION_STOP_RECORDING = "com.example.app.ACTION_STOP_RECORDING"
        const val ACTION_START_RECORDING = "com.example.app.ACTION_START_RECORDING"
        const val ACTION_SAVE_RECORDING = "com.example.app.ACTION_SAVE_RECORDING"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val sharedViewModel = ViewModelHolder.getSharedViewModel()
        val myBufferService = sharedViewModel.myBufferService

        when (intent.action) {
            ACTION_STOP_RECORDING -> {
                Log.d(logTag, "Received ACTION_STOP_RECORDING")
                myBufferService?.stopRecording()
            }

            ACTION_START_RECORDING -> {
                Log.d(logTag, "Received ACTION_START_RECORDING")
                myBufferService?.startRecording()
            }

            ACTION_SAVE_RECORDING -> {
                val grantedUri = FileSavingUtils.getCachedGrantedUri(context)
                if (grantedUri != null) {
                    // Use previously permitted cached uri
                    val saveIntent = Intent(context, FileSavingService::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("grantedUri", grantedUri)
                        .putExtra("audioData", myBufferService?.getBuffer())
                    context.startService(saveIntent)
                } else {
                    // Otherwise get file saving location permission
                    // You might need to launch an activity to request permission here
                    // or handle this case differently based on your app's flow
                    // ...
                }
            }
        }
    }
}