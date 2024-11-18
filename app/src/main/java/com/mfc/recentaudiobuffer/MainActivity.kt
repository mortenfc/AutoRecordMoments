package com.mfc.recentaudiobuffer

import MyMediaPlayerController
import MyMediaController
import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
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
import java.lang.Exception
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
import java.util.concurrent.atomic.AtomicBoolean

class SharedViewModel : ViewModel(), RecordingStateListener {
    var myBufferService: MyBufferServiceInterface? = null
    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> = _isRecording

    override fun onRecordingStateChanged(isRecording: Boolean) {
        updateRecordingState(isRecording)
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
                    if (saveBufferToFile(myBufferService.getBuffer())) {
                        Log.d(logTag, "Saved buffer, reset it")
                        onClickResetBuffer()
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

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "recording_channel"
        private const val CHANNEL_NAME = "Recording Into Ringbuffer"
        private const val CHANNEL_DESCRIPTION = "Buffering recorder in the background"
        private const val REQUEST_CODE_STOP = 1
        private const val REQUEST_CODE_START = 2
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

        val recordingNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording Audio") // Set the title
            .setContentText(if (myBufferService.isRecording()) "Running...\n" else "Stopped.\n") // Update
            .addAction(
                if (myBufferService.isRecording()) R.drawable.baseline_mic_24 else R.drawable.baseline_mic_off_24, // Update icon
                if (myBufferService.isRecording()) "Stop" else "Start", // Update action text
                if (myBufferService.isRecording()) stopIntent else startIntent // Update PendingIntent
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

    private fun saveFile(data: ByteArray, uri: Uri?): Boolean {
        var success = false
        uri?.let {
            try {
                val outputStream = contentResolver.openOutputStream(it)
                outputStream?.let { stream ->
                    try {
                        myBufferService.writeWavHeader(
                            stream, data.size.toLong()
                        )
                        stream.write(data)
                        stream.flush()
                        Snackbar.make(
                            findViewById(R.id.RootView),
                            "File saved successfully",
                            Snackbar.LENGTH_SHORT
                        ).show()

                        success = true
                    } catch (e: IOException) {
                        Log.e(logTag, "Error writing data to stream", e)
                        Snackbar.make(
                            findViewById(R.id.RootView),
                            "ERROR: File failed to save fully",
                            Snackbar.LENGTH_LONG
                        ).show()
                    } finally {
                        stream.close()
                    }
                } ?: run {
                    Log.e(logTag, "Failed to open output stream for $uri")
                    Snackbar.make(
                        findViewById(R.id.RootView),
                        "ERROR: Failed to open output stream",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            } catch (e: IOException) {
                Log.e(logTag, "Failed to save file to $uri", e)
                Snackbar.make(
                    findViewById(R.id.RootView), "Failed to save file", Snackbar.LENGTH_SHORT
                ).show()
            } catch (e: SecurityException) {
                Log.e(logTag, "Permission denied to access $uri", e)
                Snackbar.make(
                    findViewById(R.id.RootView), "Permission denied", Snackbar.LENGTH_SHORT
                ).show()
            }
        } ?: run {
            Log.e(logTag, "No URI received from directory chooser")
        }

        return success
    }

    private fun cacheGrantedUri(context: Context, uri: Uri) {
        val sharedPrefs = context.getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("grantedUri", uri.toString()).apply()
    }

    private fun getCachedGrantedUri(context: Context): Uri? {
        val sharedPrefs = context.getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        val uriString = sharedPrefs.getString("grantedUri", null)
        return if (uriString != null) Uri.parse(uriString) else null
    }

    private fun fileExists(fileUri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(fileUri)?.use { true } ?: false
        } catch (e: FileNotFoundException) {
            false
        } catch (e: Exception) {
            // Handle other exceptions, e.g., SecurityException
            Log.e("fileExists", "Error checking file existence: ${e.message}", e)
            false
        }
    }

    private var isGrantedUriSet = AtomicBoolean(false)
    private val grantedUriMutex = Mutex()
    private var grantedDirectoryUri: Uri? = null

    private val saveStorageUriPermission =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { directoryUri: Uri? ->
            directoryUri?.let {
                lifecycleScope.launch { // Launch a new coroutine
                    grantedUriMutex.withLock { // Now allowed inside the coroutine
                        grantedDirectoryUri = it // Save the permission to save files on this uri
                        isGrantedUriSet.set(true)
                    }
                }
            }
        }

    @SuppressLint("SetTextI18n")
    private suspend fun saveBufferToFile(data: ByteArray): Boolean {
        var success = false
        if (grantedDirectoryUri == null) {
            grantedDirectoryUri =
                Uri.fromFile(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS))
            saveStorageUriPermission.launch(grantedDirectoryUri)
            grantedUriMutex.withLock {
                while (!isGrantedUriSet.get()) {
                    delay(10)
                }
            }
            cacheGrantedUri(this, grantedDirectoryUri!!)
            isGrantedUriSet.set(false)
        } else {
            grantedDirectoryUri = getCachedGrantedUri(ContextWrapper(this))
        }

        Log.i(logTag, "saveBufferToFile()")

        grantedDirectoryUri?.let { grantedUri ->
            // 1. Prompt for filename here
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Enter Filename")
            val input = EditText(this)
            input.setText("recording.wav") // Default filename
            builder.setView(input)
            builder.setPositiveButton("OK") { _, _ ->
                val inputFilename = input.text.toString()

                var filename = if (inputFilename.endsWith(".wav", ignoreCase = true)) {
                    inputFilename // Already ends with .wav, no change needed
                } else {
                    val baseName = inputFilename.substringBeforeLast(".") // Extract base name
                    "$baseName.wav" // Append .wav to the base name
                }

                val timestamp =
                    SimpleDateFormat("yy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                filename = "${filename.substringBeforeLast(".")}_${timestamp}.wav"

                val fileUri = grantedUri.buildUpon().appendPath(filename).build()
                if (fileExists(fileUri)) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Somehow this filename and timestamp already existed, overwrote it: $filename",
                            Toast.LENGTH_SHORT
                        ).show()
                        Log.e(
                            logTag,
                            "Somehow this filename and timestamp already existed, overwrote it: $filename"
                        )
                    }
                }

                fileUri?.let {
                    success = saveFile(data, it)
                } ?: run {
                    // Handle file creation failure
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity, "Error creating file", Toast.LENGTH_SHORT
                        ).show()
                        Log.e(logTag, "Failed to create file: $filename")
                    }
                }
//                }
            }
            builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
            builder.show()

        } ?: run {
            // Handle case where directory access is not granted
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity, "Directory access not granted", Toast.LENGTH_SHORT
                ).show()
                // You might want to request permission again or guide the user to settings
            }
        }

        return success
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
