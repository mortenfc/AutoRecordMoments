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
import android.graphics.PorterDuff
import android.media.AudioAttributes
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.widget.Toolbar
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority


class SharedViewModel : ViewModel(), RecordingStateListener {
    var myBufferService: MyBufferServiceInterface? = null
    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> = _isRecording
    private val _recordingDuration = MutableLiveData<String>("00:00:00")
    val recordingDuration: LiveData<String> = _recordingDuration

    override fun onRecordingStateChanged(isRecording: Boolean) {
        updateRecordingState(isRecording)
    }

    override fun onRecordingDurationChange(duration: String) {
        updateRecordingDurationChange(duration)
    }

    private fun  updateRecordingDurationChange(duration: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _recordingDuration.value = duration // Update directly on main thread
        } else {
            _recordingDuration.postValue(duration) // Update safely from background thread
        }
    }

    private fun updateRecordingState(isRecording: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _isRecording.value = isRecording // Update directly on main thread
        } else {
            _isRecording.postValue(isRecording) // Update safely from background thread
        }
    }
}


class MainActivity : AppCompatActivity() {

    public val DEFAULT_UPDATE_INTERVAL: Long = 30L
    public val FAST_UPDATE_INTERVAL: Long = 5L


    private val logTag = "MainActivity"
    private val sharedViewModel: SharedViewModel by viewModels()
    private lateinit var myBufferService: MyBufferServiceInterface
    private lateinit var saveStorageUriPermission: ActivityResultLauncher<Uri?>

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= 33) {
        mutableListOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE,
            // Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            // Manifest.permission.ACCESS_FINE_LOCATION,
            // Manifest.permission.ACCESS_COARSE_LOCATION
        )
    } else {
        mutableListOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE
        )
    }

    companion object {
        const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "recording_channel"
        private const val CHANNEL_NAME = "Recording Into Ringbuffer"
        private const val CHANNEL_DESCRIPTION = "Buffering recorder in the background"
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
            sharedViewModel.myBufferService = this.service
            this.isBound = true
            sharedViewModel.isRecording.observe(this@MainActivity) {
                createRecordingNotification()
            }
            sharedViewModel.myBufferService?.recordingStateListener = sharedViewModel
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
        setContentView(R.layout.layout)

        val locationRequest = LocationRequest.Builder(10000)
            .setWaitForAccurateLocation(true)
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setIntervalMillis(1000 * DEFAULT_UPDATE_INTERVAL)
            .setMinUpdateIntervalMillis(5000)
            .setMaxUpdateDelayMillis(15000)
            .setMinUpdateDistanceMeters(10f)
            .build()







        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        Log.i(logTag, "onCreate(): Build.VERSION.SDK_INT: ${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT >= 34) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        }
        if (Build.VERSION.SDK_INT < 33) {
            requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        foregroundServiceAudioBuffer = Intent(this, MyBufferService::class.java)

        getPermissions()

        saveStorageUriPermission =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { grantedDirectoryUri: Uri? ->
                if (grantedDirectoryUri != null) {
                    lifecycleScope.launch {
                        FileSavingUtils.cacheGrantedUri(
                            this@MainActivity,
                            grantedDirectoryUri
                        )
                        if (FileSavingUtils.promptSaveFileName(
                                this@MainActivity,
                                grantedDirectoryUri,
                                myBufferService.getBuffer()
                            )
                        ) {
                            Log.d(logTag, "Saved buffer, reset it")
                            onClickResetBuffer()
                        }
                    }
                } else {
                    // Handle case where grantedUri is null
                    Toast.makeText(this, "Error getting granted URI", Toast.LENGTH_SHORT).show()
                }
            }

        findViewById<Button>(R.id.StartBuffering).setOnClickListener {
            onClickStartRecording()
        }
        findViewById<Button>(R.id.StopBuffering).setOnClickListener {
            onClickStopRecording()
        }
        findViewById<Button>(R.id.ResetBuffer).setOnClickListener {
            onClickResetBuffer()
        }
        findViewById<Button>(R.id.SaveBuffer).setOnClickListener {
            if (foregroundServiceAudioBufferConnection.isBound) {
                lifecycleScope.launch {
                    onClickStopRecording()
                    val grantedUri = FileSavingUtils.getCachedGrantedUri(this@MainActivity)
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
        findViewById<Button>(R.id.PickAndPlayFile).setOnClickListener {
            if (haveAllPermissions(requiredPermissions)) {
                pickAndPlayFile()
            } else {
                getPermissions()
                Toast.makeText(
                    this, "Accept the permissions and then try again", Toast.LENGTH_LONG
                ).show()
            }
        }
        val showMap: Button = findViewById(R.id.show_map)
        showMap.setOnClickListener {
            val i = Intent(this, MapsActivity::class.java)
            startActivity(i)
        }

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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)

        val settingsItem = menu.findItem(R.id.action_settings)
        val actionView = settingsItem.actionView as LinearLayout
        val iconView = actionView.findViewById<ImageView>(R.id.menu_icon)

        iconView.setImageResource(R.drawable.baseline_settings_24)
        iconView.setColorFilter(ContextCompat.getColor(this, R.color.white), PorterDuff.Mode.SRC_IN)

        actionView.setOnClickListener {
            Intent(this, SettingsActivity::class.java).also {
                it.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                startActivity(it)
            }
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            else -> super.onOptionsItemSelected(item) // Handle other menu items or let the superclass handle it
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
                Log.i(logTag, "Created chronic notification")
            } else if (myBufferService.isRecording()) {
                Toast.makeText(
                    this, "Buffer is already running!", Toast.LENGTH_SHORT
                ).show()
            } else {
                myBufferService.startRecording()
                sharedViewModel.isRecording.observe(this) {
                    createRecordingNotification()
                }
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
            if (myBufferService.isRecording()) {
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

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = CHANNEL_DESCRIPTION
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createRecordingNotification() {
        createNotificationChannel()

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

        val recordingNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording Recent Audio")
            .setContentText(if (myBufferService.isRecording()) "Running...\n" else "Stopped.\n")
            .setContentText(
                "${if (hasOverflowed) "100%" else "${recorderIndex / totalRingbufferSize * 100}%"} - ${getLengthInTime()}"
            ).setSmallIcon(R.drawable.baseline_record_voice_over_24)
            .setProgress(  // Bar visualization
                totalRingbufferSize, if (hasOverflowed) {
                    totalRingbufferSize
                } else {
                    recorderIndex
                }, false)
            .addAction(
                if (myBufferService.isRecording()) R.drawable.baseline_mic_24 else R.drawable.baseline_mic_off_24,
                if (myBufferService.isRecording()) "Stop" else "Restart", // Update action text
                if (myBufferService.isRecording()) stopIntent else startIntent // Update PendingIntent
            ).setAutoCancel(false)
            .addAction(
                if (myBufferService.isRecording()) R.drawable.baseline_save_alt_24 else 0,
                if (myBufferService.isRecording()) "Save and clear" else null,
                if (myBufferService.isRecording()) saveIntent else null
            ).setAutoCancel(false) // Keep notification after being tapped
            .setSmallIcon(R.drawable.baseline_record_voice_over_24) // Set the small icon
            .setOngoing(true) // Make it a chronic notification
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS).build()

        with(NotificationManagerCompat.from(this)) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notify(NOTIFICATION_ID, recordingNotification)
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
                    goToSettings()
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
