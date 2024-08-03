package com.example.recentaudiobuffer

import MediaPlayerControllerImpl
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.lang.Exception
import android.os.Build
import com.google.android.material.snackbar.Snackbar
import java.io.IOException

class MainActivity : ComponentActivity() {
    private val logTag = "MainActivity"

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.FOREGROUND_SERVICE
    )

    private var mediaController: MyMediaController? = null
    private var mediaPlayer: MediaPlayer? = null
    private var mediaPlayerControl: MediaPlayerControllerImpl? = null

    private lateinit var foregroundServiceAudioBuffer: Intent
    private val foregroundServiceAudioBufferConnection = object : ServiceConnection {
        lateinit var service: MainActivityInterface
        var isBound: Boolean = false

        override fun onServiceConnected(className: ComponentName, ibinder: IBinder) {
            val binder = ibinder as MyBufferService.MyBinder
            this.service = binder.getService()
            isBound = true
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

        fun unBind() {
            unbindService(this)
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        setContent {
//            RecentAudioBufferTheme {
//                // A surface container using the 'background' color from the theme
//                Surface(
//                    modifier = Modifier.fillMaxSize(),
//                    color = MaterialTheme.colorScheme.background
//                ) {
//                    Greeting("Android")
//                }
//            }
//        }
        setContentView(R.layout.layout)

        Log.i(logTag, "onCreate(): Build.VERSION.SDK_INT: ${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT >= 34) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        }

        foregroundServiceAudioBuffer = Intent(this, MyBufferService::class.java)

        getPermissions()

        val start: Button = findViewById(R.id.StartBuffering)
        start.setOnClickListener {
            if (haveAllPermissions(requiredPermissions)) {
                if (!foregroundServiceAudioBufferConnection.isBound) {
                    this.startForegroundService(foregroundServiceAudioBuffer)
                    bindService(
                        Intent(this, MyBufferService::class.java),
                        foregroundServiceAudioBufferConnection,
                        Context.BIND_AUTO_CREATE
                    )
                    Log.i(logTag, "Buffer service started and bound")
                    Toast.makeText(
                        this,
                        "Started buffering in the background",
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (foregroundServiceAudioBufferConnection.service.isRecording()) {
                    Toast.makeText(
                        this,
                        "Buffer is already running!",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Log.e(logTag, "Buffer service bound but not running")
                    Toast.makeText(
                        this,
                        "ERROR: Buffer service bound but not running",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                getPermissions()
                Toast.makeText(
                    this,
                    "Accept the permissions and then start again",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val stop: Button = findViewById(R.id.StopBuffering)
        stop.setOnClickListener {
            if (haveAllPermissions(requiredPermissions)) {
                if (foregroundServiceAudioBufferConnection.isBound) {
                    Log.i(logTag, "Stopping bound MyBufferService by unbinding it")
                    foregroundServiceAudioBufferConnection.unBind()
                    Toast.makeText(
                        this,
                        "Stopped buffering in the background",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Log.e(logTag, "Buffer service is not running")
                    Toast.makeText(
                        this,
                        "Buffer is not running",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                getPermissions()
            }
        }

        val save: Button = findViewById(R.id.SaveBuffer)
        save.setOnClickListener {
            if (foregroundServiceAudioBufferConnection.isBound) {
                saveBufferToFile(foregroundServiceAudioBufferConnection.service.getBuffer())
            } else {
                Toast.makeText(
                    this,
                    "Buffer is not running. It has to be running to save it!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val pickAndPlay: Button = findViewById(R.id.PickAndPlayFile)
        pickAndPlay.setOnClickListener {
            pickAndPlayFile()
        }
    }

    override fun onStop() {
        super.onStop()
        mediaPlayerControl?.let {
            mediaController?.setMediaPlayer(null) // Detach MediaController
            mediaController?.hide()
            mediaController = null
        }
        mediaPlayer?.release()
        mediaPlayer = null
        mediaPlayerControl = null

        if (foregroundServiceAudioBufferConnection.isBound) {
            unbindService(foregroundServiceAudioBufferConnection)
            foregroundServiceAudioBufferConnection.isBound = false // Update the flag
        }
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
    ) {
    }

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
                this,
                "$permissionIn permission granted!",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("$permissionIn permission required")
                .setMessage("This permission is needed for this app to work")
                .setPositiveButton("Open settings") { _, _ ->
                    goToSettings()
                }
                .create().show()
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

    private lateinit var dataIn: ByteArray
    private val saveFileInDirectoryLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            uri?.let {
                try {
                    val outputStream = contentResolver.openOutputStream(it)
                    outputStream?.let { stream ->
                        // Check MIME type (optional, consider more robust methods)
                        val mimeType = contentResolver.getType(it)
                        if (mimeType == "audio/wav" || it.path?.endsWith(".wav") == true) {
                            try {
                                foregroundServiceAudioBufferConnection.service.writeWavHeader(
                                    stream, dataIn.size.toLong()
                                )
                                stream.write(dataIn)
                                stream.flush()
                                Snackbar.make(
                                    findViewById(R.id.RootView),
                                    "File saved successfully",
                                    Snackbar.LENGTH_SHORT
                                ).show()
                            }
                            catch (e: IOException) {
                                Log.e(logTag, "Error writing data to stream", e)
                                Snackbar.make(
                                    findViewById(R.id.RootView),
                                    "ERROR: File failed to save fully",
                                    Snackbar.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            Snackbar.make(
                                findViewById(R.id.RootView),
                                "Invalid file type. Please save as .wav",
                                Snackbar.LENGTH_SHORT
                            ).show()
                        }
                        stream.close()
                    } ?: run {
                        // Handle case where output stream is null
                        Log.e(logTag, "Failed to open output stream for $uri")
                        Snackbar.make(
                             findViewById(R.id.RootView),
                            "ERROR: Failed to open output stream",
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                } catch (e: IOException) {
                    Log.e(logTag, "Failed to save file to $uri", e)
                    Snackbar.make(findViewById(R.id.RootView), "Failed to save file", Snackbar.LENGTH_SHORT).show()
                } catch (e: SecurityException) {Log.e(logTag, "Permission denied to access $uri", e)
                    Snackbar.make(findViewById(R.id.RootView), "Permission denied", Snackbar.LENGTH_SHORT).show()
                    // Request permission if needed
                }
            } ?: run {
                // Handle case where uri is null
                Log.e(logTag, "No URI received from directory chooser")
                // Show error feedback
            }
        }
    }

    private fun saveBufferToFile(data: ByteArray) {
        dataIn = data
        Log.i(logTag, "saveBufferToFile()")
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav" // Use the correct MIME type for WAV files
            putExtra(Intent.EXTRA_TITLE, "rename_me.wav") // Set a default file name
        }
        saveFileInDirectoryLauncher.launch(intent)
    }

    class MyMediaController(context: Context) : MediaController(context) {
        override fun hide() {
            // Don't hide
        }
    }

    private fun createAudioPlayerUI() {// Create a FrameLayout that fills the screen
        val frameLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        addContentView(frameLayout, frameLayout.layoutParams)
        val controller = MyMediaController(this)
        controller.setAnchorView(frameLayout)
        mediaController = controller
    }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri: Uri? = result.data?.data
                Log.i(logTag, "Picked file $uri")

                try {
                    val parcelFileDescriptor = contentResolver.openFileDescriptor(uri!!, "r")
                    val fileDescriptor = parcelFileDescriptor?.fileDescriptor
                    val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                    mediaPlayer?.release() // Release any existing player
                    mediaPlayer = MediaPlayer().apply {
                        setAudioAttributes(audioAttributes)
                        setDataSource(fileDescriptor)
                        setOnPreparedListener { player ->
                            createAudioPlayerUI()
                            player.start()
                            mediaController?.setMediaPlayer(mediaPlayerControl) // Set after start()
                            mediaController?.show()
                        }
                        setOnCompletionListener {
                            release()
                            mediaPlayer = null
                        }
                        setOnErrorListener { _, what, extra ->
                            Log.e("MediaPlayer", "Error occurred: $what, $extra")
                            // Handle specific error cases here based on 'what' and 'extra'
                            true
                        }
                        prepareAsync()
                    }
                    mediaPlayer?.let {
                        mediaPlayerControl = MediaPlayerControllerImpl(it)
                    } ?: run {
                        Toast.makeText(this, "Failed to play audio file", Toast.LENGTH_SHORT).show()
                        // Disable playback controls if necessary
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "Failed to play file $uri with error: $e")
                    Toast.makeText(
                        this,
                        "Failed to play file $uri",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }


    private fun pickAndPlayFile() {
        Log.i(logTag, "pickAndPlayFile()")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            val mimeTypes = arrayOf("audio/wav","audio/x-wav")
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }
        filePickerLauncher.launch(intent)
    }


}
