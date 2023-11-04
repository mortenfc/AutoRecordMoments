package com.example.recentaudiobuffer

import android.Manifest
import android.app.AlertDialog
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
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var foregroundServiceAudioBuffer : Intent
    private val foregroundServiceAudioBufferConnection  = object : ServiceConnection {
        lateinit var service: MainActivityInterface
        var isBound : Boolean = false

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

        fun unBind()
        {
            unbindService(this)
            isBound = false
        }
    }

    private val onSettingsExit = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        confirmPermissionsWereSet()
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
                }
                else if (foregroundServiceAudioBufferConnection.service.isRunning()){
                    Toast.makeText(
                        this,
                        "Buffer is already running!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                else
                {
                    Log.e(logTag, "Buffer service bound but not running")
                    Toast.makeText(
                        this,
                        "ERROR: Buffer service bound but not running",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            else
            {
                AlertDialog.Builder(this)
                    .setTitle("Not all permissions were given")
                    .setMessage("All permissions are required for this app to work")
                    .setPositiveButton("Open settings") { _, _ ->
                        goToSettings()
                    }
                    .create().show()
            }
        }

        val stop: Button = findViewById(R.id.StopBuffering)
        stop.setOnClickListener {
            if (haveAllPermissions(requiredPermissions))
            {
                if(foregroundServiceAudioBufferConnection.isBound) {
                    Log.i(logTag, "Stopping bound MyBufferService by unbinding it")
                    foregroundServiceAudioBufferConnection.unBind()
                }
                else
                {
                    Log.e(logTag, "Buffer service is not running")
                    Toast.makeText(
                        this,
                        "Buffer is not running",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            else
            {
                AlertDialog.Builder(this)
                    .setTitle("Not all permissions were given")
                    .setMessage("All permissions are required for this app to work")
                    .setPositiveButton("Open settings") { _, _ ->
                        goToSettings()
                    }
                    .create().show()
            }
        }

        val save: Button = findViewById(R.id.SaveBuffer)
        save.setOnClickListener {
            if(foregroundServiceAudioBufferConnection.isBound)
            {
                saveBufferToFile(foregroundServiceAudioBufferConnection.service.getBuffer())
            }
            else
            {
                Toast.makeText(
                    this,
                    "Buffer is not running. It has to be running to save it!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun haveAllPermissions(permissions: Array<String>): Boolean {
        Log.i(logTag, "haveAllPermissions()")
        for (permission in permissions) {
            if (PackageManager.PERMISSION_GRANTED != checkSelfPermission(permission)) {
                return false
            }
        }

        return true
    }


    private fun confirmPermissionsWereSet()
    {
        Log.i(logTag, "confirmPermissionsWereSet()")

        if (haveAllPermissions(requiredPermissions)) {
            Toast.makeText(
                this,
                "All permissions were granted, you can now use the app!",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                this,
                "Not all permissions were given. All are required for the app to work.",
                Toast.LENGTH_SHORT
            ).show()
        }
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

        onSettingsExit.launch(thisAppSettings)
    }

    private fun getPermissions() {
        Log.i(logTag, "getPermissions()")
        for (permission in requiredPermissions) {
            if (PackageManager.PERMISSION_GRANTED != checkSelfPermission(permission)) {
                val requestForegroundServicePermission =
                    registerForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { isGranted: Boolean ->
                        if (isGranted) {
                            Toast.makeText(
                                this,
                                "$permission permission granted!",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("$permission permission required")
                                .setMessage("This permission is needed for this app to work")
                                .setPositiveButton("Open settings") { _, _ ->
                                    goToSettings()
                                }
                                .create().show()
                        }
                    }
                requestForegroundServicePermission.launch(
                    permission
                )
            }
        }

        Log.i(logTag, "done getPermissions()")
    }

    private fun saveBufferToFile(data: ByteArray) {
        Log.i(logTag, "saveBufferToFile()")
        val file = File(this.filesDir, "recorded_audio.wav")
        try {
            val outputStream = FileOutputStream(file, true)
            outputStream.write(data)
            outputStream.close()
            Log.i(logTag, "Saved file to ${file.absolutePath}")
            Toast.makeText(
                this,
                "Saved file to ${file.path}",
                Toast.LENGTH_LONG
            ).show()
        }
        catch (e: Exception)
        {
            Log.e(logTag, "Failed to save file to ${file.absolutePath}")
            Toast.makeText(
                this,
                "Failed to save file to ${file.path}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
