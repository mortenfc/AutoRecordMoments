package com.mfc.recentaudiobuffer

import MediaPlayerManager
import android.Manifest
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
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint

class SharedViewModel : ViewModel() {
    var myBufferService: MyBufferServiceInterface? = null
}

@AndroidEntryPoint
@UnstableApi
class MainActivity : AppCompatActivity() {
    private val logTag = "MainActivity"

    @Inject
    lateinit var authenticationManager: AuthenticationManager
    private val sharedViewModel: SharedViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private var myBufferService: MyBufferServiceInterface? = null

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= 33) {
        mutableListOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
    } else {
        mutableListOf(
            Manifest.permission.ACCESS_NOTIFICATION_POLICY,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
    }

    private var mediaPlayerManager: MediaPlayerManager? = null

    private lateinit var foregroundServiceAudioBuffer: Intent
    private val foregroundServiceAudioBufferConnection = object : ServiceConnection {
        var isBound: Boolean = false
        private lateinit var service: MyBufferServiceInterface

        override fun onServiceConnected(className: ComponentName, ibinder: IBinder) {
            val binder = ibinder as MyBufferService.MyBinder
            this.service = binder.getService()
            myBufferService = this.service
            this.isBound = true
            sharedViewModel.myBufferService = this.service
            myBufferService?.startRecording()
            Log.d(logTag, "onServiceConnect()")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            Log.e(logTag, "onServiceDisconnected unexpectedly called")
            isBound = false
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.e(logTag, "onBindingDied unexpectedly called")
            isBound = false
            super.onBindingDied(name)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(logTag, "onCreate(): Build.VERSION.SDK_INT: ${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT >= 34) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        }
        if (Build.VERSION.SDK_INT < 33) {
            requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        foregroundServiceAudioBuffer = Intent(this, MyBufferService::class.java)

        getPermissions()

        createNotificationChannels()

        ViewModelHolder.setSharedViewModel(sharedViewModel)

        mediaPlayerManager = MediaPlayerManager(context = this, onPlayerReady = {
            Log.i(logTag, "Player is ready")
        })

        setContent {
            MaterialTheme {
                MainScreen(
                    signInButtonText = authenticationManager.signInButtonText,
                    onSignInClick = { authenticationManager.onSignInClick() },
                    onStartBufferingClick = { onClickStartRecording() },
                    onStopBufferingClick = { onClickStopRecording() },
                    onResetBufferClick = { onClickResetBuffer() },
                    onSaveBufferClick = { onClickSaveBuffer() },
                    onPickAndPlayFileClick = { onClickPickAndPlayFile() },
                    onDonateClick = { onClickDonate() },
                    onSettingsClick = { onClickSettings() },
                    mediaPlayerManager = mediaPlayerManager!!
                )
            }
        }
    }

    private fun onClickSettings() {
        Intent(this, SettingsActivity::class.java).also {
            it.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            startActivity(it)
        }
        if (myBufferService != null && myBufferService!!.isRecording.get()) {
            myBufferService!!.stopRecording()
            Toast.makeText(
                this, "Stopped buffering in the background", Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onStop() {
        super.onStop()
        closeMediaPlayer()
        Log.i(logTag, "onStop() finished")
    }

    override fun onStart() {
        Log.i(logTag, "onStart() called")
        super.onStart()
        authenticationManager.registerLauncher(this)
    }

    private fun onClickStartRecording() {
        if (haveAllPermissions(requiredPermissions)) {
            if (!foregroundServiceAudioBufferConnection.isBound) {
                this.startForegroundService(foregroundServiceAudioBuffer)
                bindService(
                    Intent(this, MyBufferService::class.java),
                    foregroundServiceAudioBufferConnection,
                    BIND_AUTO_CREATE
                )
                Log.i(logTag, "Buffer service started and bound")
            } else if (myBufferService!!.isRecording.get()) {
                Toast.makeText(
                    this, "Buffer is already running!", Toast.LENGTH_SHORT
                ).show()
            } else {
                myBufferService!!.startRecording()
                Toast.makeText(
                    this, "Restarted buffering in the background", Toast.LENGTH_LONG
                ).show()
            }
        } else {
            getPermissions()
            Toast.makeText(
                this, "Accept the permissions and then start again", Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun onClickStopRecording() {
        if (foregroundServiceAudioBufferConnection.isBound) {
            if (myBufferService!!.isRecording.get()) {
                Log.i(logTag, "Stopping recording in MyBufferService")
                myBufferService!!.stopRecording()
                Toast.makeText(
                    this, "Stopped buffering in the background", Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this, "Buffer is not running", Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            Log.e(logTag, "Buffer service is not running")
            Toast.makeText(
                this, "Buffer service is not running", Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun onClickResetBuffer() {
        if (foregroundServiceAudioBufferConnection.isBound) {
            myBufferService!!.resetBuffer()
        } else {
            Toast.makeText(this, "ERROR: Buffer service is not running. ", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private val saveStorageUriPermission =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { grantedDirectoryUri: Uri? ->
            if (grantedDirectoryUri != null) {
                contentResolver.takePersistableUriPermission(
                    grantedDirectoryUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                Log.d(
                    logTag,
                    "takePersistableUriPermission called for grantedDirectoryUri: $grantedDirectoryUri"
                )
                FileSavingUtils.cacheGrantedUri(
                    this@MainActivity, grantedDirectoryUri
                )
                FileSavingUtils.promptSaveFileName(
                    this@MainActivity, grantedDirectoryUri, myBufferService!!.getBuffer()
                )
            } else {
                // Handle case where grantedUri is null
                Toast.makeText(this, "Error getting granted URI", Toast.LENGTH_SHORT).show()
            }
        }

    private fun onClickSaveBuffer() {
        if (foregroundServiceAudioBufferConnection.isBound) {
            lifecycleScope.launch {
                onClickStopRecording()
                val grantedUri = FileSavingUtils.getCachedGrantedUri(this@MainActivity)
                Log.d(logTag, "MainActivity: grantedUri = $grantedUri")
                if (grantedUri != null) {
                    // Use previously permitted cached uri
                    FileSavingUtils.promptSaveFileName(
                        this@MainActivity, grantedUri, myBufferService!!.getBuffer()
                    )
                } else {
                    // Otherwise get file saving location permission
                    saveStorageUriPermission.launch(null)
                }
            }
        } else {
            Toast.makeText(
                this,
                "ERROR: Buffer service is not running. There is no recorded data.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun onClickPickAndPlayFile() {
        if (haveAllPermissions(requiredPermissions)) {
            pickAndPlayFile()
        } else {
            getPermissions()
            Toast.makeText(
                this, "Accept the permissions and then try again", Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun onClickDonate() {
        val intent = Intent(this, DonationActivity::class.java)
        startActivity(intent)
        if (myBufferService != null && myBufferService!!.isRecording.get()) {
            myBufferService!!.stopRecording()
            Toast.makeText(
                this, "Stopped buffering in the background", Toast.LENGTH_SHORT
            ).show()
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

    private fun haveAllPermissions(permissions: MutableList<String>): Boolean {
        for (permission in permissions) {
            if (PackageManager.PERMISSION_GRANTED != checkSelfPermission(permission)) {
                Log.i(logTag, "FALSE haveAllPermissions()")
                return false
            }
        }

        Log.i(logTag, "TRUE haveAllPermissions()")
        return true
    }

    private val settingsLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    private fun goToAndroidAppSettings() {
        Log.i(logTag, "goToAndroidAppSettings()")
        val thisAppSettings = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse(
                "package:$packageName"
            )
        )
        thisAppSettings.addCategory(Intent.CATEGORY_DEFAULT)
        thisAppSettings.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        settingsLauncher.launch(thisAppSettings)
    }

    private lateinit var permissionIn: String

    private val permissionLauncher: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(
                this, "$permissionIn permission granted!", Toast.LENGTH_SHORT
            ).show()
        } else {
//            AlertDialog.Builder(this).setTitle("$permissionIn permission required")
//                .setMessage("This permission is needed for this app to work")
//                .setPositiveButton("Open settings") { _, _ ->
//                    goToAndroidAppSettings()
//                }.create().show()
            Toast.makeText(
                this, "$permissionIn required for app to work", Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun getPermissions() {
        Log.i(logTag, "getPermissions()")
        for (permission in requiredPermissions) {
            if (PackageManager.PERMISSION_GRANTED != checkSelfPermission(permission)) {
                permissionIn = permission
                permissionLauncher.launch(
                    permissionIn
                )
            }
        }

        Log.i(logTag, "done getPermissions()")
    }

    private fun closeMediaPlayer() {
        mediaPlayerManager?.closeMediaPlayer()
    }

    private fun setUpMediaPlayer(selectedMediaToPlayUri: Uri) {
        Log.d(logTag, "setUpMediaPlayer: selectedMediaToPlayUri = $selectedMediaToPlayUri")
        mediaPlayerManager?.setUpMediaPlayer(selectedMediaToPlayUri)
    }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Log.d(logTag, "RESULT_OK with data: ${result.data}")
                result.data?.data?.let { selectedMediaToPlayUri ->
                    Log.i(logTag, "Selected file URI: $selectedMediaToPlayUri")
                    setUpMediaPlayer(selectedMediaToPlayUri)
                }

            } else {
                Log.i(logTag, "ERROR selecting file: ${result.resultCode}")
            }
        }

    private fun pickAndPlayFile() {
        Log.i(logTag, "pickAndPlayFile()")
        val recordingsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS)
        val initialUri = Uri.fromFile(recordingsDir)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        filePickerLauncher.launch(intent)
    }

}
