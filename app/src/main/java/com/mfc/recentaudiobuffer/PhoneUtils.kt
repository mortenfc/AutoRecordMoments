package com.mfc.recentaudiobuffer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.Call
import android.telecom.TelecomManager
import timber.log.Timber

object PhoneUtils {
    fun placeCall(
        phoneNumber: String, context: Context
    ) {
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.e("Exception: ${e.message}")
        }
    }

    fun isDefaultDialer(context: Context, telecomManager: TelecomManager): Boolean {
        val isDefault = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val result = telecomManager.defaultDialerPackage == context.packageName
            Timber.tag("isDefaultDialer")
                .d("Q and above: defaultDialerPackage = ${telecomManager.defaultDialerPackage}, packageName = ${context.packageName}, result = $result")
            result
        } else {
            val intent = Intent(Intent.ACTION_DIAL)
            val componentName = intent.resolveActivity(context.packageManager)
            val result = componentName?.packageName == context.packageName
            Timber.tag("isDefaultDialer")
                .d("Pre-Q: componentName = $componentName, packageName = ${context.packageName}, result = $result")
            result
        }
        return isDefault
    }

    fun getCallStateString(state: Int): String {
        return when (state) {
            Call.STATE_NEW -> "STATE_NEW"
            Call.STATE_RINGING -> "STATE_RINGING"
            Call.STATE_DIALING -> "STATE_DIALING"
            Call.STATE_CONNECTING -> "STATE_CONNECTING"
            Call.STATE_ACTIVE -> "STATE_ACTIVE"
            Call.STATE_HOLDING -> "STATE_HOLDING"
            Call.STATE_DISCONNECTED -> "STATE_DISCONNECTED"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "STATE_SELECT_PHONE_ACCOUNT"
            Call.STATE_PULLING_CALL -> "STATE_PULLING_CALL"
            Call.STATE_AUDIO_PROCESSING -> "STATE_AUDIO_PROCESSING"
            Call.STATE_SIMULATED_RINGING -> "STATE_SIMULATED_RINGING"
            Call.STATE_DISCONNECTING -> "STATE_DISCONNECTING"
            else -> "STATE_UNKNOWN"
        }
    }
}