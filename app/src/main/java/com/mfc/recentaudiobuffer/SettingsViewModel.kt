package com.mfc.recentaudiobuffer

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    application: Application
) : AndroidViewModel(application) {
    private val logTag = "SettingsViewModel"

    val config: StateFlow<AudioConfig> = settingsRepository.config.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = AudioConfig(
            SAMPLE_RATE_HZ = sampleRates["22050"] ?: error("Invalid sample rate"),
            BUFFER_TIME_LENGTH_S = 120,
            BIT_DEPTH = bitDepths["8"] ?: error("Invalid bit depth")
        )
    )

    val areAdsEnabled: StateFlow<Boolean> = settingsRepository.areAdsEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = true
    )

    fun updateAreAdsEnabled(areAdsEnabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAreAdsEnabled(areAdsEnabled)
        }
    }

    fun updateSampleRate(sampleRate: Int) {
        viewModelScope.launch {
            settingsRepository.updateSampleRate(sampleRate)
        }
    }

    fun updateBufferTimeLength(bufferTimeLength: Int) {
        viewModelScope.launch {
            settingsRepository.updateBufferTimeLength(bufferTimeLength)
        }
    }

    fun updateBitDepth(bitDepth: BitDepth) {
        viewModelScope.launch {
            settingsRepository.updateBitDepth(bitDepth)
        }
    }

//    fun updateIsUserLoggedIn(isLoggedIn: Boolean) {
//        Log.d("SettingsViewModel", "updateIsUserLoggedIn to $isLoggedIn")
//        _isUserLoggedIn.value = isLoggedIn
//    }
}