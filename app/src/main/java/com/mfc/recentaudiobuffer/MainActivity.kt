package com.mfc.recentaudiobuffer

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

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

    private var myBufferService: MyBufferServiceInterface? = null

    // --- UI State Management ---
    private var isRecording by mutableStateOf(false)
    private var isLoading by mutableStateOf(false)
    private var showRecentFilesDialog by mutableStateOf(false)
    private var showTrimFileDialog by mutableStateOf(false)
    private var showSaveDialog by mutableStateOf(false)
    private var showDirectoryPermissionDialog by mutableStateOf(false)

    // State for the save dialog
    private var vadProcessedByteArray by mutableStateOf<ByteArray?>(null)
    private var audioConfigState by mutableStateOf<AudioConfig?>(null)
    private var suggestedFileNameState by mutableStateOf("")

    private var wasStartRecordingButtonPress = false

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

    private var mediaPlayerManager: MediaPlayerManager? = null
    private lateinit var foregroundServiceAudioBuffer: Intent

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
                myBufferService?.isLoading?.collect { isLoading = it }
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
                if (vadProcessedByteArray != null) {
                    showSaveDialog = true
                }
            }
        }

    private fun pickDirectory() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            directoryPickerLauncher.launch(null)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open directory picker.", Toast.LENGTH_SHORT).show()
            Timber.e(e, "Directory picker failed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        foregroundServiceAudioBuffer = Intent(this, MyBufferService::class.java)
        createNotificationChannels()
        mediaPlayerManager =
            MediaPlayerManager(context = this) { _, _ -> Timber.i("Player is ready.") }

        setContent {
            val authError by authenticationManager.authError.collectAsState()
            MainScreen(
                signInButtonText = authenticationManager.signInButtonText,
                onSignInClick = { authenticationManager.onSignInClick() },
                authError = authError,
                onDismissSignInErrorDialog = { authenticationManager.clearAuthError() },
                isRecordingFromService = isRecording,
                onStartBufferingClick = { onClickStartRecording() },
                onStopBufferingClick = { onClickStopRecording() },
                onResetBufferClick = { onClickResetBuffer() },
                onSaveBufferClick = { onClickSaveBuffer() },
                onPickAndPlayFileClick = { showRecentFilesDialog = true },
                showRecentFilesDialog = showRecentFilesDialog,
                onFileSelected = { uri ->
                    showRecentFilesDialog = false
                    if (uri != Uri.EMPTY) setUpMediaPlayer(uri)
                },
                onDonateClick = { onClickDonate() },
                onSettingsClick = { onClickSettings() },
                onTrimFileClick = { showTrimFileDialog = true },
                showTrimFileDialog = showTrimFileDialog,
                onTrimFileSelected = { uri ->
                    showTrimFileDialog = false
                    if (uri != Uri.EMPTY) lifecycleScope.launch { trimAndSaveFile(uri) }
                },
                showDirectoryPermissionDialog = showDirectoryPermissionDialog,
                onDirectoryAlertDismiss = {
                    showDirectoryPermissionDialog = false
                    pickDirectory()
                },
                mediaPlayerManager = mediaPlayerManager!!,
                isLoading = isLoading,
                showSaveDialog = showSaveDialog,
                suggestedFileName = suggestedFileNameState,
                onConfirmSave = { fileName ->
                    showSaveDialog = false
                    handleConfirmSave(fileName)
                },
                onDismissSaveDialog = {
                    showSaveDialog = false
                    vadProcessedByteArray = null
                    audioConfigState = null
                    suggestedFileNameState = ""
                })
        }

        if (FileSavingUtils.getCachedGrantedUri(this) == null) {
            showDirectoryPermissionDialog = true
        }
        handleIntent(intent)
    }

    private fun onClickSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onStart() {
        super.onStart()
        authenticationManager.registerLauncher(this)
        if (MyBufferService.isServiceRunning.get() && !foregroundBufferServiceConn.isBound) {
            bindService(
                Intent(this, MyBufferService::class.java),
                foregroundBufferServiceConn,
                Context.BIND_AUTO_CREATE
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
        mediaPlayerManager?.closeMediaPlayer()
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == FileSavingService.ACTION_OPEN_FILE) {
            val savedFileUri =
                intent.getParcelableExtra<Uri>(FileSavingService.EXTRA_SAVED_FILE_URI)
            savedFileUri?.let { setUpMediaPlayer(it) }
        }
        if (intent.action == ACTION_REQUEST_DIRECTORY_PERMISSION) {
            pickDirectory()
            setIntent(Intent(this, MainActivity::class.java))
        }
    }

    private fun onClickStartRecording() {
        getPermissionsAndThen(requiredPermissions) {
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
        if (myBufferService == null || !foregroundBufferServiceConn.isBound) {
            Toast.makeText(this, "ERROR: Buffer service is not running.", Toast.LENGTH_LONG).show()
            return
        }
        isLoading = true
        lifecycleScope.launch {
            try {
                val settings = settingsRepository.getSettingsConfig()
                val originalBuffer = myBufferService!!.pauseSortAndGetBuffer()
                val bufferToSave = if (settings.isAiAutoClipEnabled) {
                    withContext(Dispatchers.Default) {
                        vadProcessor.processBufferInChunks(originalBuffer, settings.toAudioConfig())
                    }
                } else {
                    originalBuffer.rewind()
                    val bytes = ByteArray(originalBuffer.remaining())
                    originalBuffer.get(bytes)
                    bytes
                }
                vadProcessedByteArray = bufferToSave
                audioConfigState = settings.toAudioConfig()
                val timestamp =
                    SimpleDateFormat("yy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                suggestedFileNameState = "recording_${timestamp}.wav"
                val destDirUri = FileSavingUtils.getCachedGrantedUri(this@MainActivity)
                if (FileSavingUtils.isUriValidAndAccessible(this@MainActivity, destDirUri)) {
                    showSaveDialog = true
                } else {
                    pickDirectory()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during save buffer preparation")
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                myBufferService!!.startRecording()
                isLoading = false
            }
        }
    }

    private fun onClickDonate() {
        startActivity(Intent(this, DonationActivity::class.java))
    }

    private fun handleConfirmSave(fileName: String) {
        val buffer = vadProcessedByteArray
        val config = audioConfigState
        val destDirUri = FileSavingUtils.getCachedGrantedUri(this)
        if (buffer == null || config == null || destDirUri == null) {
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
                        buffer, config, destDirUri, fileName
                    )
                }.setNegativeButton("Cancel", null).show()
        } else {
            proceedWithSave(buffer, config, destDirUri, fileName)
        }
    }

    private fun proceedWithSave(
        buffer: ByteArray, config: AudioConfig, destDirUri: Uri, fileName: String
    ) {
        lifecycleScope.launch {
            isLoading = true
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
            isLoading = false
        }
    }

    private fun createNotificationChannels() {
        val resultChannel = NotificationChannel(
            FileSavingService.RESULT_NOTIFICATION_CHANNEL_ID,
            FileSavingService.RESULT_NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = FileSavingService.RESULT_NOTIFICATION_CHANNEL_DESCRIPTION
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(resultChannel)
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

    private fun setUpMediaPlayer(selectedMediaToPlayUri: Uri) {
        mediaPlayerManager?.setUpMediaPlayer(selectedMediaToPlayUri)
    }

    private fun trimAndSaveFile(fileUri: Uri) {
        lifecycleScope.launch {
            isLoading = true
            try {
                val inputStream = contentResolver.openInputStream(fileUri)
                val originalBytes = withContext(Dispatchers.IO) { inputStream?.readBytes() }
                inputStream?.close()
                if (originalBytes == null) {
                    Toast.makeText(this@MainActivity, "Failed to read file", Toast.LENGTH_SHORT)
                        .show()
                    return@launch
                }
                val config = WavUtils.readWavHeader(originalBytes)
                val audioData = originalBytes.drop(WavUtils.WAV_HEADER_SIZE).toByteArray()
                val processedBytes = withContext(Dispatchers.Default) {
                    vadProcessor.processBufferInChunks(ByteBuffer.wrap(audioData), config)
                }
                vadProcessedByteArray = processedBytes
                audioConfigState = config
                val originalFileName =
                    DocumentFile.fromSingleUri(this@MainActivity, fileUri)?.name ?: "processed.wav"
                suggestedFileNameState =
                    originalFileName.replace(".wav", "_clipped.wav", ignoreCase = true)
                val destDirUri = FileSavingUtils.getCachedGrantedUri(this@MainActivity)
                if (FileSavingUtils.isUriValidAndAccessible(this@MainActivity, destDirUri)) {
                    showSaveDialog = true
                } else {
                    pickDirectory()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to process file")
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
            }
        }
    }
}
