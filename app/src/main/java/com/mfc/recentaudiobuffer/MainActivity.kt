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

import android.Manifest
import android.app.AlertDialog
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import dagger.hilt.android.AndroidEntryPoint
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
import kotlin.getValue


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


@AndroidEntryPoint
@UnstableApi
class MainActivity : AppCompatActivity() {
    companion object {
        const val ACTION_REQUEST_DIRECTORY_PERMISSION =
            "com.mfc.recentaudiobuffer.ACTION_REQUEST_DIRECTORY_PERMISSION"
    }

    @Inject
    lateinit var authenticationManager: AuthenticationManager

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var vadProcessor: VADProcessor

    @Inject
    lateinit var interstitialAdManager: InterstitialAdManager

    private val viewModel: MainViewModel by viewModels()
    private var myBufferService: MyBufferServiceInterface? = null

    // --- UI State Management ---
    private var serviceError by mutableStateOf<String?>(null)
    private var isRecording by mutableStateOf(false)
    private var showRecentFilesDialog by mutableStateOf(false)
    private var showTrimFileDialog by mutableStateOf(false)
    private var showDirectoryPermissionDialog by mutableStateOf(false)
    private var showLockscreenInfoDialog = mutableStateOf(false)
    private var hasDonated by mutableStateOf(false)
    private var wasStartRecordingButtonPress = false
    private var isRewardActive by mutableStateOf(false)
    private var rewardExpiryTimestamp by mutableLongStateOf(0L)

