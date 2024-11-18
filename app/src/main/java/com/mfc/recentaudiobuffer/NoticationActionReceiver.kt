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
    }

    override fun onReceive(context: Context, intent: Intent) {
        val sharedViewModel = ViewModelHolder.getSharedViewModel()
        val myBufferService = sharedViewModel.myBufferService

        when (intent.action) {
            ACTION_STOP_RECORDING -> {
                Log.d(logTag, "Received ACTION_STOP_RECORDING")
                myBufferService?.stopRecording()
                myBufferService?.resetBuffer()
            }

            ACTION_START_RECORDING -> {
                Log.d(logTag, "Received ACTION_START_RECORDING")
                myBufferService?.startRecording()
            }
        }
    }
}