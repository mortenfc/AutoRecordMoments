package com.mfc.recentaudiobuffer

import MyPhoneStateListener
import android.annotation.SuppressLint
import android.telecom.Call
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

object OngoingCall {
    private val _state = MutableStateFlow(Call.STATE_DISCONNECTED) // Start with disconnected
    val state: StateFlow<Int> = _state.asStateFlow()

    private var _callStartTime: Long = 0L
    private val callStartTime: Long get() = _callStartTime

    private var myInCallService: MyInCallService? = null
    @SuppressLint("StaticFieldLeak")
    private var myPhoneStateListener: MyPhoneStateListener? = null

    private const val USE_TELECOM: Boolean = false
    const val USE_TELEPHONY: Boolean = !USE_TELECOM

    var onTelephonyCallStarted: (() -> Unit)? = null

    fun setMyInCallService(service: MyInCallService) {
        myInCallService = service
    }

    fun setMyPhoneStateListener(listener: MyPhoneStateListener) {
        myPhoneStateListener = listener
        listener.onCallStateChanged = { state, number ->
            Timber.d("Phone state changed (listener): ${PhoneUtils.getTelephonyStateString(state)}, number: $number")
            updateState(state)
            phoneNumber = number
        }
    }

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
            if (USE_TELECOM) {
                _call?.registerCallback(callback)
                updateState(_call?.details?.state ?: Call.STATE_DISCONNECTED)
                if (_call != null) {
                    name = _call?.details?.contactDisplayName
                    phoneNumber = _call?.details?.handle?.schemeSpecificPart
                    _callStartTime = System.currentTimeMillis()
                }
            }
        }

    private fun updateState(newState: Int) {
        _state.value = newState
        if (!disconnectingCallScreenStates.contains(newState)) {
            restartCallDurationTracking()
        }
        if (newState == Call.STATE_ACTIVE) {
            startCallRecording()
            onTelephonyCallStarted?.invoke() // Only does something with telephony
        } else if (newState == Call.STATE_DISCONNECTED) {
            stopCallRecording()
        }
    }

    private fun updateTelephonyState(newState: Int) {
        _state.value = newState
        if (!disconnectingCallScreenStates.contains(newState)) {
            restartCallDurationTracking()
        }
        if (newState == Call.STATE_ACTIVE) {
            startCallRecording()
            onTelephonyCallStarted?.invoke() // Only does something with telephony
        } else if (newState == Call.STATE_DISCONNECTED) {
            stopCallRecording()
        }
    }

    val inCallScreenStates = setOf(
        Call.STATE_ACTIVE,
        Call.STATE_HOLDING,
        Call.STATE_AUDIO_PROCESSING,
        Call.STATE_SIMULATED_RINGING,
        Call.STATE_PULLING_CALL
    )

    val outgoingCallScreenStates = setOf(
        Call.STATE_DIALING, Call.STATE_CONNECTING
    )

    val incomingCallScreenStates = setOf(
        Call.STATE_RINGING
    )

    val disconnectingCallScreenStates = setOf(
        Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT
    )

    fun isInActiveState(): Boolean {
        return _state.value == Call.STATE_ACTIVE
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, newState: Int) {
            Timber.d("Call state changed (callback): ${PhoneUtils.getCallStateString(newState)}")
            updateState(newState)
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
        if (USE_TELECOM) {
            call?.disconnect()
        } else {
            myPhoneStateListener?.disconnect()
        }
    }

    fun toggleHold(on: Boolean) {
        Timber.d("toggleHold(): $on")
        if (USE_TELECOM) {
            if (on) {
                call?.hold()
            } else {
                call?.unhold()
            }
        }
    }

    fun toggleMute(on: Boolean) {
        Timber.d("toggleMute(): $on")
        myInCallService?.toggleMute(on)
    }

    fun toggleSpeaker(on: Boolean) {
        Timber.d("toggleSpeaker(): $on")
        myInCallService?.toggleSpeaker(on)
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
        myPhoneStateListener = null // Clear the listener
    }

    private fun startCallRecording() {
        Timber.d("startCallRecording()")
        RecentAudioBufferApplication.getSharedViewModel().myBufferService?.startCallRecording()
    }

    private fun stopCallRecording() {
        Timber.d("stopCallRecording()")
        RecentAudioBufferApplication.getSharedViewModel().myBufferService?.stopCallRecording()
    }
}