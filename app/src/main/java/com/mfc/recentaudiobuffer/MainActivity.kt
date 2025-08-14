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
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import javax.inject.Inject
import kotlin.getValue

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
            if (viewModel.wasStartRecordingButtonPress.value) {
                myBufferService?.startRecording()
                viewModel.setWasStartRecordingButtonPress(false)
            }
            lifecycleScope.launch {
                myBufferService?.isRecording?.collect { viewModel.setIsRecording(it) }
            }
            lifecycleScope.launch {
                myBufferService?.isLoading?.collect { viewModel.setLoading(it) }
            }
            lifecycleScope.launch {
                (myBufferService as? MyBufferService)?.serviceError?.collect { error ->
                    if (error != null) {
                        viewModel.setServiceError(error)
                        (myBufferService as? MyBufferService)?.clearServiceError()
                    }
                }
            }
            Timber.d("onServiceConnected()")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            Timber.e("onServiceDisconnected")
            myBufferService = null
            viewModel.setIsRecording(false)
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
                //  Check if a quick save was pending and execute it
                if (viewModel.pendingQuickSave.value) {
                    Timber.d("Directory permission granted, executing pending quick save.")
                    onClickSaveBuffer()
                    viewModel.setPendingQuickSave(false)
                }
            } ?: run {
                //  Handle case where user cancels the picker
                if (viewModel.pendingQuickSave.value) {
                    Toast.makeText(
                        this,
                        "Save cancelled: Directory permission is required.",
                        Toast.LENGTH_LONG
                    ).show()
                    viewModel.setPendingQuickSave(false)
                }
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
        val timestamp = interstitialAdManager.getRewardExpiryTimestamp()
        viewModel.setRewardExpiryTimestamp(timestamp)
        viewModel.setIsRewardActive(System.currentTimeMillis() < timestamp)
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        foregroundServiceAudioBuffer = Intent(this, MyBufferService::class.java)

        // Listen for reward state changes from the AdManager
        lifecycleScope.launch {
            interstitialAdManager.rewardStateChanged.collect {
                updateRewardState()
            }
        }

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val uiEvent by viewModel.uiEvents.collectAsState()
            val serviceError by viewModel.serviceError.collectAsState()

            // This effect block handles one-time events from the ViewModel
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

            // The main UI is now just this single composable call
            MainScreen(
                viewModel = viewModel,
                widthSizeClass = windowSizeClass.widthSizeClass,
                heightSizeClass = windowSizeClass.heightSizeClass,
                // System actions that only the Activity can perform
                openLockScreenSettings = ::openLockScreenSettings,
                openBatteryOptimizationSettings = ::openBatteryOptimizationSettings,
                // Actions that need access to the Activity/Service
                onStartBufferingClick = ::onClickStartRecording,
                onStopBufferingClick = ::onClickStopRecording,
                onResetBufferClick = ::onClickResetBuffer,
                onSaveBufferClick = { viewModel.saveBufferFromService(myBufferService) },
                onDonateClick = ::onClickDonate,
                onSettingsClick = ::onClickSettings,
                onConfirmSave = { fileName ->
                    viewModel.saveDialogState.value?.let { state ->
                        handleConfirmSave(fileName, state.processedAudioData, state.audioConfig)
                    }
                    viewModel.onDismissSaveDialog()
                })

            // The error dialog is an overlay managed by the Activity
            serviceError?.let { message ->
                RecordingErrorDialog(
                    message = message, onDismiss = { viewModel.setServiceError(null) })
            }
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
        updateRewardState()
        // Refresh donation status when the activity (re)starts
        lifecycleScope.launch {
            // hasDonated is true if ads are disabled
            viewModel.setHasDonated(!settingsRepository.getSettingsConfig().areAdsEnabled)
        }
        if (MyBufferService.isServiceRunning.get() && !foregroundBufferServiceConn.isBound) {
            bindService(
                Intent(this, MyBufferService::class.java),
                foregroundBufferServiceConn,
                BIND_AUTO_CREATE
            )
        }

        val grantedUrlMaybe = FileSavingUtils.getCachedGrantedUri(this)
        if (grantedUrlMaybe == null || !FileSavingUtils.isUriValidAndAccessible(
                this, grantedUrlMaybe
            )
        ) {
            viewModel.setShowDirectoryPermissionDialog(true)
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
            viewModel.setPendingQuickSave(true)
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
                putExtra(
                    Settings.EXTRA_CHANNEL_ID, MyBufferService.CHRONIC_NOTIFICATION_CHANNEL_ID
                )
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
                    viewModel.setShowLockscreenInfoDialog(true)
                    // Persist the change so the dialog doesn't show again
                    settingsRepository.updateHasShownLockscreenInfo(true)
                }
            }

            if (!foregroundBufferServiceConn.isBound) {
                viewModel.setWasStartRecordingButtonPress(true)
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
        myBufferService?.stopRecording()
        myBufferService?.resetBuffer()
    }

    private fun onClickSaveBuffer() {
        viewModel.saveBufferFromService(myBufferService)
    }

    private fun onClickDonate() {
        startActivity(Intent(this, DonationActivity::class.java))
    }

    private fun handleConfirmSave(
        fileName: String, buffer: ByteArray, audioConfig: AudioConfig
    ) {
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

    private fun getPermissionsAndThen(
        permissions: List<String>, onPermissionsGranted: () -> Unit
    ) {
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
