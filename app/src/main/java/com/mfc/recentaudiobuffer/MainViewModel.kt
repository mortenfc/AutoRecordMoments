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
import com.mfc.recentaudiobuffer.speakeridentification.DiarizationProcessor
import com.mfc.recentaudiobuffer.speakeridentification.Speaker
import com.mfc.recentaudiobuffer.speakeridentification.SpeakerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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
    private val settingsRepository: SettingsRepository,
    private val speakerRepository: SpeakerRepository,
    private val diarizationProcessor: DiarizationProcessor
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
    private val _showPrivacyInfoDialog = MutableStateFlow(false)
    val showPrivacyInfoDialog = _showPrivacyInfoDialog.asStateFlow()
    private val _showLockscreenInfoDialog = MutableStateFlow(false)
    val showLockscreenInfoDialog = _showLockscreenInfoDialog.asStateFlow()
    private val _showBatteryInfoDialog = MutableStateFlow(false)
    val showBatteryInfoDialog = _showBatteryInfoDialog.asStateFlow()
    private val _playerUiState = MutableStateFlow(PlayerUiState())
    val playerUiState: StateFlow<PlayerUiState> = _playerUiState.asStateFlow()
    private val _saveDialogState = MutableStateFlow<SaveDialogState?>(null)
    val saveDialogState: StateFlow<SaveDialogState?> = _saveDialogState.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _uiEvents = MutableStateFlow<UiEvent?>(null)
    val uiEvents: StateFlow<UiEvent?> = _uiEvents.asStateFlow()
    private val _pendingQuickSave = MutableStateFlow(false)
    val pendingQuickSave = _pendingQuickSave.asStateFlow()

    val settings: StateFlow<SettingsConfig> = settingsRepository.getSettingsConfigFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsConfig())

    val speakers: StateFlow<List<Speaker>> = speakerRepository.getAllSpeakers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mediaPlayerManager: MediaPlayerManager = MediaPlayerManager(application) { uri, fileName ->
        viewModelScope.launch {
            _playerUiState.update {
                it.copy(
                    isVisible = true, currentUri = uri, currentFileName = fileName
                )
            }
        }
    }

    // --- Setters for UI State ---
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

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun onDismissSaveDialog() {
        _saveDialogState.value = null
    }

    fun onPlayerClose() {
        mediaPlayerManager.closeMediaPlayer()
        _playerUiState.update { it.copy(isVisible = false) }
    }

    fun setUpMediaPlayer(uri: Uri) {
        mediaPlayerManager.setUpMediaPlayer(uri)
    }

    fun onEventHandled() {
        _uiEvents.value = null
    }

    fun requestDirectoryPicker() {
        _uiEvents.value = UiEvent.RequestDirectoryPicker
    }

    fun setPendingQuickSave(pending: Boolean) {
        _pendingQuickSave.value = pending
    }

    // --- Business Logic ---
    fun onPrivacyDialogDismissed() {
        _showPrivacyInfoDialog.value = false
        _showLockscreenInfoDialog.value = true
    }

    fun onLockscreenDialogDismissed() {
        _showLockscreenInfoDialog.value = false
        _showBatteryInfoDialog.value = true
    }

    fun onSpeakerSelectionChanged(speakerId: String, isSelected: Boolean) {
        viewModelScope.launch {
            val currentSelection = settings.value.selectedSpeakerIds.toMutableSet()
            if (isSelected) {
                currentSelection.add(speakerId)
            } else {
                currentSelection.remove(speakerId)
            }
            settingsRepository.updateSelectedSpeakerIds(currentSelection)
        }
    }

    fun processAndSaveBufferFromService(bufferService: MyBufferServiceInterface?) {
        if (bufferService == null) {
            _uiEvents.value = UiEvent.ShowToast("ERROR: Buffer service is not running.")
            return
        }
        viewModelScope.launch {
            setLoading(true)
            try {
                val currentSettings = settings.value
                val originalBuffer =
                    withContext(Dispatchers.IO) { bufferService.pauseSortAndGetBuffer() }

                if (!originalBuffer.hasRemaining()) {
                    _uiEvents.value = UiEvent.ShowToast("Buffer is empty, nothing to save.")
                    return@launch
                }

                val (dataToSave, configForSave) = if (currentSettings.isAiAutoClipEnabled) {
                    val audioBytes = withContext(Dispatchers.Default) {
                        vadProcessor.process(originalBuffer, currentSettings.toAudioConfig())
                    }
                    Pair(audioBytes, currentSettings.toAudioConfig())
                } else {
                    val audioBytes =
                        ByteArray(originalBuffer.remaining()).also { originalBuffer.get(it) }
                    Pair(audioBytes, currentSettings.toAudioConfig())
                }

                if (dataToSave.isEmpty()) {
                    _uiEvents.value =
                        UiEvent.ShowToast("No audio from selected speakers was found.")
                    return@launch
                }

                val timestamp =
                    SimpleDateFormat("yy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
                val suggestedFileName = "recording_${timestamp}.wav" // TODO maybe name based on speakers
                _saveDialogState.value =
                    SaveDialogState(dataToSave, configForSave, suggestedFileName)

                val destDirUri = FileSavingUtils.getCachedGrantedUri(application)
                if (!FileSavingUtils.isUriValidAndAccessible(application, destDirUri)) {
                    _uiEvents.value = UiEvent.RequestDirectoryPicker
                }

            } catch (e: Exception) {
                Timber.e(e, "Error during save buffer preparation")
                _uiEvents.value = UiEvent.ShowToast("Error: ${e.message}")
            } finally {
                bufferService.startRecording()
                setLoading(false)
            }
        }
    }

    fun processAndPrepareToSaveFile(fileUri: Uri) {
        viewModelScope.launch {
            setLoading(true)
            try {
                application.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                    val originalBytes = withContext(Dispatchers.IO) { inputStream.readBytes() }
                    val config = WavUtils.readWavHeader(originalBytes)
                    if (config == null) {
                        throw IllegalArgumentException("Invalid WAV header")
                    }
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
                    _saveDialogState.value = SaveDialogState(processedBytes, config, suggestedName)
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
                setLoading(false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        mediaPlayerManager.closeMediaPlayer()
        Timber.d("MainViewModel cleared, player released.")
    }
}