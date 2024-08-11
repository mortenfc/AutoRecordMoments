package com.mfc.recentaudiobuffer

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.stateIn

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val repository: SettingsRepository by lazy { SettingsRepository(context.dataStore) }
    val config: StateFlow<AudioConfig> = repository.config.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AudioConfig(
            SAMPLE_RATE_HZ = sampleRates["22050"] ?: error("Invalid sample rate"),
            BUFFER_TIME_LENGTH_S = 120,
            BIT_DEPTH = bitDepths["8"] ?: error("Invalid bit depth")
        )
    )

    fun updateSampleRate(sampleRate: Int) {
        viewModelScope.launch {
            repository.updateSampleRate(sampleRate)
        }
    }

    fun updateBufferTimeLength(bufferTimeLength: Int) {
        viewModelScope.launch {
            repository.updateBufferTimeLength(bufferTimeLength)
        }
    }

    fun updateBitDepth(bitDepth: BitDepth) {
        viewModelScope.launch {
            repository.updateBitDepth(bitDepth)
        }
    }
}