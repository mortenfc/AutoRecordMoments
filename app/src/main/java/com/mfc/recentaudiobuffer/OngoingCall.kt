package com.mfc.recentaudiobuffer

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.telecom.Call
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

object OngoingCall {
    private val _state = MutableStateFlow(Call.STATE_NEW)
    val state: StateFlow<Int> = _state.asStateFlow()

    private var _callStartTime: Long = 0L
    private val callStartTime: Long get() = _callStartTime

    var name: String? = null
        private set

    var phoneNumber: String? = null
        private set

    private var _call: Call? = null
    var call: Call?
        get() = _call
        set(value) {
            _call?.unregisterCallback(callback)
            _call = value
            _call?.registerCallback(callback)
            _state.value = _call?.state ?: Call.STATE_NEW
            if (_call != null) {
                name = _call?.details?.contactDisplayName
                phoneNumber = _call?.details?.handle?.schemeSpecificPart
                _callStartTime = System.currentTimeMillis() // Moved here
            }
        }

    private lateinit var audioManager: AudioManager

    fun initAudioManager(context: Context) {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, newState: Int) {
            Timber.d("Call state changed: ${PhoneUtils.getCallStateString(newState)}")
            _state.value = newState
            if (newState == Call.STATE_ACTIVE || newState == Call.STATE_DIALING || newState == Call.STATE_CONNECTING || newState == Call.STATE_RINGING) {
                restartCallDurationTracking()
            }
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            Timber.d("Call details changed: $details")
            name = details.callerDisplayName
            phoneNumber = details.handle?.schemeSpecificPart
        }
    }

    fun answer() {
        call?.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
    }

    fun hangup() {
        call?.disconnect()
    }

    fun hold() {
        call?.hold()
    }

    fun mute() {
        val isCurrentlyMuted = audioManager.isMicrophoneMute
        audioManager.isMicrophoneMute = !isCurrentlyMuted
    }

    fun speakerphone() {
        val isSpeakerPhoneOn = audioManager.isSpeakerphoneOn
        audioManager.isSpeakerphoneOn = !isSpeakerPhoneOn
    }

    fun getCallDuration(): Long {
        return System.currentTimeMillis() - callStartTime
    }

    private fun restartCallDurationTracking() {
        _callStartTime = System.currentTimeMillis();
    }

    fun clear() {
        call = null
        _callStartTime = 0L
        name = null
        phoneNumber = null
    }
}