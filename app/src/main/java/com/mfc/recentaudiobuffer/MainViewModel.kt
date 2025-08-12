/*
 * Auto Record Moments
 * Copyright (C) 2025 Morten Fjord Christensen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.mfc.recentaudiobuffer

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject


// --- ViewModel and State classes to manage player state across configuration changes ---

/**
 * Data class representing the UI state of the media player.
 * It's kept separate from the Activity/Composable to survive configuration changes.
 */
data class PlayerUiState(
    val isVisible: Boolean = false, val currentUri: Uri? = null, val currentFileName: String = ""
)

data class SaveDialogState(
    val processedAudioData: ByteArray, val audioConfig: AudioConfig, val suggestedFileName: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SaveDialogState

        if (!processedAudioData.contentEquals(other.processedAudioData)) return false
        if (audioConfig != other.audioConfig) return false
        if (suggestedFileName != other.suggestedFileName) return false

        return true
    }

    override fun hashCode(): Int {
        var result = processedAudioData.contentHashCode()
        result = 31 * result + audioConfig.hashCode()
        result = 31 * result + suggestedFileName.hashCode()
        return result
    }
}

// Helper sealed class for one-time events
sealed class UiEvent {
    data class ShowToast(val message: String) : UiEvent()
    object RequestDirectoryPicker : UiEvent()
}

