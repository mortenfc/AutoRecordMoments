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
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecord.READ_BLOCKING
import android.media.AudioRecord.READ_NON_BLOCKING
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
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.math.roundToLong

interface MyBufferServiceInterface {
    fun getBuffer(): ByteArray
    fun writeWavHeader(out: OutputStream, audioDataLen: Int)
    fun stopRecording()
    fun startRecording()
    fun resetBuffer()
    fun quickSaveBuffer()

    val isRecording: AtomicReference<Boolean>
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
        private const val READ_SLEEP_DURATION = 300L
        private val NOTIFICATION_UPDATE_INTERVAL_MS = 1000L - (READ_SLEEP_DURATION / 2.0).roundToLong()

        // Static variable to hold the buffer
        var sharedAudioDataToSave: ByteArray? = null
    }

    private var lastNotificationUpdateTime: Long = 0

    @Inject
    lateinit var settingsRepository: SettingsRepository

    public override val isRecording: AtomicReference<Boolean> = AtomicReference(false)

    private val hasOverflowed: AtomicReference<Boolean> = AtomicReference(false)

    private val recorderIndex: AtomicReference<Int> = AtomicReference(0)

    private val totalRingBufferSize: AtomicReference<Int> = AtomicReference(100)

    private val time: AtomicReference<String> = AtomicReference("00:00:00")

    private var recorder: AudioRecord? = null
    private var lock: ReentrantLock = ReentrantLock()

    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    override fun onCreate() {
        super.onCreate()
        Log.i(logTag, "onCreate()")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannels()
        requestAudioFocus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(logTag, "onStartCommand()")

        config = runBlocking { settingsRepository.getAudioConfig() }
        updateTotalBufferSize(config)

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

        startForeground(CHRONIC_NOTIFICATION_ID, createNotification())

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(logTag, "onDestroy()")
        stopRecording()
        abandonAudioFocus()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(logTag, "onTaskRemoved() called with intent: $rootIntent")
        super.onTaskRemoved(rootIntent)
    }

    private var isRecordingCallAudio = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(logTag, "OnAudioFocusChangeListener() focusChange: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Stop or pause recording
                Log.d(logTag, "Audio focus lost")
                if (isRecording.get()) {
                    stopRecording()
                    isRecordingCallAudio = true
                    startRecording()
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                // Start or resume recording
                Log.d(logTag, "Audio focus gained")
                if (isRecordingCallAudio) {
                    stopRecording()
                    isRecordingCallAudio = false
                    startRecording()
                }
            }
        }
    }

    private fun requestAudioFocus() {
        Log.d(logTag, "requestAudioFocus()")
        val result = audioManager.requestAudioFocus(
            audioFocusChangeListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN
        )
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d(logTag, "Audio focus granted")
        } else {
            Log.e(logTag, "Audio focus request failed")
        }
    }

    private fun abandonAudioFocus() {
        Log.d(logTag, "abandonAudioFocus()")
        audioManager.abandonAudioFocus(audioFocusChangeListener)
    }

    private fun tryAllocating(idealBufferSize: Int): Long {
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

        val maxDynamicMemory = tryAllocating((idealBufferSize * 1.1).roundToInt())

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

        val audioSource = if (isRecordingCallAudio) {
            MediaRecorder.AudioSource.VOICE_CALL
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
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
            Log.e(
                logTag, "Audio record permission not granted, exiting the foreground service"
            )
            return
        }

        // Permission has been granted
        recorder = AudioRecord.Builder().setAudioSource(audioSource).setAudioFormat(audioFormat)
            .setBufferSizeInBytes(readChunkSize).build()

        try {
            Thread {
                isRecording.set(true)
                recorder?.startRecording() // Start recording
                Log.d(logTag, "Recording thread started")
                while (isRecording.get()) {
                    Thread.sleep(READ_SLEEP_DURATION)
                    // Blocking read in chunks of the size equal to the audio recorder's internal audioDataStorage.
                    // Waits for readChunkSize to be available
                    val readDataChunk = ByteArray(readChunkSize)
                    val readResult = recorder?.read(
                        readDataChunk, 0, readChunkSize, READ_NON_BLOCKING
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
        updateNotification(checkTime = false)
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
            TimeUnit.SECONDS.toMinutes(durationInSeconds.toLong()) % TimeUnit.HOURS.toMinutes(
                1
            ),
            TimeUnit.SECONDS.toSeconds(durationInSeconds.toLong()) % TimeUnit.MINUTES.toSeconds(
                1
            )
        )

        time.set(durationFormatted)
    }

    override fun writeWavHeader(out: OutputStream, audioDataLen: Int) {
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
        out.write(intToBytes(totalDataLen), 0, 4)
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
        out.write(intToBytes(byteRate), 0, 4)
        out.write(shortToBytes((channels * sampleSize).toShort()), 0, 2)  // Block align
        out.write(shortToBytes(bitsPerSample), 0, 2)
        out.write(
            byteArrayOf(
                'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()
            )
        )
        out.write(intToBytes(audioDataLen), 0, 4)
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
        val shiftedBuffer: ByteArray
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

        return shiftedBuffer
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
        Log.d(logTag, "resetBuffer()")
        recorderIndex.set(0)
        hasOverflowed.set(false)
        updateNotification(checkTime = false)
    }

    override fun quickSaveBuffer() {
        Log.d(logTag, "quickSaveBuffer()")
        // Store the buffer in the static variable
        sharedAudioDataToSave = getBuffer()
        val grantedUri = FileSavingUtils.getCachedGrantedUri()
        // Null of grantedUri is handled in the file saving service
        val quickSaveIntent = Intent(
            this, FileSavingService::class.java
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("grantedUri", grantedUri)
        this.startService(quickSaveIntent)
    }

    override fun startRecording() {
        if (!isRecording.get()) {
            startBuffering()
            updateNotification(checkTime = false)
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
        // Continue recording after unbinding from MainActivity
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
        val stopIntent = PendingIntent.getBroadcast(
            this, REQUEST_CODE_STOP, Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_STOP_RECORDING
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val startIntent = PendingIntent.getBroadcast(
            this, REQUEST_CODE_START, Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_START_RECORDING
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val saveIntent = PendingIntent.getBroadcast(
            this, REQUEST_CODE_SAVE, Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_SAVE_RECORDING
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val recordingNotification =
            NotificationCompat.Builder(this, CHRONIC_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Buffered Recent Audio")
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

    private fun updateNotification(checkTime: Boolean = true) {
        val currentTime = System.currentTimeMillis()
        if (!checkTime || (currentTime - lastNotificationUpdateTime >= NOTIFICATION_UPDATE_INTERVAL_MS)) {
            val notification = createNotification()
            notificationManager.notify(CHRONIC_NOTIFICATION_ID, notification)
            lastNotificationUpdateTime = currentTime
        }
    }
}
