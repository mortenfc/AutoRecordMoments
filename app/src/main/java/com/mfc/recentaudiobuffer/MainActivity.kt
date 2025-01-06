package com.mfc.recentaudiobuffer

import MyMediaPlayerController
import MyMediaController
import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SharedViewModel : ViewModel() {
    var myBufferService: MyBufferServiceInterface? = null
}

class MainActivity : AppCompatActivity() {
    private val logTag = "MainActivity"
    private val sharedViewModel: SharedViewModel by viewModels()
    private lateinit var myBufferService: MyBufferServiceInterface

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= 33) {
        mutableListOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE
        )
    } else {
        mutableListOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE
        )
    }

    companion object {
        const val CHRONIC_NOTIFICATION_ID = 1
        private const val CHRONIC_NOTIFICATION_CHANNEL_ID = "recording_channel"
        private const val CHRONIC_NOTIFICATION_CHANNEL_NAME = "Recording Into RingBuffer"
        private const val CHRONIC_NOTIFICATION_CHANNEL_DESCRIPTION =
            "Channel for the persistent recording notification banner"
        private const val REQUEST_CODE_STOP = 1
        private const val REQUEST_CODE_START = 2
        private const val REQUEST_CODE_SAVE = 3
    }

    private lateinit var mediaPlayerViewModel: MediaPlayerViewModel
    private var mediaPlayerController: MyMediaPlayerController? = null
    private var mediaController: MyMediaController? = null

    private var frameLayout: FrameLayout? = null

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
            observeLiveData()
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
        setContent {
            MaterialTheme {
                MainScreen(
                    onStartBufferingClick = { onClickStartRecording() },
                    onStopBufferingClick = { onClickStopRecording() },
                    onResetBufferClick = { onClickResetBuffer() },
                    onSaveBufferClick = { onClickSaveBuffer() },
                    onPickAndPlayFileClick = { onClickPickAndPlayFile() },
                    onDonateClick = { onClickDonate() },
                    onSettingsClick = { onClickSettings() }
                )
            }
        }

        Log.i(logTag, "onCreate(): Build.VERSION.SDK_INT: ${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT >= 34) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        }
        if (Build.VERSION.SDK_INT < 33) {
            requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        foregroundServiceAudioBuffer = Intent(this, MyBufferService::class.java)

        getPermissions()

        frameLayout = FrameLayout(this).apply { // Initialize frameLayout here
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        addContentView(frameLayout, frameLayout?.layoutParams)

        mediaController = MyMediaController(this)

        ContextCompat.registerReceiver(
            this,
            NotificationActionReceiver(),
            IntentFilter().apply {
                addAction(NotificationActionReceiver.ACTION_STOP_RECORDING)
                addAction(NotificationActionReceiver.ACTION_START_RECORDING)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ViewModelHolder.setSharedViewModel(sharedViewModel)
    }

//    override fun onCreateOptionsMenu(menu: Menu): Boolean {
//        menuInflater.inflate(R.menu.main_menu, menu)
//
//        val settingsItem = menu.findItem(R.id.action_settings)
//        val actionView = settingsItem.actionView as LinearLayout
//        val iconView = actionView.findViewById<ImageView>(R.id.menu_icon)
//
//        iconView.setImageResource(R.drawable.baseline_settings_24)
//        iconView.setColorFilter(ContextCompat.getColor(this, R.color.white), PorterDuff.Mode.SRC_IN)
//
//        actionView.setOnClickListener {
//            Intent(this, SettingsActivity::class.java).also {
//                it.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
//                startActivity(it)
//            }
//        }
//
//        return true
//    }

//    override fun onOptionsItemSelected(item: MenuItem): Boolean {
//        return when (item.itemId) {
//            else -> super.onOptionsItemSelected(item) // Handle other menu items or let the superclass handle it
//        }
//    }

    private fun onClickSettings() {
        Intent(this, SettingsActivity::class.java).also {
            it.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            startActivity(it)
        }
    }

    override fun onStop() {
        super.onStop()
        closeMediaPlayer()
        Log.i(logTag, "onStop() finished")
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
            } else if (myBufferService.isRecording.get()) {
                Toast.makeText(
                    this, "Buffer is already running!", Toast.LENGTH_SHORT
                ).show()
            } else {
                myBufferService.startRecording()
                observeLiveData()
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
            if (myBufferService.isRecording.get()) {
                Log.i(logTag, "Stopping recording in MyBufferService")
                myBufferService.stopRecording()
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
            myBufferService.resetBuffer()
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
                    this@MainActivity,
                    grantedDirectoryUri
                )
                FileSavingUtils.promptSaveFileName(
                    this@MainActivity,
                    grantedDirectoryUri,
                    myBufferService.getBuffer()
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
                        this@MainActivity,
                        grantedUri,
                        myBufferService.getBuffer()
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
    }

    private fun createNotificationChannels() {
        val chronicChannel = NotificationChannel(
            CHRONIC_NOTIFICATION_CHANNEL_ID,
            CHRONIC_NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = CHRONIC_NOTIFICATION_CHANNEL_DESCRIPTION
        }
        val resultChannel = NotificationChannel(
            FileSavingService.RESULT_NOTIFICATION_CHANNEL_ID,
            FileSavingService.RESULT_NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = FileSavingService.RESULT_NOTIFICATION_CHANNEL_DESCRIPTION
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.createNotificationChannels(listOf(chronicChannel, resultChannel))
    }

    private fun observeLiveData() {
        myBufferService.isRecording.observe(this@MainActivity, Observer { _ ->
            updateRecordingNotification()
        })

        myBufferService.hasOverflowed.observe(this@MainActivity, Observer { _ ->
            updateRecordingNotification()
        })

        myBufferService.recorderIndex.observe(this@MainActivity, Observer { _ ->
            updateRecordingNotification()
        })

        myBufferService.totalRingBufferSize.observe(
            this@MainActivity,
            Observer { _ ->
                updateRecordingNotification()
            })

        myBufferService.time.observe(this@MainActivity, Observer { _ ->
            updateRecordingNotification()
        })
    }

    private fun updateRecordingNotification() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            powerManager.isScreenOn
        }

        if (!isScreenOn) {
            return
        }

        createNotificationChannels()

        val stopIntent = PendingIntent.getBroadcast(
            this, REQUEST_CODE_STOP, Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_STOP_RECORDING
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val startIntent = PendingIntent.getBroadcast(
            this, REQUEST_CODE_START, // Define this request code
            Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_START_RECORDING
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val saveIntent = PendingIntent.getBroadcast(
            this,
            REQUEST_CODE_SAVE,
            Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_SAVE_RECORDING
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val recordingNotification =
            NotificationCompat.Builder(this, CHRONIC_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Recording Recent Audio")
                .setContentText(if (myBufferService.isRecording.get()) "Running...\n" else "Stopped.\n")
                .setContentText(
                    "${
                        if (myBufferService.hasOverflowed.get()) "100%" else "${
                            ((myBufferService.recorderIndex.get()
                                .toFloat() / myBufferService.totalRingBufferSize.get()) * 100).roundToInt()
                        }%"
                    } - ${myBufferService.time.get()}"
                ).setSmallIcon(R.drawable.baseline_record_voice_over_24)
                .setProgress(  // Bar visualization
                    myBufferService.totalRingBufferSize.get(),
                    if (myBufferService.hasOverflowed.get()) {
                        myBufferService.totalRingBufferSize.get()
                    } else {
                        myBufferService.recorderIndex.get()
                    }, false
                )
                .addAction(
                    if (myBufferService.isRecording.get()) R.drawable.baseline_mic_24 else R.drawable.baseline_mic_off_24,
                    if (myBufferService.isRecording.get()) "Pause" else "Continue", // Update action text
                    if (myBufferService.isRecording.get()) stopIntent else startIntent // Update PendingIntent
                ).setAutoCancel(false)
                .addAction(
                    if (myBufferService.isRecording.get()) R.drawable.baseline_save_alt_24 else 0,
                    if (myBufferService.isRecording.get()) "Save and clear" else null,
                    if (myBufferService.isRecording.get()) saveIntent else null
                ).setAutoCancel(false) // Keep notification after being tapped
                .setSmallIcon(R.drawable.baseline_record_voice_over_24) // Set the small icon
                .setOngoing(true) // Make it a chronic notification
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_STATUS).build()

        with(NotificationManagerCompat.from(this)) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notify(CHRONIC_NOTIFICATION_ID, recordingNotification)
            } else {
                // Not possible as notification is created in start which requires all permissions
            }
        }
    }

    private fun closeMediaPlayer() {
        mediaPlayerController?.let {
            if (it.isPlaying) {
                it.pause()
            }
        }
        mediaController?.setAllowHiding(true)
        mediaController?.hide()
//        mediaController?.setAllowHiding(false) // Would prevent back button from hiding it
        mediaPlayerController = null
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

    private fun goToSettings() {
        Log.i(logTag, "goToSettings()")
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
            AlertDialog.Builder(this).setTitle("$permissionIn permission required")
                .setMessage("This permission is needed for this app to work")
                .setPositiveButton("Open settings") { _, _ ->

                }.create().show()
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

    private fun setUpMediaPlayer(selectedMediaToPlayUri: Uri) {
        mediaPlayerViewModel = ViewModelProvider(this)[MediaPlayerViewModel::class.java]
        val audioAttributes =
            AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

        mediaPlayerViewModel.createMediaPlayer(
            this, selectedMediaToPlayUri, audioAttributes
        ) {
            mediaPlayerController = MyMediaPlayerController(mediaPlayerViewModel.mediaPlayer!!)
            mediaController?.setMediaPlayer(mediaPlayerController)
            mediaController?.setAnchorView(frameLayout)
            mediaController?.isEnabled = true
            mediaController?.show(0) // Show indefinitely

            // Update the duration display initially and periodically
            updateDurationDisplay()

            mediaPlayerViewModel.mediaPlayer?.setOnCompletionListener {
                // Stop updating duration when playback completes
                durationUpdateHandler.removeCallbacks(updateDurationRunnable)
            }
        }
    }

    private val durationUpdateHandler = Handler(Looper.getMainLooper())
    private val updateDurationRunnable = object : Runnable {
        override fun run() {
            val currentPosition = mediaPlayerController?.currentPosition ?: 0
            val playedDuration = mediaPlayerController?.duration ?: 0
            mediaController?.getUpdateTime(currentPosition, playedDuration)

            durationUpdateHandler.postDelayed(this, 100) // Update every 0.1 second
        }
    }

    private fun updateDurationDisplay() {
        val duration = mediaPlayerController?.duration ?: 0
        mediaController?.getUpdateTime(0, duration) // Initial update

        durationUpdateHandler.post(updateDurationRunnable) // Start periodic updates
    }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { selectedMediaToPlayUri ->
                    Log.i(logTag, "Selected file URI: $selectedMediaToPlayUri")
                    setUpMediaPlayer(selectedMediaToPlayUri)
                }
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
        }
        filePickerLauncher.launch(intent)
    }

}
