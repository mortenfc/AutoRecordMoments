package com.mfc.recentaudiobuffer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class NotificationActionReceiver : BroadcastReceiver() {
    private val logTag = "NotificationActionReceiver"

    companion object {
        const val ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING"
        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"
        const val ACTION_SAVE_RECORDING = "ACTION_SAVE_RECORDING"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val myBufferService = RecentAudioBufferApplication.getSharedViewModel().myBufferService

        when (intent.action) {
            ACTION_STOP_RECORDING -> {
                Log.d(logTag, "Received ACTION_STOP_RECORDING")
                if (myBufferService != null) {
                    myBufferService.stopRecording()
                } else {
                    startService(context, MyBufferService.ACTION_STOP_RECORDING_SERVICE)
                }
            }

            ACTION_START_RECORDING -> {
                Log.d(logTag, "Received ACTION_START_RECORDING")
                if (myBufferService != null) {
                    myBufferService.startRecording()
                } else {
                    startService(context, MyBufferService.ACTION_START_RECORDING_SERVICE)
                }
            }

            ACTION_SAVE_RECORDING -> {
                Log.d(logTag, "Received ACTION_SAVE_RECORDING")
                if (myBufferService != null) {
                    myBufferService.quickSaveBuffer()
                    myBufferService.resetBuffer()
                } else {
                    startService(context, MyBufferService.ACTION_SAVE_RECORDING_SERVICE)
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