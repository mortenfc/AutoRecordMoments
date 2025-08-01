/*
 * # Auto Record Moments
 * # Copyright (C) 2025 Morten Fjord Christensen
 * #
 * # This program is free software: you can redistribute it and/or modify
 * # it under the terms of the GNU Affero General Public License as published by
 * # the Free Software Foundation, either version 3 of the License, or
 * # (at your option) any later version.
 * #
 * # This program is distributed in the hope that it will be useful,
 * # but WITHOUT ANY WARRANTY; without even the implied warranty of
 * # MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * # GNU Affero General Public License for more details.
 * #
 * # You should have received a copy of the GNU Affero General Public License
 * # along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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

    fun updateBufferTimeLengthS(bufferTimeLength: Int): Job {
        return viewModelScope.launch {
            settingsRepository.updateBufferTimeLengthS(bufferTimeLength)
            _config.update { it.copy(bufferTimeLengthS = bufferTimeLength) }
        }
    }

    fun updateBitDepth(bitDepth: BitDepth): Job {
        return viewModelScope.launch {
            settingsRepository.updateBitDepth(bitDepth)
            _config.update { it.copy(bitDepth = bitDepth) }
        }
    }

    fun updateIsAiAutoClipEnabled(isEnabled: Boolean): Job {
        return viewModelScope.launch {
            settingsRepository.updateIsAiAutoClipEnabled(isEnabled) // We will add this function next
            _config.update { it.copy(isAiAutoClipEnabled = isEnabled) }
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