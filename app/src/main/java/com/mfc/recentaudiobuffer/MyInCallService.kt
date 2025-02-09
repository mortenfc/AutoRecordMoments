package com.mfc.recentaudiobuffer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

interface MyInCallServiceInterface {
    fun answerCall(callHandle: Uri)
    fun rejectCall(callHandle: Uri)
    fun getCallStartTime(callHandle: Uri): Long?
}

class MyInCallService : InCallService(), MyInCallServiceInterface {
    private val logTag = "MyInCallService"
    private val callMap = ConcurrentHashMap<Uri, Call>()
    private val callStartTimeMap = ConcurrentHashMap<Uri, Long>()

    companion object {
        const val INCOMING_CALL_NOTIFICATION_ID = 2
        private const val INCOMING_CALL_NOTIFICATION_CHANNEL_ID = "incoming_call"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            INCOMING_CALL_NOTIFICATION_CHANNEL_ID,
            "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            setSound(
                ringtoneUri,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d(logTag, "onCallAdded: $call")
        call.registerCallback(callCallback)
        callMap[call.details.handle] = call
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(logTag, "onCallRemoved: $call")
        call.unregisterCallback(callCallback)
        callMap.remove(call.details.handle)
        callStartTimeMap.remove(call.details.handle)
    }

    private fun showIncomingCallUI(call: Call) {
        // Create an intent to launch your CallScreenActivity
        val fullScreenIntent = Intent(this, IncomingCallFullScreenActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            // Add call information to intent extras
            Log.d(logTag, "showIncomingCallUI(): Ringing phone number: ${call.details.handle.schemeSpecificPart}")
            putExtra("callerName", call.details.contactDisplayName ?: "Unknown")
            putExtra("phoneNumber", call.details.handle.schemeSpecificPart)
            putExtra("isIncomingCall", true)
            putExtra(NotificationActionReceiver.EXTRA_CALL_HANDLE, call.details.handle.toString())
        }
        val pendingFullScreenIntent = PendingIntent.getActivity(
            this,
            0,
            fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val answerPendingIntent = PendingIntent.getBroadcast(
            this, 4, Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_ANSWER_CALL
                putExtra(
                    NotificationActionReceiver.EXTRA_CALL_HANDLE, call.details.handle.toString()
                )
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val answerAction = NotificationCompat.Action.Builder(
            R.drawable.baseline_call_24, "Answer", answerPendingIntent
        ).build()

        val rejectPendingIntent = PendingIntent.getBroadcast(
            this, 5, Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_REJECT_CALL
                putExtra(
                    NotificationActionReceiver.EXTRA_CALL_HANDLE, call.details.handle.toString()
                )
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val rejectAction = NotificationCompat.Action.Builder(
            R.drawable.exo_icon_close, "Reject", rejectPendingIntent
        ).build()

        // Build the notification
        val notificationBuilder =
            NotificationCompat.Builder(this, INCOMING_CALL_NOTIFICATION_CHANNEL_ID).setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSmallIcon(R.drawable.baseline_call_received_24).setContentTitle("Incoming call")
                .setContentText("Incoming call from ${call.details.handle}")
                .setFullScreenIntent(pendingFullScreenIntent, true)
                .setContentIntent(pendingFullScreenIntent).addAction(answerAction)
                .addAction(rejectAction)

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(INCOMING_CALL_NOTIFICATION_ID, notificationBuilder.build())
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            Log.d(logTag, "onStateChanged: $state")
            when (state) {
                Call.STATE_RINGING -> {
                    Log.d(logTag, "Call is ringing")
                    showIncomingCallUI(call)
                }

                Call.STATE_ACTIVE -> {
                    callStartTimeMap[call.details.handle] = System.currentTimeMillis()
                    Log.d(logTag, "Call is active")
                    // Use getApplication() to get the Application instance
                    val sharedViewModel = RecentAudioBufferApplication.getSharedViewModel()
                    // Check for null before calling methods
                    sharedViewModel.myBufferService?.startCallRecording()
                }

                Call.STATE_DISCONNECTED -> {
                    Log.d(logTag, "Call is disconnected")
                    val sharedViewModel = RecentAudioBufferApplication.getSharedViewModel()
                    sharedViewModel.myBufferService?.stopCallRecording()
                    call.unregisterCallback(this)
                }

                Call.STATE_DIALING -> {
                    Log.d(logTag, "Call is dialing")
                }

                Call.STATE_CONNECTING -> {
                    Log.d(logTag, "Call is connecting")
                }

                Call.STATE_HOLDING -> {
                    Log.d(logTag, "Call is holding")
                }

                Call.STATE_NEW -> {
                    Log.d(logTag, "Call is new")
                }

                Call.STATE_SELECT_PHONE_ACCOUNT -> {
                    Log.d(logTag, "Call is selecting phone account")
                }

                Call.STATE_DISCONNECTING -> {
                    Log.d(logTag, "Call is disconnecting")
                }

                Call.STATE_PULLING_CALL -> {
                    Log.d(logTag, "Call is pulling call")
                }

                Call.STATE_AUDIO_PROCESSING -> {
                    Log.d(logTag, "Call is processing audio")
                }

                Call.STATE_SIMULATED_RINGING -> {
                    Log.d(logTag, "Call is in simulated ringing state")
                }
            }
        }
    }

    override fun getCallStartTime(callHandle: Uri): Long? {
        return callStartTimeMap[callHandle]
    }

    override fun answerCall(callHandle: Uri) {
        val call = callMap[callHandle]
        if (call != null) {
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
        } else {
            Log.w(logTag, "Call with handle $callHandle not found")
        }
    }

    override fun rejectCall(callHandle: Uri) {
        val call = callMap[callHandle]
        if (call != null) {
            call.reject(false, null)
        } else {
            Log.w(logTag, "Call with handle $callHandle not found")
        }
    }
}