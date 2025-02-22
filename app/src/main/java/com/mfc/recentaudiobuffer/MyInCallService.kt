package com.mfc.recentaudiobuffer

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import timber.log.Timber

class MyInCallService : InCallService() {
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Timber.d("onCallAdded: $call")
        OngoingCall.call = call

        // For outgoing calls, this is invoked after ConnectionService::onCreateOutgoingConnection
        val intent = Intent(this, CallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Timber.d("onCallRemoved: $call")
        if (OngoingCall.call == call) {
            OngoingCall.clear()
        }
    }
}