package com.mfc.recentaudiobuffer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository, application: Application
) : AndroidViewModel(application) {
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _config = MutableStateFlow(SettingsConfig())
    val config: StateFlow<SettingsConfig> = _config.asStateFlow()

    init {
        Timber.d("SettingsViewModel init block started")
        viewModelScope.launch {
            refreshSettings()
        }
    }

    fun updateAreAdsEnabled(areAdsEnabled: Boolean) {
        viewModelScope.launch {
            _isSaving.value = true
            settingsRepository.updateAreAdsEnabled(areAdsEnabled)
            _config.update { it.copy(areAdsEnabled = areAdsEnabled) }
            _isSaving.value = false
        }
    }

    fun updateSampleRate(sampleRate: Int): Job {
        return viewModelScope.launch {
            // No change to the logic inside
            settingsRepository.updateSampleRate(sampleRate)
            _config.update { it.copy(sampleRateHz = sampleRate) }
        }
    }

    fun updateBufferTimeLength(bufferTimeLength: Int): Job {
        return viewModelScope.launch {
            settingsRepository.updateBufferTimeLength(bufferTimeLength)
            _config.update { it.copy(bufferTimeLengthS = bufferTimeLength) }
        }
    }

    fun updateBitDepth(bitDepth: BitDepth): Job {
        return viewModelScope.launch {
            settingsRepository.updateBitDepth(bitDepth)
            _config.update { it.copy(bitDepth = bitDepth) }
        }
    }

    suspend fun refreshSettings() {
        Timber.d("refreshSettings() called")
        _isSaving.value = true
        val newConfig = settingsRepository.getSettingsConfig()
        _config.update { newConfig.copy() }
        _isSaving.value = false
    }
}