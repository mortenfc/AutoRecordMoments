package com.mfc.recentaudiobuffer

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository, application: Application
) : AndroidViewModel(application) {
    private val logTag = "SettingsViewModel"
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _config = MutableStateFlow(runBlocking {
        Log.d(logTag, "Recreated _config from getSettingsConfig()")
        settingsRepository.getSettingsConfig()
    })
    val config: StateFlow<SettingsConfig> = _config.asStateFlow()

    init {
        Log.d(logTag, "SettingsViewModel init block started")
        viewModelScope.launch {
            Log.d(logTag, "viewModelScope init block started")
            settingsRepository.syncSettings()
            refreshSettings()
            Log.d(logTag, "viewModelScope init block finished")
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

    fun updateSampleRate(sampleRate: Int) {
        viewModelScope.launch {
            _isSaving.value = true
            settingsRepository.updateSampleRate(sampleRate)
            _config.update { it.copy(sampleRateHz = sampleRate) }
            _isSaving.value = false
        }
    }

    fun updateBufferTimeLength(bufferTimeLength: Int) {
        viewModelScope.launch {
            _isSaving.value = true
            settingsRepository.updateBufferTimeLength(bufferTimeLength)
            _config.update { it.copy(bufferTimeLengthS = bufferTimeLength) }
            _isSaving.value = false
        }
    }

    fun updateBitDepth(bitDepth: BitDepth) {
        viewModelScope.launch {
            _isSaving.value = true
            settingsRepository.updateBitDepth(bitDepth)
            _config.update { it.copy(bitDepth = bitDepth) }
            _isSaving.value = false
        }
    }

    suspend fun refreshSettings() {
        Log.d(logTag, "refreshSettings() called")
        _isSaving.value = true
        val newConfig = settingsRepository.getSettingsConfig()
        _config.update { newConfig.copy() }
        _isSaving.value = false
    }
}