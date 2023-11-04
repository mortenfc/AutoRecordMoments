package com.example.recentaudiobuffer

import android.Manifest
import android.R.drawable.ic_media_play
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

interface MainActivityInterface {
    fun getBuffer(): ByteArray
    fun isRunning(): Boolean
}

private const val BUFFER_SIZE = 10_000

class MyBufferService: Service(), MainActivityInterface {
    private val logTag = "MyBufferService"
    private var recorder: AudioRecord? = null
    private var buffer: ByteArray = ByteArray(BUFFER_SIZE)
    private var is_running = false

    override fun onCreate()
    {
        Log.i(logTag, "onCreate()")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(logTag, "onStartCommand()")

        return if (intent != null) {
            prepareAndStartRecording()
        } else {
            Log.i(logTag, "START_REDELIVER_INTENT")
            START_REDELIVER_INTENT
        }
    }

    override fun onDestroy() {
        Log.i(logTag, "onDestroy()")
        stopBuffering()
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }

    private fun prepareAndStartRecording(): Int {
        Log.i(logTag, "prepareAndStartRecording")
        val name = "NotifyMicForeground" // The name of the channel
        val descriptionText = "Notifications for the mic foreground" // The description of the channel
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel("NotifyMicForeground", name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, "NotifyMicForeground")
            .setContentTitle("Recording Recent Audio")
            .setContentText("Running...")
            .setSmallIcon(ic_media_play)
            .setContentIntent(pendingIntent)
            .build()

        val micPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (micPermission == PackageManager.PERMISSION_DENIED) {
            // Without mic permissions the service cannot run in the foreground
            // Consider informing user or updating your app UI if visible.
            stopSelf()
            return STOP_FOREGROUND_DETACH
        }

        this.startForeground(
            1,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        try {
            startBuffering()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        return START_NOT_STICKY
    }

    private fun startBuffering() {
        var bufferIndex = 0
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            audioFormat.sampleRate,
            audioFormat.channelMask,
            audioFormat.encoding
        )

        val permission = Manifest.permission.RECORD_AUDIO
        val granted = PackageManager.PERMISSION_GRANTED

        if (ContextCompat.checkSelfPermission(this, permission) != granted) {
            // Permission is not granted
            // Stop the service or handle the lack of audio permissions appropriately
        } else {
            // Permission has been granted
            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufferSize)
                .build()
        }

        try {
            Thread {
                while (true) {
                    val result = recorder!!.read(buffer, bufferIndex, buffer.size)
                    if (result > 0) {
                        bufferIndex += result
                        if (bufferIndex >= buffer.size) {
                            bufferIndex = 0
                        }
                    }
                }
            }.start()
            is_running = true
        } catch (e: Exception) {
            Log.e(logTag, "startBuffering() failed in thread", e)
            stopSelf()
        }
    }

    private fun stopBuffering() {
        recorder!!.stop()
        recorder!!.release()
        recorder = null
        is_running = false
    }

    override fun getBuffer(): ByteArray {
        return buffer
    }

    override fun isRunning(): Boolean
    {
        return is_running
    }

    inner class MyBinder : Binder() {
        fun getService(): MainActivityInterface = this@MyBufferService
    }

    private val binder = MyBinder()

    override fun onBind(intent: Intent): IBinder {
        return binder
    }
}
