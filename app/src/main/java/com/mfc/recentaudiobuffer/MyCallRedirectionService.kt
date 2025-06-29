package com.mfc.recentaudiobuffer

import android.content.Intent
import android.net.Uri
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import timber.log.Timber

class MyCallRedirectionService : CallRedirectionService() {
    override fun onPlaceCall(
        handle: Uri,
        initialPhoneAccount: PhoneAccountHandle,
        allowInteractiveResponse: Boolean
    ) {
        Timber.d("onPlaceCall: handle=$handle, initialPhoneAccount=$initialPhoneAccount, allowInteractiveResponse=$allowInteractiveResponse")
        try {
            // Let the call proceed as normal
            placeCallUnmodified()

            // Start recording here
            val intent = Intent(this, MyBufferService::class.java)
            intent.action = MyBufferService.ACTION_START_RECORDING_SERVICE
            startForegroundService(intent)
        } catch (e: Exception) {
            Timber.e("Exception: ${e.message}")
        }
    }
}