    private val requiredPermissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.FOREGROUND_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        }
    }

    private lateinit var foregroundServiceAudioBuffer: Intent
    private lateinit var consentInformation: ConsentInformation

    private val foregroundBufferServiceConn = object : ServiceConnection {
        var isBound: Boolean = false
        override fun onServiceConnected(className: ComponentName, ibinder: IBinder) {
            val binder = ibinder as MyBufferService.MyBinder
            myBufferService = binder.getService()
            isBound = true
            if (wasStartRecordingButtonPress) {
                myBufferService?.startRecording()
                wasStartRecordingButtonPress = false
            }
            lifecycleScope.launch {
                myBufferService?.isRecording?.collect { isRecording = it }
            }
            lifecycleScope.launch {
                myBufferService?.isLoading?.collect { viewModel.setLoading(it) }
            }
            lifecycleScope.launch {
                (myBufferService as? MyBufferService)?.serviceError?.collect { error ->
                    if (error != null) {
                        serviceError = error
                        (myBufferService as? MyBufferService)?.clearServiceError()
                    }
                }
            }
            Timber.d("onServiceConnected()")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            Timber.e("onServiceDisconnected")
            myBufferService = null
            isRecording = false
            isBound = false
        }
    }

    private val directoryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { selectedDir: Uri? ->
            selectedDir?.let {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                FileSavingUtils.cacheGrantedUri(this, it)
                // The UI will show the dialog automatically if viewModel.saveDialogState is not null.
            }
        }

    private fun pickDirectory() {
        try {
            // First, check if the user has already picked a directory.
            // If so, start them there for convenience.
            val cachedUri = FileSavingUtils.getCachedGrantedUri(this)
            if (cachedUri != null) {
                directoryPickerLauncher.launch(cachedUri)
                return
            }

            // If no directory is cached (first run), create a URI that points
            // to the public "Recordings" directory as a starting suggestion.
            val documentId = "primary:${Environment.DIRECTORY_RECORDINGS}"
            val initialUri = DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents", documentId
            )

            // Pass this URI to the launcher. The system will try to open here.
            // If the directory doesn't exist, it will fall back gracefully to the root.
            directoryPickerLauncher.launch(initialUri)

        } catch (e: Exception) {
            // If building the URI fails for any reason, launch the picker normally.
            Toast.makeText(this, "Could not open directory picker.", Toast.LENGTH_SHORT).show()
            Timber.e(e, "Directory picker failed, falling back to default.")
            try {
                directoryPickerLauncher.launch(null)
            } catch (fallbackException: Exception) {
                Timber.e(fallbackException, "Fallback directory picker also failed.")
            }
        }
    }

    private fun updateRewardState() {
        rewardExpiryTimestamp = interstitialAdManager.getRewardExpiryTimestamp()
        isRewardActive = System.currentTimeMillis() < rewardExpiryTimestamp
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        foregroundServiceAudioBuffer = Intent(this, MyBufferService::class.java)

        updateRewardState()

        // Listen for reward state changes from the AdManager
        lifecycleScope.launch {
            interstitialAdManager.rewardStateChanged.collect {
                updateRewardState()
            }
        }

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val playerUiState by viewModel.playerUiState.collectAsState()
            val saveDialogState by viewModel.saveDialogState.collectAsState()
            val isLoading by viewModel.isLoading.collectAsState()
            val uiEvent by viewModel.uiEvents.collectAsState()
            LaunchedEffect(uiEvent) {
                when (val event = uiEvent) {
                    is UiEvent.ShowToast -> {
                        Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_LONG).show()
                        viewModel.onEventHandled()
                    }

                    is UiEvent.RequestDirectoryPicker -> {
                        pickDirectory()
                        viewModel.onEventHandled()
                    }

                    null -> { /* Do nothing */
                    }
                }
            }
            MainScreen(
                widthSizeClass = windowSizeClass.widthSizeClass,
                heightSizeClass = windowSizeClass.heightSizeClass,
                isRecordingFromService = isRecording,
                onStartBufferingClick = { onClickStartRecording() },
                onStopBufferingClick = { onClickStopRecording() },
                onResetBufferClick = { onClickResetBuffer() },
                onSaveBufferClick = { onClickSaveBuffer() },
                onPickAndPlayFileClick = { showRecentFilesDialog = true },
                showRecentFilesDialog = showRecentFilesDialog,
                onFileSelected = { uri ->
                    showRecentFilesDialog = false
                    if (uri != Uri.EMPTY) viewModel.setUpMediaPlayer(uri)
                },
                onDonateClick = { onClickDonate() },
                hasDonated = hasDonated,
                isRewardActive = isRewardActive,
                rewardExpiryTimestamp = rewardExpiryTimestamp,
                onSettingsClick = { onClickSettings() },
                onTrimFileClick = { showTrimFileDialog = true },
                showTrimFileDialog = showTrimFileDialog,
                onTrimFileSelected = { uri ->
                    showTrimFileDialog = false
                    if (uri != Uri.EMPTY) {
                        // The new, simple call
                        viewModel.processAndPrepareToSaveFile(uri)
                    }
                },
                showDirectoryPermissionDialog = showDirectoryPermissionDialog,
                onDirectoryAlertDismiss = {
                    showDirectoryPermissionDialog = false
                    pickDirectory()
                },
                mediaPlayerManager = viewModel.mediaPlayerManager,
                playerUiState = playerUiState,
                onPlayerClose = { viewModel.onPlayerClose() },
                isLoading = isLoading,
                showSaveDialog = saveDialogState != null,
                showLockscreenInfoDialog = showLockscreenInfoDialog,
                openLockScreenSettings = { openLockScreenSettings() },
                openBatteryOptimizationSettings = { openBatteryOptimizationSettings() },
                suggestedFileName = saveDialogState?.suggestedFileName ?: "",
                onConfirmSave = { fileName ->
                    saveDialogState?.let { state ->
                        handleConfirmSave(fileName, state.processedAudioData, state.audioConfig)
                    }
                    viewModel.onDismissSaveDialog()
                },
                onDismissSaveDialog = {
                    viewModel.onDismissSaveDialog()
                })
            serviceError?.let { message ->
                RecordingErrorDialog(
                    message = message, onDismiss = { serviceError = null })
            }
        }

        if (FileSavingUtils.getCachedGrantedUri(this) == null) {
            showDirectoryPermissionDialog = true
        }
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()

        // It's much safer to check for and show the consent form here.
        val params = ConsentRequestParameters.Builder().build()
        consentInformation = UserMessagingPlatform.getConsentInformation(this)
        consentInformation.requestConsentInfoUpdate(this, params, {
            // By the time onResume is called, it's safe to show a UI element.
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) { loadAndShowError ->
                if (loadAndShowError != null) {
                    Timber.e("Consent form failed to load: ${loadAndShowError.message}")
                    return@loadAndShowConsentFormIfRequired
                }

                AdInitializer.initialize(this)
            }
        }, { requestConsentError ->
            Timber.e("Consent info update failed: ${requestConsentError.message}")
            Timber.d("Initializing ads after consent flow failure.")
            AdInitializer.initialize(this)
        })
    }

    private fun onClickSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onStart() {
        super.onStart()
        // Refresh donation status when the activity (re)starts
        lifecycleScope.launch {
            // hasDonated is true if ads are disabled
            hasDonated = !settingsRepository.getSettingsConfig().areAdsEnabled
        }
        if (MyBufferService.isServiceRunning.get() && !foregroundBufferServiceConn.isBound) {
            bindService(
                Intent(this, MyBufferService::class.java),
                foregroundBufferServiceConn,
                BIND_AUTO_CREATE
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (foregroundBufferServiceConn.isBound) {
            unbindService(foregroundBufferServiceConn)
        }
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == FileSavingService.ACTION_OPEN_FILE) {
            val savedFileUri =
                intent.getParcelableExtra<Uri>(FileSavingService.EXTRA_SAVED_FILE_URI)
            savedFileUri?.let { viewModel.setUpMediaPlayer(it) }
        }
        if (intent.action == ACTION_REQUEST_DIRECTORY_PERMISSION) {
            pickDirectory()
            setIntent(Intent(this, MainActivity::class.java))
        }
    }

    private fun openLockScreenSettings() {
        try {
            // This intent takes the user to the global lock screen notification settings.
            val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                // Use the constant from your service
                putExtra(Settings.EXTRA_CHANNEL_ID, MyBufferService.CHRONIC_NOTIFICATION_CHANNEL_ID)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback for older devices or custom ROMs that might not have this specific screen.
            Timber.w(e, "Could not open lock screen settings, falling back to app details.")
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Could not open lock screen settings.")
            Toast.makeText(this, "Could not open settings.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Could not open battery optimization settings.")
            Toast.makeText(this, "Could not open settings.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onClickStartRecording() {
        getPermissionsAndThen(requiredPermissions) {
            lifecycleScope.launch {
                val settings = settingsRepository.getSettingsConfig()
                if (!settings.hasShownLockscreenInfo) {
                    showLockscreenInfoDialog.value = true
                    // Persist the change so the dialog doesn't show again
                    settingsRepository.updateHasShownLockscreenInfo(true)
                }
            }

            if (!foregroundBufferServiceConn.isBound) {
                wasStartRecordingButtonPress = true
                startForegroundService(foregroundServiceAudioBuffer)
                bindService(
                    Intent(this, MyBufferService::class.java),
                    foregroundBufferServiceConn,
                    BIND_AUTO_CREATE
                )
            } else {
                myBufferService?.startRecording()
            }
        }
    }

    private fun onClickStopRecording() {
        myBufferService?.stopRecording()
    }

    private fun onClickResetBuffer() {
        myBufferService?.resetBuffer()
    }

    private fun onClickSaveBuffer() {
        viewModel.saveBufferFromService(myBufferService)
    }

    private fun onClickDonate() {
        startActivity(Intent(this, DonationActivity::class.java))
    }

    private fun handleConfirmSave(fileName: String, buffer: ByteArray, audioConfig: AudioConfig) {
        val destDirUri = FileSavingUtils.getCachedGrantedUri(this)
        if (destDirUri == null) {
            Toast.makeText(this, "Internal error: Missing data for save.", Toast.LENGTH_SHORT)
                .show()
            return
        }
        val destDir = DocumentFile.fromTreeUri(this, destDirUri)
        if (destDir?.findFile(fileName) != null) {
            AlertDialog.Builder(this).setTitle("File Exists")
                .setMessage("A file named '$fileName' already exists. Do you want to overwrite it?")
                .setPositiveButton("Overwrite") { _, _ ->
                    proceedWithSave(
                        buffer, audioConfig, destDirUri, fileName
                    )
                }.setNegativeButton("Cancel", null).show()
        } else {
            proceedWithSave(buffer, audioConfig, destDirUri, fileName)
        }
    }

    private fun proceedWithSave(
        buffer: ByteArray, config: AudioConfig, destDirUri: Uri, fileName: String
    ) {
        lifecycleScope.launch {
            viewModel.setLoading(true)
            val tempFileUri = withContext(Dispatchers.IO) {
                FileSavingUtils.saveBufferToTempFile(this@MainActivity, ByteBuffer.wrap(buffer))
            }
            if (tempFileUri != null) {
                val saveIntent = Intent(this@MainActivity, FileSavingService::class.java).apply {
                    putExtra(FileSavingService.EXTRA_TEMP_FILE_URI, tempFileUri)
                    putExtra(FileSavingService.EXTRA_DEST_DIR_URI, destDirUri)
                    putExtra(FileSavingService.EXTRA_DEST_FILENAME, fileName)
                    putExtra(FileSavingService.EXTRA_AUDIO_CONFIG, config)
                }
                startService(saveIntent)
                myBufferService?.resetBuffer()
            } else {
                Toast.makeText(
                    this@MainActivity, "Failed to create temporary file.", Toast.LENGTH_LONG
                ).show()
            }
            viewModel.setLoading(false)
        }
    }

    private var onPermissionsGrantedCallback: (() -> Unit)? = null

    private val multiplePermissionsLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                onPermissionsGrantedCallback?.invoke()
            } else {
                Toast.makeText(
                    this,
                    "Permissions are required. Please grant them in app settings.",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            }
            onPermissionsGrantedCallback = null
        }

    private fun getPermissionsAndThen(permissions: List<String>, onPermissionsGranted: () -> Unit) {
        onPermissionsGrantedCallback = onPermissionsGranted
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (permissionsToRequest.isNotEmpty()) {
            multiplePermissionsLauncher.launch(permissionsToRequest)
        } else {
            onPermissionsGrantedCallback?.invoke()
            onPermissionsGrantedCallback = null
        }
    }
}
