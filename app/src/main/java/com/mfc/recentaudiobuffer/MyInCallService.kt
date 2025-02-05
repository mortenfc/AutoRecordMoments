package com.mfc.recentaudiobuffer

import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

class MyInCallService : InCallService() {

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            Log.d("MyInCallService", "onStateChanged: $state")
            when (state) {
                Call.STATE_ACTIVE -> {
                    Log.d("MyInCallService", "Call is active")
                    // Use getApplication() to get the Application instance
                    val application = application
                    val sharedViewModel = RecentAudioBufferApplication.getSharedViewModel(application)
                    // Check for null before calling methods
                    sharedViewModel.myBufferService?.startCallRecording()
                }

                Call.STATE_DISCONNECTED -> {
                    Log.d("MyInCallService", "Call is disconnected")
                    val application = application
                    val sharedViewModel = RecentAudioBufferApplication.getSharedViewModel(application)
                    sharedViewModel.myBufferService?.stopCallRecording()
                    call.unregisterCallback(this)
                    // Optionally, remove the call from your internal list
                }
                Call.STATE_AUDIO_PROCESSING -> {
                    Log.d("MyInCallService", "STATE_AUDIO_PROCESSING")
                }
                Call.STATE_CONNECTING -> {
                    Log.d("MyInCallService", "Call is connecting")
                }
                Call.STATE_DIALING -> {
                    Log.d("MyInCallService", "Call is dialing")
                }
                Call.STATE_DISCONNECTING -> {
                    Log.d("MyInCallService", "Call is disconnecting")
                }
                Call.STATE_HOLDING -> {
                    Log.d("MyInCallService", "Call is holding")
                }
                Call.STATE_NEW -> {
                    Log.d("MyInCallService", "Call is new")
                }
                Call.STATE_PULLING_CALL -> {
                    Log.d("MyInCallService", "STATE_PULLING_CALL")
                }
                Call.STATE_RINGING -> {
                    Log.d("MyInCallService", "Call is ringing")
                }
                Call.STATE_SELECT_PHONE_ACCOUNT -> {
                    Log.d("MyInCallService", "Call is selecting phone account")
                }
                Call.STATE_SIMULATED_RINGING -> {
                    Log.d("MyInCallService", "STATE_SIMULATED_RINGING")
                }
            }
        }
    }

    override fun onCallAdded(call: Call) {
        Log.d("MyInCallService", "onCallAdded: ${call.details.handle}")
        call.registerCallback(callCallback)
    }

    override fun onCallRemoved(call: Call) {
        Log.d("MyInCallService", "onCallRemoved: ${call.details.handle}")
        call.unregisterCallback(callCallback)
    }
}