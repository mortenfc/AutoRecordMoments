package com.example.recentaudiobuffer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.lang.Exception

class MainActivity : ComponentActivity() {
    private val logTag = "MainActivity"
    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
    )
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
                } else if (foregroundServiceAudioBufferConnection.service.isRunning()) {
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

    private fun haveAllPermissions(permissions: Array<String>): Boolean {
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

    private fun addAudioExtension(uri: Uri?): Uri {
        val oldPath: String = uri?.path!!
        val newPath = "$oldPath.wav"
        return Uri.parse(newPath)
    }

    private val directoryChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                var uri: Uri? = result.data?.data

                // Check if the user provided a file extension
                val extension = MimeTypeMap.getFileExtensionFromUrl(uri?.toString())
                val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                if (mimeType != null) {
                    if (!mimeType.startsWith("audio/")) {
                        // Add the .wav extension if not present
                        uri = addAudioExtension(uri)
                    }
                } else {
                    uri = addAudioExtension(uri)
                }

                try {
                    val outputStream = contentResolver.openOutputStream(uri!!)
                    outputStream?.write(dataIn)
                    outputStream?.close()
                    Log.i(logTag, "Saved file to $uri")
                    Toast.makeText(
                        this,
                        "Saved file to $uri",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Log.e(logTag, "Failed to save file to $uri with error: $e")
                    Toast.makeText(
                        this,
                        "Failed to save file to $uri",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

    private fun saveBufferToFile(data: ByteArray) {
        dataIn = data
        Log.i(logTag, "saveBufferToFile()")
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/octet-stream"
        intent.putExtra(Intent.EXTRA_TITLE, "default_file_name")
        directoryChooserLauncher.launch(intent)
    }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {

                val uri: Uri? = result.data?.data

                try {
                    MediaPlayer().apply {
                        setDataSource(applicationContext, uri!!)
                        prepare()
                        start()
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
        }
        filePickerLauncher.launch(intent)
    }


}
