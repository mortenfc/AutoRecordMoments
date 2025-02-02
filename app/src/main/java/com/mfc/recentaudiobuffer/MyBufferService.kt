package com.mfc.recentaudiobuffer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRecord.READ_BLOCKING
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import kotlin.math.roundToInt

interface MyBufferServiceInterface {
    fun getBuffer(): ByteArray
    fun writeWavHeader(out: OutputStream, audioDataLen: Long)
    fun stopRecording()
    fun startRecording()
    fun resetBuffer()
    fun quickSaveBuffer()

    val isRecording: AtomicLiveDataThrottled<Boolean>
}

@AndroidEntryPoint
class MyBufferService : Service(), MyBufferServiceInterface {
    private val logTag = "MyBufferService"

    private lateinit var config: AudioConfig
    private lateinit var audioDataStorage: ByteArray

    companion object {
        const val CHRONIC_NOTIFICATION_ID = 1
        private const val CHRONIC_NOTIFICATION_CHANNEL_ID = "recording_channel"
        private const val CHRONIC_NOTIFICATION_CHANNEL_NAME = "Recording Into RingBuffer"
        private const val CHRONIC_NOTIFICATION_CHANNEL_DESCRIPTION =
            "Channel for the persistent recording notification banner"
        const val ACTION_STOP_RECORDING_SERVICE = "com.example.app.ACTION_STOP_RECORDING_SERVICE"
        const val ACTION_START_RECORDING_SERVICE = "com.example.app.ACTION_START_RECORDING_SERVICE"
        const val ACTION_SAVE_RECORDING_SERVICE = "com.example.app.ACTION_SAVE_RECORDING_SERVICE"
        private const val REQUEST_CODE_STOP = 1
        private const val REQUEST_CODE_START = 2
        private const val REQUEST_CODE_SAVE = 3
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 850L

        // Static variable to hold the buffer
        var sharedAudioDataToSave: ByteArray? = null
    }

    private var lastNotificationUpdateTime: Long = 0

    @Inject
    lateinit var settingsRepository: SettingsRepository

    public override val isRecording: AtomicLiveDataThrottled<Boolean> =
        AtomicLiveDataThrottled(false)

    private val hasOverflowed: AtomicLiveDataThrottled<Boolean> = AtomicLiveDataThrottled(false)

    private val recorderIndex: AtomicLiveDataThrottled<Int> = AtomicLiveDataThrottled(0)

    private val totalRingBufferSize: AtomicLiveDataThrottled<Int> = AtomicLiveDataThrottled(100)

    private val time: AtomicLiveDataThrottled<String> = AtomicLiveDataThrottled("00:00:00")

