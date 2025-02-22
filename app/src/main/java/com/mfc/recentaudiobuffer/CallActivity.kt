package com.mfc.recentaudiobuffer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

class CallActivity : ComponentActivity() {
    private var callerName by mutableStateOf<String?>(null)
    private var phoneNumber by mutableStateOf<String?>(null)
    private var callHandle by mutableStateOf<Uri?>(null)
    private var connectionState by mutableIntStateOf(Call.STATE_NEW)
    private var callDuration by mutableLongStateOf(0L)

    private var connectionStateJob: Job? = null
    private var ongoingCallStateJob: Job? = null
    private var callDurationJob: Job? = null

    companion object {
        private const val INCOMING_CALL_NOTIFICATION_CHANNEL_ID = "incoming_call"
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            INCOMING_CALL_NOTIFICATION_CHANNEL_ID,
            "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        handleIntent(intent)

        setContent {
            // React on connection changes
            LaunchedEffect(key1 = Unit) {
                connectionStateJob = launch {
                    OngoingCall.state.collectLatest { newState ->
                        connectionState = newState
                    }
                }
            }

            // Update the call duration
            LaunchedEffect(key1 = connectionState) {
                if (connectionState == Call.STATE_ACTIVE || connectionState == Call.STATE_DIALING || connectionState == Call.STATE_CONNECTING || connectionState == Call.STATE_RINGING) {
                    if (callHandle != null) {
                        Timber.d("LaunchedEffect: Starting duration updates")
                        callDurationJob = launch {
                            while (connectionState == Call.STATE_ACTIVE || connectionState == Call.STATE_DIALING || connectionState == Call.STATE_CONNECTING || connectionState == Call.STATE_RINGING) {
                                callDuration = OngoingCall.getCallDuration()
                                Timber.d("LaunchedEffect: Duration updated")
                                delay(1000)
                            }
                        }
                    }
                } else {
                    callDurationJob?.cancel()
                }
            }

            // Update the caller name and phone number
            LaunchedEffect(key1 = OngoingCall.name, key2 = OngoingCall.phoneNumber) {
                callerName = OngoingCall.name
                phoneNumber = OngoingCall.phoneNumber
            }

            when (connectionState) {
                Call.STATE_ACTIVE -> {
                    InCallScreen(name = callerName ?: "Unknown",
                        phoneNumber = phoneNumber ?: "Unknown",
                        callDuration = callDuration,
                        onMute = { value -> OngoingCall.toggleMute(value) },
                        onSpeakerphone = { value -> OngoingCall.toggleSpeaker(value) },
                        onHold = { value -> OngoingCall.toggleHold(value) },
                        onEndCall = { OngoingCall.hangup() })
                }

                Call.STATE_RINGING -> {
                    IncomingCallScreen(
                        callerName = callerName ?: "Unknown",
                        phoneNumber = phoneNumber ?: "Unknown",
                        callDuration = callDuration,
                        onAnswer = {
                            OngoingCall.answer()
                        },
                        onReject = {
                            OngoingCall.hangup()
                        },
                    )
                }

                Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                    OutgoingCallScreen(name = callerName ?: "Unknown",
                        phoneNumber = phoneNumber ?: "Unknown",
                        callDuration = callDuration,
                        onEndCall = {
                            OngoingCall.hangup()
                        })
                }

                else -> {
                    // Handle other states or show a default screen
                    Text("Call State: $connectionState")
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        handleIntent(intent)

        ongoingCallStateJob = lifecycleScope.launch {
            OngoingCall.state.collectLatest { state ->
                Timber.d("OngoingCall state changed: $state")
                if (state == Call.STATE_DISCONNECTED) {
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionStateJob?.cancel()
        ongoingCallStateJob?.cancel()
        callDurationJob?.cancel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        callerName = if (intent.hasExtra("callerName")) {
            intent.getStringExtra("callerName")
        } else {
            OngoingCall.name
        }

        phoneNumber = if (intent.hasExtra("phoneNumber")) {
            intent.getStringExtra("phoneNumber")
        } else {
            OngoingCall.phoneNumber
        }

        callHandle = if (phoneNumber != null) {
            Uri.fromParts("tel", phoneNumber, null)
        } else {
            OngoingCall.call?.details?.handle
        }
    }
}