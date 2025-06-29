package com.mfc.recentaudiobuffer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.telecom.Call
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import timber.log.Timber
import android.provider.CallLog
import android.provider.ContactsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.Manifest
import android.telephony.TelephonyManager

object PhoneUtils {
    fun isDefaultDialer(context: Context, telecomManager: TelecomManager?): Boolean {
        return if (telecomManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                telecomManager.defaultDialerPackage == context.packageName
            } else {
                val intent = Intent(Intent.ACTION_DIAL)
                val componentName = intent.resolveActivity(context.packageManager)
                componentName?.packageName == context.packageName
            }
        } else {
            false // Not relevant when using TelephonyManager directly
        }
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

    fun getTelephonyStateString(state: Int): String {
        return when (state) {
            TelephonyManager.CALL_STATE_IDLE -> "CALL_STATE_IDLE"
            TelephonyManager.CALL_STATE_RINGING -> "CALL_STATE_RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "CALL_STATE_OFFHOOK"
            else -> "STATE_UNKNOWN"
        }
    }

    fun getCallLog(context: Context): List<CallLogEntry> {
        val callLogEntries = mutableListOf<CallLogEntry>()
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("CallScreen", "READ_CALL_LOG permission not granted")
            return callLogEntries
        }
        val projection = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION
        )
        val cursor: Cursor? = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, projection, null, null, CallLog.Calls.DATE + " DESC"
        )
        cursor?.use {
            val numberIndex = it.getColumnIndex(CallLog.Calls.NUMBER)
            val dateIndex = it.getColumnIndex(CallLog.Calls.DATE)
            val typeIndex = it.getColumnIndex(CallLog.Calls.TYPE)
            val durationIndex = it.getColumnIndex(CallLog.Calls.DURATION)

            while (it.moveToNext()) {
                val number = it.getString(numberIndex)
                val name = getContactName(context, number)
                val date = it.getLong(dateIndex)
                val type = it.getInt(typeIndex)
                val duration = it.getString(durationIndex)

                val formattedDate =
                    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(date))
                val callType = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> "Incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                    CallLog.Calls.MISSED_TYPE -> "Missed"
                    else -> "Unknown"
                }
                callLogEntries.add(CallLogEntry(number, name, formattedDate, callType, duration))
            }
        }
        return callLogEntries
    }

    fun getContactName(context: Context, phoneNumber: String?): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        var contactName: String? = null
        val cursor = context.contentResolver.query(uri, projection, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                contactName = it.getString(nameIndex)
            }
        }
        return contactName
    }
}