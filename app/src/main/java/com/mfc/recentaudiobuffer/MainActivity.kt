package com.mfc.recentaudiobuffer

import MediaPlayerManager
import android.Manifest
import android.app.Application
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
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.telecom.TelecomManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
@UnstableApi
class MainActivity : AppCompatActivity() {
    private val logTag = "MainActivity"

    @Inject
    lateinit var authenticationManager: AuthenticationManager
    private val settingsViewModel: SettingsViewModel by viewModels()
    private var myBufferService: MyBufferServiceInterface? = null
    private var isPickAndPlayFileRunning = false
    private var wasStartRecordingButtonPress = false

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= 33) {
        mutableListOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MANAGE_OWN_CALLS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
    } else {
        mutableListOf(
            Manifest.permission.ACCESS_NOTIFICATION_POLICY,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MANAGE_OWN_CALLS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
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
            RecentAudioBufferApplication.getSharedViewModel(this@MainActivity.applicationContext as Application).myBufferService =
                this.service
            if (wasStartRecordingButtonPress) {
                myBufferService?.startRecording()
                wasStartRecordingButtonPress = false
            }
            Log.d(logTag, "onServiceConnect()")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            Log.e(logTag, "onServiceDisconnected unexpectedly called")
            isBound = false
            // Try to rebind the service
            this@MainActivity.startForegroundService(foregroundServiceAudioBuffer)
            bindService(
                Intent(this@MainActivity, MyBufferService::class.java), this, BIND_AUTO_CREATE
            )
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.e(logTag, "onBindingDied unexpectedly called")
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
                Log.i("MainActivity", "directoryPickerLauncher: $it")
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                FileSavingUtils.cacheGrantedUri(it)
                // If we have a buffer, prompt to save it
                if (myBufferService != null) {
                    FileSavingUtils.promptSaveFileName(
                        this@MainActivity, it, myBufferService!!.getBuffer()
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
        directoryPickerLauncher.launch(intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI))
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
        Log.i(logTag, "onCreate(): Build.VERSION.SDK_INT: ${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT >= 34) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        }
        if (Build.VERSION.SDK_INT < 33) {
            requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        foregroundServiceAudioBuffer = Intent(this, MyBufferService::class.java)

        createNotificationChannels()

        mediaPlayerManager = MediaPlayerManager(context = this, onPlayerReady = {
            Log.i(logTag, "Player is ready")
        })

        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

        setContent {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = "main_screen") {
                composable("main_screen") {
                    MainScreen(
                        navController = navController,
                        signInButtonText = authenticationManager.signInButtonText,
                        onSignInClick = { authenticationManager.onSignInClick() },
                        onStartBufferingClick = { onClickStartRecording() },
                        onStopBufferingClick = { onClickStopRecording() },
                        onResetBufferClick = { onClickResetBuffer() },
                        onSaveBufferClick = { onClickSaveBuffer() },
                        onPickAndPlayFileClick = { onClickPickAndPlayFile() },
                        onDonateClick = { onClickDonate() },
                        onSettingsClick = { onClickSettings() },
                        onDirectoryAlertDismiss = {
                            FileSavingUtils.showDirectoryPermissionDialog = false
                            pickDirectory()
                        },
                        mediaPlayerManager = mediaPlayerManager!!
                    )
                }
                composable("call_screen") {
                    CallScreen(
                        onNavigateToMain = { navController.navigate("main_screen") },
                        telecomManager = telecomManager
                    )
                }
            }
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
        Log.i(
            logTag,
            "onStart() called with isPickAndPlayFileRunning: $isPickAndPlayFileRunning \nIntent.action: ${intent.action}"
        )
        authenticationManager.registerLauncher(this)
        if (isMyBufferServiceRunning(this)) {
            // Rebind to the existing service
            Log.i(logTag, "Rebinding to existing service")
            Intent(this, MyBufferService::class.java).also { intent ->
                bindService(intent, foregroundBufferServiceConn, Context.BIND_AUTO_CREATE)
            }
        } else {
            // Start and bind to a new service
            Log.i(logTag, "Starting and binding to a new service")
            val serviceIntent = Intent(this, MyBufferService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            bindService(serviceIntent, foregroundBufferServiceConn, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(logTag, "onNewIntent() called")
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        closeMediaPlayer()
        Log.i(logTag, "onStop() finished")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (foregroundBufferServiceConn.isBound) {
            unbindService(foregroundBufferServiceConn)
            foregroundBufferServiceConn.isBound = false
        }
        Log.i(logTag, "onDestroy() finished")
    }

    private fun handleIntent(intent: Intent) {
        // Check if the activity was launched from the notification
        if (intent.action == FileSavingService.ACTION_OPEN_FILE && !isPickAndPlayFileRunning) {
            Log.d(logTag, "Got external intent to open file")
            val savedFileUri =
                intent.getParcelableExtra<Uri>(FileSavingService.EXTRA_SAVED_FILE_URI)
            if (savedFileUri != null) {
                isPickAndPlayFileRunning = true
                pickAndPlayFile(savedFileUri)
            } else {
                Log.e(logTag, "savedFileUri is null")
            }
        }
    }

    private fun isMyBufferServiceRunning(context: Context): Boolean {
        val manager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (MyBufferService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun onClickStartRecording() {
        if (haveAllPermissions(requiredPermissions)) {
            if (!foregroundBufferServiceConn.isBound) {
                wasStartRecordingButtonPress = true
                this.startForegroundService(foregroundServiceAudioBuffer)
                bindService(
                    Intent(this, MyBufferService::class.java),
                    foregroundBufferServiceConn,
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
        if (foregroundBufferServiceConn.isBound) {
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
        if (foregroundBufferServiceConn.isBound) {
            myBufferService!!.resetBuffer()
        } else {
            Toast.makeText(this, "ERROR: Buffer service is not running. ", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun onClickSaveBuffer() {
        if (foregroundBufferServiceConn.isBound) {
            lifecycleScope.launch {
                onClickStopRecording()
                val prevGrantedUri = FileSavingUtils.getCachedGrantedUri()
                Log.d(logTag, "MainActivity: prevGrantedUri = $prevGrantedUri")
                if (FileSavingUtils.isUriValidAndAccessible(this@MainActivity, prevGrantedUri)) {
                    // Use previously permitted cached uri
                    FileSavingUtils.promptSaveFileName(
                        this@MainActivity, prevGrantedUri!!, myBufferService!!.getBuffer()
                    )
                } else {
                    // Otherwise get and store file saving location permission
                    pickDirectory()
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


    private lateinit var permissionIn: String

    private val permissionLauncher: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(
                this, "$permissionIn permission granted!", Toast.LENGTH_SHORT
            ).show()
        } else {
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
            // Prevent duplicate file pickers simultaneously from external intents
            isPickAndPlayFileRunning = false
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

    private fun pickAndPlayFile(initialUri: Uri? = null) {
        Log.i(logTag, "pickAndPlayFile() with initialUri: $initialUri")
        val intent = if (initialUri != null) {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            val recordingsDirFile =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS)
            val recordingsDirUrl = Uri.fromFile(recordingsDirFile)
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, recordingsDirUrl)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        filePickerLauncher.launch(intent)
    }

}
