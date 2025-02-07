package com.mfc.recentaudiobuffer

import android.os.Bundle
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

class MyInCallService : InCallService() {
    private val TAG = "MyInCallService"

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d(TAG, "onCallAdded: $call")
        call.registerCallback(callCallback)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved: $call")
        call.unregisterCallback(callCallback)
    }

    override fun onConnectionEvent(call: Call, event: String, extras: Bundle?) {
        super.onConnectionEvent(call, event, extras)
        Log.d(TAG, "onConnectionEvent: $event")
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            Log.d(TAG, "onStateChanged: $state")
            when (state) {
                Call.STATE_ACTIVE -> {
                    Log.d(TAG, "Call is active")
                    // Use getApplication() to get the Application instance
                    val application = application
                    val sharedViewModel =
                        RecentAudioBufferApplication.getSharedViewModel(application)
                    // Check for null before calling methods
                    sharedViewModel.myBufferService?.startCallRecording()
                }

                Call.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Call is disconnected")
                    val application = application
                    val sharedViewModel =
                        RecentAudioBufferApplication.getSharedViewModel(application)
                    sharedViewModel.myBufferService?.stopCallRecording()
                    call.unregisterCallback(this)
                    // Optionally, remove the call from your internal list
                }

                Call.STATE_RINGING -> {
                    Log.d(TAG, "Call is ringing")
                }

                Call.STATE_DIALING -> {
                    Log.d(TAG, "Call is dialing")
                }

                Call.STATE_CONNECTING -> {
                    Log.d(TAG, "Call is connecting")
                }

                Call.STATE_HOLDING -> {
                    Log.d(TAG, "Call is holding")
                }

                Call.STATE_NEW -> {
                    Log.d(TAG, "Call is new")
                }

                Call.STATE_SELECT_PHONE_ACCOUNT -> {
                    Log.d(TAG, "Call is selecting phone account")
                }

                Call.STATE_DISCONNECTING -> {
                    Log.d(TAG, "Call is disconnecting")
                }

                Call.STATE_PULLING_CALL -> {
                    Log.d(TAG, "Call is pulling call")
                }

                Call.STATE_AUDIO_PROCESSING -> {
                    Log.d(TAG, "Call is processing audio")
                }

                Call.STATE_SIMULATED_RINGING -> {
                    Log.d(TAG, "Call is in simulated ringing state")
                }
            }
        }
    }
}