    private var recorder: AudioRecord? = null
    private var lock: ReentrantLock = ReentrantLock()
    private lateinit var stopIntent: PendingIntent
    private lateinit var startIntent: PendingIntent
    private lateinit var saveIntent: PendingIntent
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        Log.i(logTag, "onCreate()")
        stopIntent = PendingIntent.getBroadcast(
            this, REQUEST_CODE_STOP, Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_STOP_RECORDING
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        startIntent = PendingIntent.getBroadcast(
            this, REQUEST_CODE_START, Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_START_RECORDING
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        saveIntent = PendingIntent.getBroadcast(
            this, REQUEST_CODE_SAVE, Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_SAVE_RECORDING
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(logTag, "onStartCommand()")
        config = runBlocking { settingsRepository.getAudioConfig() }
        updateTotalBufferSize(config)

        startForeground(CHRONIC_NOTIFICATION_ID, createNotification())

        when (intent?.action) {
            ACTION_STOP_RECORDING_SERVICE -> {
                stopRecording()
            }

            ACTION_START_RECORDING_SERVICE -> {
                startRecording()
            }

            ACTION_SAVE_RECORDING_SERVICE -> {
                Log.d(logTag, "Got ACTION_SAVE_RECORDING_SERVICE intent")
                quickSaveBuffer()
                resetBuffer()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(logTag, "onDestroy()")
        stopRecording()
    }

    private fun getApproximateFreeMemory(idealBufferSize: Int): Long {
        try {
            // Try to allocate a large byte array
            ByteArray(idealBufferSize)
            // If successful, return the allocation size
            return idealBufferSize.toLong()
        } catch (e: OutOfMemoryError) {
            // If OutOfMemoryError, parse the message
            val message = e.message ?: ""
            val freeBytesRegex = Regex("with (\\d+) free bytes")
            val matchResult = freeBytesRegex.find(message)
            if (matchResult != null && matchResult.groupValues.size > 1) {
                try {
                    val freeBytes = matchResult.groupValues[1].toLong()
                    Log.d(logTag, "Approximate free memory: $freeBytes bytes")
                    return freeBytes
                } catch (e: NumberFormatException) {
                    Log.e(logTag, "Failed to parse free bytes from OutOfMemoryError message", e)
                }
            } else {
                Log.e(logTag, "Failed to find free bytes in OutOfMemoryError message")
            }
        }
        return 0
    }

    private fun updateTotalBufferSize(config: AudioConfig) {
        // Calculate the ideal buffer size based on settings
        var idealBufferSize =
            config.sampleRateHz * (config.bitDepth.bytes / 8) * config.bufferTimeLengthS

        Log.d(logTag, "updateTotalBufferSize(): idealBufferSize = $idealBufferSize")


        val maxDynamicMemory = getApproximateFreeMemory(idealBufferSize)

        // Check against dynamic memory limit first
        if (idealBufferSize > maxDynamicMemory) {
            // Calculate the maximum dynamic memory based on available device memory
            val safeLimitPercentage = 0.7 // Reduce to 70%
            Log.e(
                logTag,
                "idealBufferSize > $maxDynamicMemory, setting it to ${safeLimitPercentage * 100}% of $maxDynamicMemory"
            )
            Toast.makeText(
                applicationContext,
                "ERROR: Exceeded available RAM (${maxDynamicMemory}) for the buffer size... Clear RAM or reduce settings values. Limiting size.",
                Toast.LENGTH_LONG
            ).show()
            idealBufferSize = (maxDynamicMemory.toInt() * safeLimitPercentage).toInt()
            if (idealBufferSize < 0) {
                idealBufferSize = 0
            }
        }

        // Check against MAX_BUFFER_SIZE
        if (idealBufferSize > MAX_BUFFER_SIZE) {
            Log.e(logTag, "idealBufferSize > MAX_BUFFER_SIZE, setting it to MAX_BUFFER_SIZE")
            idealBufferSize = MAX_BUFFER_SIZE
            Toast.makeText(
                applicationContext,
                "ERROR: Exceeded 100MB for the buffer size... Reduce settings values. Limiting size.",
                Toast.LENGTH_LONG
            ).show()
        }

        // Set the totalRingBufferSize
        totalRingBufferSize.set(idealBufferSize)
        Log.d(logTag, "updateTotalBufferSize(): totalRingBufferSize = ${totalRingBufferSize.get()}")
        audioDataStorage = ByteArray(totalRingBufferSize.get())
    }

    private fun startBuffering() {
        this.syncPreferences()

        val micPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (micPermission == PackageManager.PERMISSION_DENIED) {
            return
        }

        val audioFormat = AudioFormat.Builder().setEncoding(config.bitDepth.encodingEnum)
            .setSampleRate(config.sampleRateHz).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()

        val minReadChunkSize = AudioRecord.getMinBufferSize(
            audioFormat.sampleRate, audioFormat.channelMask, audioFormat.encoding
        )

        val bufferSizeMultiplier = 8
        val readChunkSize = minReadChunkSize * bufferSizeMultiplier
        Log.d(logTag, "startBuffering(): readChunkSize = $readChunkSize")

        if (readChunkSize <= 0) {
            Log.e(logTag, "Minimum audioDataStorage size <= 0 somehow")
            return
        }

        val permission = Manifest.permission.RECORD_AUDIO
        val granted = PackageManager.PERMISSION_GRANTED

        if (ContextCompat.checkSelfPermission(this, permission) != granted) {
            Log.e(logTag, "Audio record permission not granted, exiting the foreground service")
            return
        }

        // Permission has been granted
        recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(audioFormat).setBufferSizeInBytes(readChunkSize).build()

        try {
            Thread {
                isRecording.set(true)
                recorder?.startRecording() // Start recording
                Log.d(logTag, "Recording thread started")
                while (isRecording.get()) {
                    // Blocking read in chunks of the size equal to the audio recorder's internal audioDataStorage.
                    // Waits for readChunkSize to be available
                    val readDataChunk = ByteArray(readChunkSize)
                    val readResult = recorder?.read(
                        readDataChunk, 0, readChunkSize, READ_BLOCKING
                    ) ?: -1

                    Log.d(
                        logTag,
                        "readResult: $readResult, recorderIndex before update: ${recorderIndex.get()}"
                    )
                    if (readResult > 0) {
                        // Copy to storage if data was read
                        lock.lock()
                        try {
                            // Check for overflow before copying
                            if (recorderIndex.get() + readResult > totalRingBufferSize.get() - 1) {
                                hasOverflowed.set(true)
                                val bytesToEnd = totalRingBufferSize.get() - recorderIndex.get()
                                // If overflow copy what's left until the end
                                System.arraycopy(
                                    readDataChunk,
                                    0,
                                    audioDataStorage,
                                    recorderIndex.get(),
                                    bytesToEnd
                                )
                                // Then copy the remaining data to position 0
                                System.arraycopy(
                                    readDataChunk,
                                    bytesToEnd,
                                    audioDataStorage,
                                    0,
                                    readResult - bytesToEnd
                                )
                                recorderIndex.set(readResult - bytesToEnd)
                            } else {
                                // Typical operation, just copy entire read data to current index
                                System.arraycopy(
                                    readDataChunk,
                                    0,
                                    audioDataStorage,
                                    recorderIndex.get(),
                                    readResult
                                )
                                recorderIndex.set(recorderIndex.get() + readResult)
                            }
                        } finally {
                            lock.unlock()
                        }
                        updateLengthInTime()
                        updateNotification()
                    } else if (readResult == AudioRecord.ERROR_INVALID_OPERATION) {
                        Log.e(
                            logTag,
                            "AudioRecord invalid operation. Params: (${audioDataStorage.size}, ${recorderIndex.get()}, $minReadChunkSize)"
                        )
                    } else if (readResult == AudioRecord.ERROR_BAD_VALUE) {
                        Log.e(
                            logTag,
                            "AudioRecord bad value. Params: (${audioDataStorage.size}, ${recorderIndex.get()}, $minReadChunkSize)"
                        )
                    } else if (recorder?.state == AudioRecord.RECORDSTATE_STOPPED) {
                        Log.e(logTag, "AudioRecord stopped unexpectedly")
                        isRecording.set(false)
                    } else {
                        Log.e(
                            logTag,
                            "AudioRecord other error state: ${recorder?.state}, result: $readResult. Params: (${audioDataStorage.size}, ${recorderIndex.get()}, $minReadChunkSize)"
                        )
                    }
                }
                recorder?.stop()
                recorder?.release()
                recorder = null
            }.start()
        } catch (e: IllegalArgumentException) {
            Log.e(logTag, "startBuffering() failed: illegal argument", e)
            isRecording.set(false)
        } catch (e: IllegalStateException) {
            Log.e(logTag, "startBuffering() failed: illegal state", e)
            isRecording.set(false)
        }
    }

    override fun stopRecording() {
        Log.i(logTag, "stopRecording()")
        isRecording.set(false)
        updateNotification()
    }

    private fun updateLengthInTime() {
        val sampleRate = config.sampleRateHz
        val bitDepth = config.bitDepth.bytes
        val channels = 1 // Recording is in mono

        val lengthIndex: Int = if (hasOverflowed.get()) {
            totalRingBufferSize.get()
        } else {
            recorderIndex.get()
        }

        val durationInSeconds = (lengthIndex * 8).toDouble() / (sampleRate * bitDepth * channels)

        // Format duration into HH:mm:ss
        val durationFormatted = String.format(
            Locale.getDefault(),
            "%02d:%02d:%02d",
            TimeUnit.SECONDS.toHours(durationInSeconds.toLong()),
            TimeUnit.SECONDS.toMinutes(durationInSeconds.toLong()) % TimeUnit.HOURS.toMinutes(1),
            TimeUnit.SECONDS.toSeconds(durationInSeconds.toLong()) % TimeUnit.MINUTES.toSeconds(1)
        )

        time.set(durationFormatted)
    }

    override fun writeWavHeader(out: OutputStream, audioDataLen: Long) {
        val channels = 1.toShort() // Recording is in mono
        val sampleRate = config.sampleRateHz
        val bitsPerSample = config.bitDepth.bytes.toShort()

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
        var shiftedBuffer: ByteArray? = null
        lock.lock()
        try {
            if (hasOverflowed.get()) {
                // Return the entire audioDataStorage, shifted at recorderIndex
                shiftedBuffer = ByteArray(totalRingBufferSize.get())
                val bytesToEnd = totalRingBufferSize.get() - recorderIndex.get()
                System.arraycopy(
                    audioDataStorage, recorderIndex.get(), shiftedBuffer, 0, bytesToEnd
                )
                System.arraycopy(
                    audioDataStorage, 0, shiftedBuffer, bytesToEnd, recorderIndex.get()
                )
                return shiftedBuffer
            } else {
                // Return only the relevant portion up to recorderIndex
                shiftedBuffer = audioDataStorage.copyOf(recorderIndex.get())
            }
        } finally {
            lock.unlock()
        }

        return shiftedBuffer!!
    }

    private fun syncPreferences() {
        Log.i(logTag, "syncPreferences()")
        try {
            val newConfig = runBlocking {
                settingsRepository.getAudioConfig()
            }
            if (config != newConfig) {
                Log.d(logTag, "syncPreferences(): config != newConfig, resetting")
                config = newConfig
                resetBuffer()
                updateTotalBufferSize(config)
            }
            Log.i(
                logTag,
                "syncPreferences(): sampleRate = ${config.sampleRateHz}, bufferTimeLengthS = ${config.bufferTimeLengthS}, bitDepth = ${config.bitDepth}"
            )
        } catch (e: Exception) {
            Log.e(logTag, "Error syncing preferences", e)
        }
    }

    override fun resetBuffer() {
        recorderIndex.set(0)
        hasOverflowed.set(false)
        Log.i(logTag, "Buffer reset")
    }

    override fun quickSaveBuffer() {
        Log.d(logTag, "quickSaveBuffer()")
        // Store the buffer in the static variable
        sharedAudioDataToSave = getBuffer()
        val grantedUri = FileSavingUtils.getCachedGrantedUri()
        // Null of grantedUri is handled in the file saving service
        val saveIntent =
            Intent(this, FileSavingService::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("grantedUri", grantedUri)
        this.startService(saveIntent)
    }

    override fun startRecording() {
        if (!isRecording.get()) {
            startBuffering()
        }
    }

    inner class MyBinder : Binder() {
        fun getService(): MyBufferServiceInterface = this@MyBufferService
    }

    private val binder = MyBinder()

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopRecording()
        return super.onUnbind(intent)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHRONIC_NOTIFICATION_CHANNEL_ID,
                CHRONIC_NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = CHRONIC_NOTIFICATION_CHANNEL_DESCRIPTION
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val recordingNotification =
            NotificationCompat.Builder(this, CHRONIC_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Recording Recent Audio")
                .setContentText(if (isRecording.get()) "Running...\n" else "Stopped.\n")
                .setContentText(
                    "${
                        if (hasOverflowed.get()) "100%" else "${
                            ((recorderIndex.get()
                                .toFloat() / totalRingBufferSize.get()) * 100).roundToInt()
                        }%"
                    } - ${time.get()}"
                ).setSmallIcon(R.drawable.baseline_record_voice_over_24)
                .setProgress(  // Bar visualization
                    totalRingBufferSize.get(), if (hasOverflowed.get()) {
                        totalRingBufferSize.get()
                    } else {
                        recorderIndex.get()
                    }, false
                ).addAction(
                    if (isRecording.get()) R.drawable.baseline_mic_24 else R.drawable.baseline_mic_off_24,
                    if (isRecording.get()) "Pause" else "Continue", // Update action text
                    if (isRecording.get()) stopIntent else startIntent // Update PendingIntent
                ).addAction(
                    if (isRecording.get()) R.drawable.baseline_save_alt_24 else 0,
                    if (isRecording.get()) "Save and clear" else null,
                    if (isRecording.get()) saveIntent else null
                ).setAutoCancel(false) // Keep notification after being tapped
                .setSmallIcon(R.drawable.baseline_record_voice_over_24) // Set the small icon
                .setOngoing(true) // Make it a chronic notification
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true) // IMPORTANCE_DEFAULT otherwise notifies on each update
                .setSilent(true) // Don't make sounds
                .setCategory(NotificationCompat.CATEGORY_SERVICE).build()

        return recordingNotification
    }

    private fun updateNotification() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNotificationUpdateTime >= NOTIFICATION_UPDATE_INTERVAL_MS) {
            val notification = createNotification()
            notificationManager.notify(CHRONIC_NOTIFICATION_ID, notification)
            lastNotificationUpdateTime = currentTime
        }
    }
}
