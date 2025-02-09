package com.mfc.recentaudiobuffer

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

class NotificationActionReceiver : BroadcastReceiver() {
    private val logTag = "NotificationActionReceiver"

    companion object {
        const val ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING"
        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"
        const val ACTION_SAVE_RECORDING = "ACTION_SAVE_RECORDING"
        const val ACTION_ANSWER_CALL = "ACTION_ANSWER_CALL"
        const val ACTION_REJECT_CALL = "ACTION_REJECT_CALL"
        const val EXTRA_CALL_HANDLE = "callHandle"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val myBufferService = RecentAudioBufferApplication.getSharedViewModel().myBufferService

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

            ACTION_ANSWER_CALL -> {
                handleAnswerCall(intent)
            }

            ACTION_REJECT_CALL -> {
                handleRejectCall(intent)
            }
        }
    }

    private fun handleAnswerCall(intent: Intent) {
        val callHandleString = intent.getStringExtra(EXTRA_CALL_HANDLE)
        if (callHandleString != null) {
            val callHandle = Uri.parse(callHandleString)
            val myInCallService = RecentAudioBufferApplication.getSharedViewModel().myInCallService
            myInCallService?.answerCall(callHandle)
        } else {
            Log.e(logTag, "No call handle found in intent extras")
        }
    }

    private fun handleRejectCall(intent: Intent) {
        val callHandleString = intent.getStringExtra(EXTRA_CALL_HANDLE)
        if (callHandleString != null) {
            val callHandle = Uri.parse(callHandleString)
            val myInCallService = RecentAudioBufferApplication.getSharedViewModel().myInCallService
            myInCallService?.rejectCall(callHandle)
        } else {
            Log.e(logTag, "No call handle found in intent extras")
        }
    }
}