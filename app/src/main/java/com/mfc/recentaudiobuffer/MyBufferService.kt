package com.mfc.recentaudiobuffer

import android.Manifest
import android.R.drawable.ic_media_play
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantLock
import kotlin.properties.Delegates

interface MainActivityInterface {
    fun getBuffer(): ByteArray
    fun isRecording(): Boolean
    fun writeWavHeader(out: OutputStream, audioDataLen: Long)
    fun stopRecording()
    fun startRecording()
}


class MyBufferService : Service(), MainActivityInterface {
    private val logTag = "MyBufferService"

    // Calculate the maximum total buffer size
    private lateinit var config: AudioConfig
    private var TOTAL_RING_BUFFER_SIZE by Delegates.notNull<Int>()
    private lateinit var buffer: ByteArray

    private var recorder: AudioRecord? = null
    private var isRecording = false
    private var writeIndex = 0
    private var readIndex = 0
    private val lock = ReentrantLock()
    private val recordingStartedLatch = CountDownLatch(1)

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        Log.i(logTag, "onCreate()")

        val intentFilter = IntentFilter("com.mfc.recentaudiobuffer.SETTINGS_UPDATED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13 or higher
            registerReceiver(settingsUpdateReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(settingsUpdateReceiver, intentFilter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(logTag, "onStartCommand()")

        return if (intent != null && !isRecording) {
            prepareAndStartRecording()
        } else {
            Log.i(logTag, "START_REDELIVER_INTENT or already recording")
            START_REDELIVER_INTENT
        }
    }

    override fun onDestroy() {
        Log.i(logTag, "onDestroy()")
        stopRecording()
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }

    private fun updateTotalBufferSize(config: AudioConfig) {
        TOTAL_RING_BUFFER_SIZE =
            config.SAMPLE_RATE_HZ * (config.BIT_DEPTH.bytes / 8) * config.BUFFER_TIME_LENGTH_S
        buffer = ByteArray(TOTAL_RING_BUFFER_SIZE)
    }

    private fun prepareAndStartRecording(): Int {
        Log.i(logTag, "prepareAndStartRecording")
        val name = "NotifyMicForeground" // The name of the channel
        val descriptionText =
            "Notifications for the mic foreground" // The description of the channel
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel("NotifyMicForeground", name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, "NotifyMicForeground")
            .setContentTitle("Recording Recent Audio").setContentText("Running...")
            .setSmallIcon(ic_media_play).setContentIntent(pendingIntent).build()

        val micPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (micPermission == PackageManager.PERMISSION_DENIED) {
            // Without mic permissions the service cannot run in the foreground
            // Consider informing user or updating your app UI if visible.
            stopSelf()
            return STOP_FOREGROUND_DETACH
        }

        this.startForeground(
            1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        try {
            startBuffering()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        return START_NOT_STICKY
    }

    private fun startBuffering() {
        syncPreferences()

        val audioFormat = AudioFormat.Builder().setEncoding(config.BIT_DEPTH.encodingEnum)
            .setSampleRate(config.SAMPLE_RATE_HZ).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            audioFormat.sampleRate, audioFormat.channelMask, audioFormat.encoding
        )

        if (minBufferSize <= 0) {
            Log.e(logTag, "Minimum buffer size <= 0 somehow")
            Toast.makeText(
                this, "ERROR: Minimum buffer size <= 0 somehow", Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }

        // Ensure TOTAL_RING_BUFFER_SIZE is at least twice minBufferSize
        val ringBufferSize = if (TOTAL_RING_BUFFER_SIZE < minBufferSize * 2) {
            minBufferSize * 2
        } else {
            TOTAL_RING_BUFFER_SIZE
        }

        buffer = ByteArray(ringBufferSize)

        val permission = Manifest.permission.RECORD_AUDIO
        val granted = PackageManager.PERMISSION_GRANTED

        if (ContextCompat.checkSelfPermission(this, permission) != granted) {
            Log.e(logTag, "Audio record permission not granted, exiting the foreground service")
            Toast.makeText(
                this,
                "ERROR: Audio record permission not granted before this was started, exiting the foreground service",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }

        // Permission has been granted
        recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(audioFormat).setBufferSizeInBytes(minBufferSize).build()

        try {
            isRecording = true
            Thread {
                recorder?.startRecording() // Start recording
                recordingStartedLatch.countDown()
                Log.d(logTag, "Recording thread started")
                while (isRecording) {
                    lock.lock()
                    try {
                        // Read in chunks of minBufferSize
                        val readResult = recorder?.read(buffer, writeIndex, minBufferSize) ?: -1
                        Log.d(
                            logTag, "readResult: $readResult, writeIndex before update: $writeIndex"
                        ) // Log before update
                        if (readResult > 0) {
                            writeIndex = (writeIndex + readResult) % buffer.size
                        } else if (readResult == AudioRecord.ERROR_INVALID_OPERATION) {
                            Log.e(logTag, "AudioRecord invalid operation")
                        } else if (readResult == AudioRecord.ERROR_BAD_VALUE) {
                            Log.e(logTag, "AudioRecord bad value")
                        } else if (recorder?.state == AudioRecord.RECORDSTATE_STOPPED) {
                            Log.e(logTag, "AudioRecord stopped unexpectedly")
                            isRecording = false
                        } else {
                            Log.e(
                                logTag,
                                "AudioRecord other error state: ${recorder?.state}, result: $readResult"
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(
                            logTag, "recorder read error: $e in state: ${recorder?.state}"
                        )
                    } finally {
                        lock.unlock()
                    }
                    Thread.sleep(10) // Add a small delay where lock is not used
                }
                recorder?.stop()
                recorder?.release()
                recorder = null
            }.start()
        } catch (e: IllegalArgumentException) {
            Log.e(logTag, "startBuffering() failed: illegal argument", e)
            stopSelf()
        } catch (e: IllegalStateException) {
            Log.e(logTag, "startBuffering() failed: illegal state", e)
            stopSelf()
        }
    }

    override fun stopRecording() {
        isRecording = false
    }

    override fun writeWavHeader(out: OutputStream, audioDataLen: Long) {
        val channels = 1.toShort()
        val sampleRate = config.SAMPLE_RATE_HZ
        val bitsPerSample = config.BIT_DEPTH.bytes.toShort()

        // WAV constants
        val sampleSize = bitsPerSample / 8
        val headerSize = 44

        // Calculate sizes
        val totalDataLen = audioDataLen + headerSize
        val byteRate = sampleRate * channels * sampleSize

        // Write the header
        out.write(
            byteArrayOf(
                'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()
            )
        )
        out.write(intToBytes(totalDataLen.toInt()), 0, 4)
        out.write(
            byteArrayOf(
                'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()
            )
        )
        out.write(
            byteArrayOf(
                'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()
            )
        )
        out.write(intToBytes(16), 0, 4)  // Sub-chunk size, 16 for PCM
        out.write(shortToBytes(1.toShort()), 0, 2)  // AudioFormat, 1 for PCM
        out.write(shortToBytes(channels), 0, 2)
        out.write(intToBytes(sampleRate), 0, 4)
        out.write(intToBytes(byteRate.toInt()), 0, 4)
        out.write(shortToBytes((channels * sampleSize).toShort()), 0, 2)  // Block align
        out.write(shortToBytes(bitsPerSample), 0, 2)
        out.write(
            byteArrayOf(
                'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()
            )
        )
        out.write(intToBytes(audioDataLen.toInt()), 0, 4)
    }

    private fun intToBytes(i: Int): ByteArray {
        val bb = ByteBuffer.allocate(4)
        bb.order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(i)
        return bb.array()
    }

    private fun shortToBytes(data: Short): ByteArray {
        return byteArrayOf(
            (data.toInt() and 0xff).toByte(), ((data.toInt() shr 8) and 0xff).toByte()
        )
    }

    override fun getBuffer(): ByteArray {
        val data = ByteArray(TOTAL_RING_BUFFER_SIZE)
        val bytesRead =
            readFromBuffer(dest = data, offset = 0, bytesToRead = TOTAL_RING_BUFFER_SIZE)
        Log.i(
            logTag, "getBuffer() read $bytesRead bytes of ${data.size} total available buffer bytes"
        )
        if (bytesRead == 0) {
            Log.w(logTag, "getBuffer(): Buffer is empty!")
        }
        return data.copyOf(bytesRead)
    }

    private fun readFromBuffer(
        dest: ByteArray, offset: Int, bytesToRead: Int
    ): Int {
        lock.lock()
        try {
            val bytesAvailable = if (writeIndex >= readIndex) {
                writeIndex - readIndex
            } else {
                buffer.size - readIndex + writeIndex
            }
            Log.d(
                logTag,
                "readFromBuffer(): bytesAvailable = $bytesAvailable, writeIndex = $writeIndex, readIndex = $readIndex"
            )

            val bytesToCopy = minOf(bytesToRead, bytesAvailable)
            Log.d(logTag, "readFromBuffer(): bytesToCopy = $bytesToCopy")
            Log.d(logTag, "readFromBuffer(): buffer.size = ${buffer.size}")

            if (readIndex + bytesToCopy <= buffer.size) {
                System.arraycopy(buffer, readIndex, dest, offset, bytesToCopy)
            } else {
                val bytesToEnd = buffer.size - readIndex
                Log.d(logTag, "readFromBuffer(): bytesToEnd = $bytesToEnd")
                System.arraycopy(buffer, readIndex, dest, offset, bytesToEnd)
                System.arraycopy(buffer, 0, dest, offset + bytesToEnd, bytesToCopy - bytesToEnd)
            }

            // OR Simplified copy logic using a single `arraycopy`
            // System.arraycopy(buffer, readIndex, dest, offset, bytesToCopy)

            readIndex = (readIndex + bytesToCopy) % buffer.size
            Log.d(logTag, "readFromBuffer(): readIndex updated to $readIndex")

            return bytesToCopy
        } finally {
            lock.unlock()
        }
    }

    private fun syncPreferences() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val sampleRate = sampleRates[sharedPreferences.getString("sample_rate", "22050")]
            ?: error("Invalid sample rate")
        val bufferTimeLengthS = sharedPreferences.getString("buffer_time", "120")?.toInt() ?: 120
        val bitDepth =
            bitDepths[sharedPreferences.getString("bit_depth", "8")] ?: error("Invalid bit depth")

        config = AudioConfig(sampleRate, bufferTimeLengthS, bitDepth)
        updateTotalBufferSize(config)

        Log.i(
            logTag,
            "syncPreferences(): sampleRate = $sampleRate, bufferTimeLengthS = $bufferTimeLengthS, bitDepth = $bitDepth"
        )
    }

    private val settingsUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.mfc.recentaudiobuffer.SETTINGS_UPDATED") {
                syncPreferences()
            }
        }
    }

    override fun isRecording(): Boolean {
        try {
            recordingStartedLatch.await() // Wait for recording to start
        } catch (e: InterruptedException) { // Handle interruption
            e.printStackTrace()
        }
        return isRecording
    }

    override fun startRecording() {
        if (!isRecording) {
            prepareAndStartRecording()
        }
    }

    inner class MyBinder : Binder() {
        fun getService(): MainActivityInterface = this@MyBufferService
    }

    private val binder = MyBinder()

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopRecording()
        return super.onUnbind(intent)
    }
}
