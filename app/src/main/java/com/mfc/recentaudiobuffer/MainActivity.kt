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
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.mfc.recentaudiobuffer.VADProcessor.Companion.readWavHeader
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

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

    private var isRecording = mutableStateOf(false)

    private var isLoading = mutableStateOf(false)

    private var isPickAndPlayFileRunning = false
    private var wasStartRecordingButtonPress = false

    private var showRecentFilesDialog = mutableStateOf(false)

    private val basePermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE
    )

    private val requiredPermissions = basePermissions + if (Build.VERSION.SDK_INT >= 33) {
        if (Build.VERSION.SDK_INT >= 34) {
            mutableListOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
            )
        } else {
            mutableListOf(
                Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_AUDIO
            )
        }
    } else {
        mutableListOf(
            Manifest.permission.ACCESS_NOTIFICATION_POLICY,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }

    private var mediaPlayerManager: MediaPlayerManager? = null

    private lateinit var foregroundServiceAudioBuffer: Intent
    private val foregroundBufferServiceConn = object : ServiceConnection {
        var isBound: Boolean = false
        private lateinit var service: MyBufferServiceInterface

        override fun onServiceConnected(className: ComponentName, ibinder: IBinder) {
            val binder = ibinder as MyBufferService.MyBinder
            this.service = binder.getService()
            myBufferService = this.service
            this.isBound = true
            RecentAudioBufferApplication.getSharedViewModel().myBufferService = this.service
            if (wasStartRecordingButtonPress) {
                myBufferService!!.startRecording()
                wasStartRecordingButtonPress = false
            }
            lifecycleScope.launch {
                myBufferService?.isRecording?.collect { recordingStatus ->
                    // This block will run every time the state changes in the service
                    isRecording.value = recordingStatus
                }
            }
            Timber.d("onServiceConnect()")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            Timber.e("onServiceDisconnected unexpectedly called")
            myBufferService = null
            isRecording.value = false
            isBound = false
            // Try to rebind the service
            this@MainActivity.startForegroundService(foregroundServiceAudioBuffer)
            bindService(
                Intent(this@MainActivity, MyBufferService::class.java), this, BIND_AUTO_CREATE
            )
        }

        override fun onBindingDied(name: ComponentName?) {
            Timber.e("onBindingDied unexpectedly called")
            isBound = false
            // Try to rebind the service
            this@MainActivity.startForegroundService(foregroundServiceAudioBuffer)
            bindService(
                Intent(this@MainActivity, MyBufferService::class.java), this, BIND_AUTO_CREATE
            )
            super.onBindingDied(name)
            wasStartRecordingButtonPress = false
        }
    }

    private val directoryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { selectedDir: Uri? ->
            selectedDir?.let {
                Timber.i("directoryPickerLauncher: $it")
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                FileSavingUtils.cacheGrantedUri(it)
                // If we have a buffer, prompt to save it
                if (myBufferService != null) {
                    FileSavingUtils.promptSaveFileName(
                        it, myBufferService!!.getBuffer()
                    )
                }
            }
        }

    private fun pickDirectory() {
        val recordingsDirFile =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            val contentUri = getDocumentUriFromPath(recordingsDirFile.absolutePath)
            if (contentUri != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, contentUri)
            }
        }
        val initialUri = intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)
        directoryPickerLauncher.launch(initialUri)
    }

    // Helper function to convert a file path to a content:// URI (if possible)
    private fun getDocumentUriFromPath(path: String): Uri? {
        val externalStorageVolume = Environment.getExternalStorageDirectory()
        if (path.startsWith(externalStorageVolume.absolutePath)) {
            val relativePath = path.substring(externalStorageVolume.absolutePath.length + 1)
            return DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:$relativePath"
            )
        }
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("onCreate(): Build.VERSION.SDK_INT: ${Build.VERSION.SDK_INT}")

        foregroundServiceAudioBuffer = Intent(this, MyBufferService::class.java)

        createNotificationChannels()

        mediaPlayerManager = MediaPlayerManager(context = this) { uri, fileName ->
            // The UI state is handled inside PlayerControlViewContainer.
            // We just need to satisfy the constructor here. Logging is great for debugging.
            Timber.i("Player is ready for file: $fileName")
        }

        setContent {
            MainScreen(
                signInButtonText = authenticationManager.signInButtonText,
                onSignInClick = { authenticationManager.onSignInClick() },
                onStartBufferingClick = { onClickStartRecording() },
                onStopBufferingClick = { onClickStopRecording() },
                onResetBufferClick = { onClickResetBuffer() },
                onSaveBufferClick = { onClickSaveBuffer() },
                onPickAndPlayFileClick = { onClickBrowseRecentFiles() },
                showRecentFilesDialog = showRecentFilesDialog,
                onFileSelected = { uri ->
                    showRecentFilesDialog.value = false
                    setUpMediaPlayer(uri)
                },
                onDonateClick = { onClickDonate() },
                onSettingsClick = { onClickSettings() },
                onTrimFileClick = { onClickTrimFile() },
                onDirectoryAlertDismiss = {
                    FileSavingUtils.showDirectoryPermissionDialog = false
                    pickDirectory()
                },
                mediaPlayerManager = mediaPlayerManager!!,
                isLoading = isLoading,
                isRecordingFromService = isRecording
            )
        }

        // Check if a directory has been cached, otherwise prompt the user
        val grantedDirectoryUri = FileSavingUtils.getCachedGrantedUri()
        if (grantedDirectoryUri == null) {
            FileSavingUtils.showDirectoryPermissionDialog = true
        }

        handleIntent(intent)
    }

    private fun onClickSettings() {
        Intent(this, SettingsActivity::class.java).also {
            it.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            startActivity(it)
        }
    }

    override fun onStart() {
        super.onStart()
        Timber.i(
            "onStart() called with isPickAndPlayFileRunning: $isPickAndPlayFileRunning \nIntent.action: ${intent.action}"
        )
        authenticationManager.registerLauncher(this)
        if (MyBufferService.isServiceRunning.get()) {
            // Rebind to the existing service
            Timber.i("Rebinding to existing service because its flag is set.")
            Intent(this, MyBufferService::class.java).also { intent ->
                bindService(intent, foregroundBufferServiceConn, Context.BIND_AUTO_CREATE)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Timber.i("onNewIntent() called")
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        Timber.i("onStop() finished")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (foregroundBufferServiceConn.isBound) {
            unbindService(foregroundBufferServiceConn)
            foregroundBufferServiceConn.isBound = false
        }
        Timber.i("onDestroy() finished")
    }

    private fun handleIntent(intent: Intent) {
        // Check if the activity was launched from the notification
        if (intent.action == FileSavingService.ACTION_OPEN_FILE) {
            Timber.d("Got external intent to open file")
            val savedFileUri =
                intent.getParcelableExtra<Uri>(FileSavingService.EXTRA_SAVED_FILE_URI)
            if (savedFileUri != null) {
                // Call setUpMediaPlayer directly, bypassing any pickers
                setUpMediaPlayer(savedFileUri)
            } else {
                Timber.e("savedFileUri is null")
            }
        }

        if (intent.action == ACTION_REQUEST_DIRECTORY_PERMISSION) {
            Timber.d("Intent to request directory permission received.")
            // Directly trigger the directory picker flow.
            pickDirectory()
            // Clear the action so it doesn't re-trigger on configuration changes
            setIntent(Intent(this, MainActivity::class.java))
        }
    }

    private fun onClickStartRecording() {
        getPermissionsAndThen(requiredPermissions) {
            if (!foregroundBufferServiceConn.isBound) {
                wasStartRecordingButtonPress = true
                this.startForegroundService(foregroundServiceAudioBuffer)
                bindService(
                    Intent(this, MyBufferService::class.java),
                    foregroundBufferServiceConn,
                    BIND_AUTO_CREATE
                )
                Timber.i("Buffer service started and bound")
            } else if (myBufferService!!.isRecording.value) {
                runOnUiThread {
                    Toast.makeText(
                        this, "Buffer is already running!", Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                myBufferService!!.startRecording()
                runOnUiThread {
                    Toast.makeText(
                        this, "Restarted buffering in the background", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun onClickStopRecording() {
        if (foregroundBufferServiceConn.isBound) {
            if (myBufferService!!.isRecording.value) {
                Timber.i("Stopping recording in MyBufferService")
                myBufferService!!.stopRecording()
                runOnUiThread {
                    Toast.makeText(
                        this, "Stopped buffering in the background", Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                runOnUiThread {
                    Toast.makeText(
                        this, "Buffer is not running", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            Timber.e("Buffer service is not running")
            runOnUiThread {
                Toast.makeText(
                    this, "Buffer service is not running", Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun onClickResetBuffer() {
        if (foregroundBufferServiceConn.isBound) {
            myBufferService!!.resetBuffer()
        } else {
            runOnUiThread {
                Toast.makeText(this, "ERROR: Buffer service is not running. ", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun onClickSaveBuffer() {
        if (!foregroundBufferServiceConn.isBound) {
            Toast.makeText(this, "ERROR: Buffer service is not running.", Toast.LENGTH_LONG).show()
            return
        }

        isLoading.value = true // Show loading indicator

        lifecycleScope.launch {
            // 1. Get settings FIRST, before changing any state
            val settings = settingsRepository.getSettingsConfig()

            // 2. Get the full audio buffer
            val originalBuffer = myBufferService!!.getBuffer()

            // 3. Trim the buffer in a separate thread, if enabled
            val bufferToSave = if (settings.isAiAutoClipEnabled) {
                Timber.d("Auto-clipping enabled. Processing buffer...")
                // Offload the heavy work to a background thread for smooth UI
                val processedBuffer = withContext(Dispatchers.Default) {
                    vadProcessor.processBuffer(originalBuffer, settings.toAudioConfig())
                }

                processedBuffer
            } else {
                originalBuffer
            }

            // 4. Proceed to save
            val prevGrantedUri = FileSavingUtils.getCachedGrantedUri()
            if (FileSavingUtils.isUriValidAndAccessible(this@MainActivity, prevGrantedUri)) {
                FileSavingUtils.promptSaveFileName(prevGrantedUri!!, bufferToSave)
            } else {
                pickDirectory()
            }
        }
    }

    // Helper to convert SettingsConfig to AudioConfig
    private fun SettingsConfig.toAudioConfig(): AudioConfig {
        return AudioConfig(this.sampleRateHz, this.bufferTimeLengthS, this.bitDepth)
    }

    private fun onClickBrowseRecentFiles() {
        getPermissionsAndThen(requiredPermissions) {
            showRecentFilesDialog.value = true
        }
    }

    private fun onClickDonate() {
        getPermissionsAndThen(requiredPermissions) {
            val intent = Intent(this, DonationActivity::class.java)
            startActivity(intent)
        }
    }

    private fun createNotificationChannels() {
        val resultChannel = NotificationChannel(
            FileSavingService.RESULT_NOTIFICATION_CHANNEL_ID,
            FileSavingService.RESULT_NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = FileSavingService.RESULT_NOTIFICATION_CHANNEL_DESCRIPTION
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.createNotificationChannels(listOf(resultChannel))
    }

    private var pendingPermissions: Array<String>? = null // Store pending permissions

    private val multiplePermissionsLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val deniedPermissions = permissions.entries.filter { !it.value }.map { it.key }

            if (deniedPermissions.isEmpty()) {
                // All permissions granted
                onPermissionsGrantedCallback?.invoke()
            } else {
                val wasTemporarilyDenied = deniedPermissions.any {
                    ActivityCompat.shouldShowRequestPermissionRationale(this, it)
                }

                if (wasTemporarilyDenied) {
                    pendingPermissions = deniedPermissions.toTypedArray()

                    AlertDialog.Builder(this).setTitle("Permissions Required")
                        .setMessage("These permissions are needed. Please grant them.")
                        .setPositiveButton("OK") { _, _ ->
                            pendingPermissions?.let {
                                requestPermissionsAgain(it) // Call a separate function
                                pendingPermissions = null
                            }
                        }.setNegativeButton("Cancel") { dialog, _ ->
                            dialog.dismiss()
                            pendingPermissions = null
                        }.show()

                } else {
                    // "Don't ask again" was checked and permission denied
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Perm}issions are required. Please grant them in app settings.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                    startActivity(intent)
                    pendingPermissions = null // Clear pending permissions
                }
            }
            onPermissionsGrantedCallback = null // Reset the callback after it's been used.
        }

    private fun requestPermissionsAgain(permissions: Array<String>) {
        multiplePermissionsLauncher.launch(permissions)
    }

    private var onPermissionsGrantedCallback: (() -> Unit)? = null // Store the callback

    private fun getPermissionsAndThen(
        permissions: List<String>, onPermissionsGranted: () -> Unit
    ) {
        Timber.i("getPermissionsAndThen()")
        onPermissionsGrantedCallback = onPermissionsGranted // Store the callback

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(
                this, it
            ) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            multiplePermissionsLauncher.launch(permissionsToRequest)
        } else {
            Timber.i("All permissions already granted.")
            onPermissionsGrantedCallback?.invoke() // Call the callback immediately
            onPermissionsGrantedCallback = null // Reset the callback
        }

        Timber.i("done getPermissionsAndThen()")
    }

    private fun setUpMediaPlayer(selectedMediaToPlayUri: Uri) {
        Timber.d(
            "setUpMediaPlayer: selectedMediaToPlayUri = $selectedMediaToPlayUri"
        )
        mediaPlayerManager?.setUpMediaPlayer(selectedMediaToPlayUri)
    }

    private val trimFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { fileUri ->
                lifecycleScope.launch {
                    // We'll define this function next
                    trimAndSaveFile(fileUri)
                }
            }
        }

    // 2. Add the click handler function
    private fun onClickTrimFile() {
        // This launches the system file picker to select an audio file
        trimFileLauncher.launch("audio/wav")
    }

    private fun trimAndSaveFile(fileUri: Uri) {
        isLoading.value = true
        Timber.d("Trimming file: $fileUri")

        try {
            // Read the original file bytes
            val inputStream = contentResolver.openInputStream(fileUri)
            val originalBytes = inputStream?.readBytes() ?: run {
                runOnUiThread {
                    Toast.makeText(this, "Failed to read file", Toast.LENGTH_SHORT).show()
                }
                return
            }
            inputStream.close()

            // Get the config from the file's header
            val config = readWavHeader(originalBytes)

            // Process the buffer
            val processedBytes = vadProcessor.processBuffer(originalBytes, config)

            // Save the new, processed file
            val prevGrantedUri = FileSavingUtils.getCachedGrantedUri()
            if (FileSavingUtils.isUriValidAndAccessible(this, prevGrantedUri)) {
                // Suggest a new name for the processed file
                val originalFileName =
                    DocumentFile.fromSingleUri(this, fileUri)?.name ?: "processed_file.wav"
                val newFileName = originalFileName.replace(".wav", "_clipped.wav")

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Processing complete! Saving new file...",
                        Toast.LENGTH_LONG
                    )
                        .show()
                }
                FileSavingUtils.promptSaveFileName(prevGrantedUri!!, processedBytes, newFileName)
            } else {
                // If we don't have a valid save directory, ask for one
                pickDirectory()
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Please select a directory to save the processed file.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "Failed to process file")
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Error during processing: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        } finally {
            isLoading.value = false
        }
    }
}
