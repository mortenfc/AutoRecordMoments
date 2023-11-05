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
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface MainActivityInterface {
    fun getBuffer(): ByteArray
    fun isRunning(): Boolean
    fun writeWavHeader(out: OutputStream, audioDataLen: Long)
}

data class BitDepth(val bytes: Int, val encodingEnum: Int)

private val bitDepths = mapOf(
    "8" to BitDepth(8, AudioFormat.ENCODING_PCM_8BIT),
    "16" to BitDepth(16, AudioFormat.ENCODING_PCM_16BIT)
)
private val BIT_DEPTH = bitDepths["8"] ?: error("Invalid bit depth")
private const val SAMPLE_RATE_HZ = 44100
private const val BUFFER_TIME_LENGTH_S = 120
private val BUFFER_SIZE = SAMPLE_RATE_HZ * (BIT_DEPTH.bytes / 8) * BUFFER_TIME_LENGTH_S

class MyBufferService : Service(), MainActivityInterface {
    private val logTag = "MyBufferService"
    private var recorder: AudioRecord? = null
    private var buffer: ByteArray = ByteArray(BUFFER_SIZE)
    private var isRecorderRunning = false

    override fun onCreate() {
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
            .setEncoding(BIT_DEPTH.encodingEnum)
            .setSampleRate(SAMPLE_RATE_HZ)
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
            Log.e(logTag, "Audio record permission not granted, exiting the foreground service")
            Toast.makeText(
                this,
                "ERROR: Audio record permission not granted before this was started, exiting the foreground service",
                Toast.LENGTH_SHORT
            ).show()
            stopSelf()
            return
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
                    synchronized(buffer)
                    {
                        val result = recorder!!.read(buffer, bufferIndex, buffer.size)
                        if (result > 0) {
                            bufferIndex += result
                            if (bufferIndex >= buffer.size) {
                                bufferIndex = 0
                            }
                        }
                    }
                }
            }.start()
            isRecorderRunning = true
        } catch (e: Exception) {
            Log.e(logTag, "startBuffering() failed in thread", e)
            stopSelf()
        }
    }

    private fun stopBuffering() {
        recorder!!.stop()
        recorder!!.release()
        recorder = null
        isRecorderRunning = false
    }

    override fun writeWavHeader(out: OutputStream, audioDataLen: Long) {
        val channels = 1.toShort()
        val sampleRate = SAMPLE_RATE_HZ
        val bitsPerSample = BIT_DEPTH.bytes.toShort()

        // WAV constants
        val sampleSize = bitsPerSample / 8
        val headerSize = 44

        // Calculate sizes
        val totalDataLen = audioDataLen + headerSize
        val byteRate = sampleRate * channels * sampleSize

        // Write the header
        out.write(byteArrayOf('R'.toByte(), 'I'.toByte(), 'F'.toByte(), 'F'.toByte()))
        out.write(intToBytes(totalDataLen.toInt()), 0, 4)
        out.write(byteArrayOf('W'.toByte(), 'A'.toByte(), 'V'.toByte(), 'E'.toByte()))
        out.write(byteArrayOf('f'.toByte(), 'm'.toByte(), 't'.toByte(), ' '.toByte()))
        out.write(intToBytes(16), 0, 4)  // Sub-chunk size, 16 for PCM
        out.write(shortToBytes(1.toShort()), 0, 2)  // AudioFormat, 1 for PCM
        out.write(shortToBytes(channels), 0, 2)
        out.write(intToBytes(sampleRate), 0, 4)
        out.write(intToBytes(byteRate.toInt()), 0, 4)
        out.write(shortToBytes((channels * sampleSize).toShort()), 0, 2)  // Block align
        out.write(shortToBytes(bitsPerSample), 0, 2)
        out.write(byteArrayOf('d'.toByte(), 'a'.toByte(), 't'.toByte(), 'a'.toByte()))
        out.write(intToBytes(audioDataLen.toInt()), 0, 4)
    }

    private fun intToBytes(i: Int): ByteArray {
        val bb = ByteBuffer.allocate(4)
        bb.order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(i)
        return bb.array()
    }

    private fun shortToBytes(data: Short): ByteArray {
        return byteArrayOf((data.toInt() and 0xff).toByte(), ((data.toInt() shr 8) and 0xff).toByte())
    }


    override fun getBuffer(): ByteArray {
        synchronized(buffer)
        {
            return buffer.copyOf()
        }
    }

    override fun isRunning(): Boolean {
        return isRecorderRunning
    }

    inner class MyBinder : Binder() {
        fun getService(): MainActivityInterface = this@MyBufferService
    }

    private val binder = MyBinder()

    override fun onBind(intent: Intent): IBinder {
        return binder
    }
}