/**
 * ViewModel to hold the MediaPlayerManager and its UI state.
 * This ensures the player survives screen rotations and other configuration changes.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val application: Application,
    private val vadProcessor: VADProcessor,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // --- UI State Management ---
    private val _serviceError = MutableStateFlow<String?>(null)
    val serviceError = _serviceError.asStateFlow()
    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()
    private val _showRecentFilesDialog = MutableStateFlow(false)
    val showRecentFilesDialog = _showRecentFilesDialog.asStateFlow()
    private val _showTrimFileDialog = MutableStateFlow(false)
    val showTrimFileDialog = _showTrimFileDialog.asStateFlow()
    private val _showDirectoryPermissionDialog = MutableStateFlow(false)
    val showDirectoryPermissionDialog = _showDirectoryPermissionDialog.asStateFlow()
    private val _hasDonated = MutableStateFlow(false)
    val hasDonated = _hasDonated.asStateFlow()
    private val _wasStartRecordingButtonPress = MutableStateFlow(false)
    val wasStartRecordingButtonPress = _wasStartRecordingButtonPress.asStateFlow()
    private val _isRewardActive = MutableStateFlow(false)
    val isRewardActive = _isRewardActive.asStateFlow()
    private val _rewardExpiryTimestamp = MutableStateFlow(0L)
    val rewardExpiryTimestamp = _rewardExpiryTimestamp.asStateFlow()

    fun setServiceError(error: String?) {
        _serviceError.value = error
    }

    fun setIsRecording(recording: Boolean) {
        _isRecording.value = recording
    }

    fun setShowRecentFilesDialog(show: Boolean) {
        _showRecentFilesDialog.value = show
    }

    fun setShowTrimFileDialog(show: Boolean) {
        _showTrimFileDialog.value = show
    }

    fun setShowDirectoryPermissionDialog(show: Boolean) {
        _showDirectoryPermissionDialog.value = show
    }

    fun setShowLockscreenInfoDialog(show: Boolean) {
        _showLockscreenInfoDialog.value = show
    }

    fun setHasDonated(donated: Boolean) {
        _hasDonated.value = donated
    }

    fun setWasStartRecordingButtonPress(pressed: Boolean) {
        _wasStartRecordingButtonPress.value = pressed
    }

    fun setIsRewardActive(active: Boolean) {
        _isRewardActive.value = active
    }

    fun setRewardExpiryTimestamp(timestamp: Long) {
        _rewardExpiryTimestamp.value = timestamp
    }

    fun setShowPrivacyInfoDialog(value: Boolean) {
        _showPrivacyInfoDialog.value = value
    }

    fun setShowBatteryInfoDialog(value: Boolean) {
        _showBatteryInfoDialog.value = value
    }

    fun updateRewardState(interstitialAdManager: InterstitialAdManager) {
        setRewardExpiryTimestamp(interstitialAdManager.getRewardExpiryTimestamp())
        setIsRewardActive(System.currentTimeMillis() < rewardExpiryTimestamp.value)
    }

    private val _showPrivacyInfoDialog = MutableStateFlow(false)
    val showPrivacyInfoDialog = _showPrivacyInfoDialog.asStateFlow()

    private val _showLockscreenInfoDialog = MutableStateFlow(false)
    val showLockscreenInfoDialog = _showLockscreenInfoDialog.asStateFlow()

    private val _showBatteryInfoDialog = MutableStateFlow(false)
    val showBatteryInfoDialog = _showBatteryInfoDialog.asStateFlow()

    fun onPrivacyDialogDismissed() {
        _showPrivacyInfoDialog.value = false
        _showLockscreenInfoDialog.value = true // Immediately show the next dialog
    }

    fun onLockscreenDialogDismissed() {
        _showLockscreenInfoDialog.value = false
        _showBatteryInfoDialog.value = true // Chain to the next dialog
    }

    private val _playerUiState = MutableStateFlow(PlayerUiState())
    val playerUiState: StateFlow<PlayerUiState> = _playerUiState.asStateFlow()

    // The MediaPlayerManager is now owned by the ViewModel.
    val mediaPlayerManager: MediaPlayerManager = MediaPlayerManager(application) { uri, fileName ->
        viewModelScope.launch {
            _playerUiState.update {
                it.copy(
                    isVisible = true, currentUri = uri, currentFileName = fileName
                )
            }
        }
    }

    fun requestDirectoryPicker()
    {
        _uiEvents.value = UiEvent.RequestDirectoryPicker
    }

    private val _saveDialogState = MutableStateFlow<SaveDialogState?>(null)
    val saveDialogState: StateFlow<SaveDialogState?> = _saveDialogState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Provide a public function to update the state
    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun prepareForSave(buffer: ByteArray, config: AudioConfig, suggestedName: String) {
        _saveDialogState.value = SaveDialogState(buffer, config, suggestedName)
    }

    fun onDismissSaveDialog() {
        _saveDialogState.value = null
    }

    /**
     * Handles the user closing the media player UI.
     */
    fun onPlayerClose() {
        mediaPlayerManager.closeMediaPlayer()
        _playerUiState.update { it.copy(isVisible = false) }
    }

    /**
     * Sets up the media player with a new URI.
     */
    fun setUpMediaPlayer(uri: Uri) {
        mediaPlayerManager.setUpMediaPlayer(uri)
    }

    /**
     * This is called when the ViewModel is about to be destroyed.
     * It's the correct place to release the player resources.
     */
    override fun onCleared() {
        super.onCleared()
        mediaPlayerManager.closeMediaPlayer()
        Timber.d("MainViewModel cleared, player released.")
    }

    // We need a way to tell the UI to do things like show a Toast or pick a directory
    private val _uiEvents = MutableStateFlow<UiEvent?>(null)
    val uiEvents: StateFlow<UiEvent?> = _uiEvents.asStateFlow()

    fun processAndPrepareToSaveFile(fileUri: Uri) {
        viewModelScope.launch {

            _isLoading.value = true

            try {
                application.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                    val originalBytes = withContext(Dispatchers.IO) { inputStream.readBytes() }
                    val config = WavUtils.readWavHeader(originalBytes)

                    val audioDataBuffer = ByteBuffer.wrap(
                        originalBytes,
                        WavUtils.WAV_HEADER_SIZE,
                        originalBytes.size - WavUtils.WAV_HEADER_SIZE
                    )

                    val processedBytes = withContext(Dispatchers.Default) {
                        vadProcessor.process(audioDataBuffer, config)
                    }

                    val originalFileName =
                        DocumentFile.fromSingleUri(application, fileUri)?.name ?: "processed.wav"
                    val suggestedName =
                        originalFileName.replace(".wav", "_clipped.wav", ignoreCase = true)

                    // Call the existing function to update the state
                    prepareForSave(processedBytes, config, suggestedName)
                } ?: run {
                    _uiEvents.value = UiEvent.ShowToast("Failed to open file")
                }

                val destDirUri = FileSavingUtils.getCachedGrantedUri(application)
                if (!FileSavingUtils.isUriValidAndAccessible(application, destDirUri)) {
                    _uiEvents.value = UiEvent.RequestDirectoryPicker
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to process file")
                _uiEvents.value = UiEvent.ShowToast("Error: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onEventHandled() {
        _uiEvents.value = null
    }

    fun saveBufferFromService(bufferService: MyBufferServiceInterface?) {
        if (bufferService == null) {
            _uiEvents.value = UiEvent.ShowToast("ERROR: Buffer service is not running.")
            return
        }

        viewModelScope.launch {
            setLoading(true)
            try {
                // Switch to a background thread for the entire operation
                val saveData = withContext(Dispatchers.IO) {
                    val settings = settingsRepository.getSettingsConfig()
                    // This is a suspend function now
                    val originalBuffer = bufferService.pauseSortAndGetBuffer()

                    val bufferToSave = if (settings.isAiAutoClipEnabled) {
                        withContext(Dispatchers.Default) {
                            vadProcessor.process(originalBuffer, settings.toAudioConfig())
                        }
                    } else {
                        originalBuffer.rewind()
                        val bytes = ByteArray(originalBuffer.remaining())
                        originalBuffer.get(bytes)
                        bytes
                    }
                    val audioConfig = settings.toAudioConfig()
                    val timestamp =
                        SimpleDateFormat("yy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
                    val suggestedFileName = "recording_${timestamp}.wav"

                    // Return all the data we need
                    Triple(bufferToSave, audioConfig, suggestedFileName)
                }

                // Update the state on the main thread
                prepareForSave(saveData.first, saveData.second, saveData.third)

                val destDirUri = FileSavingUtils.getCachedGrantedUri(application)
                if (!FileSavingUtils.isUriValidAndAccessible(application, destDirUri)) {
                    _uiEvents.value = UiEvent.RequestDirectoryPicker
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during save buffer preparation")
                _uiEvents.value = UiEvent.ShowToast("Error: ${e.message}")
            } finally {
                // Ensure the service is told to start recording again
                withContext(Dispatchers.IO) {
                    bufferService.startRecording()
                }
                setLoading(false)
            }
        }
    }
}