package com.mfc.recentaudiobuffer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

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
                if (myBufferService != null) {
                    myBufferService.stopRecording()
                } else {
                    val serviceIntent = Intent(context, MyBufferService::class.java)
                    serviceIntent.action = MyBufferService.ACTION_STOP_RECORDING_SERVICE
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            }

            ACTION_START_RECORDING -> {
                Log.d(logTag, "Received ACTION_START_RECORDING")
                if (myBufferService != null) {
                    myBufferService.startRecording()
                } else {
                    val serviceIntent = Intent(context, MyBufferService::class.java)
                    serviceIntent.action = MyBufferService.ACTION_START_RECORDING_SERVICE
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            }

            ACTION_SAVE_RECORDING -> {
                Log.d(logTag, "Received ACTION_START_RECORDING")
                if (myBufferService != null) {
                    myBufferService.quickSaveBuffer()
                    myBufferService.resetBuffer()
                } else {
                    val serviceIntent = Intent(context, MyBufferService::class.java)
                    serviceIntent.action = MyBufferService.ACTION_START_RECORDING_SERVICE
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            }
        }
    }
}