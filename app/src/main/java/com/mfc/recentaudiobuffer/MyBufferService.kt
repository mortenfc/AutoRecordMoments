package com.mfc.recentaudiobuffer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ticker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import kotlin.math.floor
import kotlin.math.roundToInt

interface MyBufferServiceInterface {
    fun getBuffer(): ByteArray
    fun writeWavHeader(out: OutputStream, audioDataLen: Int)
    fun stopRecording()
    fun startRecording()
    fun resetBuffer()
    fun quickSaveBuffer()

    val isRecording: StateFlow<Boolean>
}

@AndroidEntryPoint
class MyBufferService : Service(), MyBufferServiceInterface {
    private val logTag = "MyBufferService"
    private var config: AudioConfig = AudioConfig()
    private lateinit var audioDataStorage: ByteArray
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null
    private var notificationJob: Job? = null

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
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 1000L

        // Static variable to hold the buffer
        var sharedAudioDataToSave: ByteArray? = null
        var isServiceRunning = AtomicBoolean(false)
    }

    @Inject
    lateinit var settingsRepository: SettingsRepository

    // 1. Create a private, mutable state flow that the service controls.
    private val _isRecording = MutableStateFlow(false)

    // 2. Expose it publicly as a read-only StateFlow, fulfilling the interface.
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val hasOverflowed: AtomicReference<Boolean> = AtomicReference(false)

    private val recorderIndex: AtomicReference<Int> = AtomicReference(0)

    private val totalRingBufferSize: AtomicReference<Int> = AtomicReference(100)

    private val recorded_duration: AtomicReference<String> = AtomicReference("00:00:00")

    private val lock: ReentrantLock = ReentrantLock()

    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    override fun onCreate() {
        super.onCreate()
        Log.i(logTag, "onCreate()")
        isServiceRunning.set(true)

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        createNotificationChannels()
        requestAudioFocus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(logTag, "onStartCommand()")

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
        isServiceRunning.set(false)
        stopRecording()
        serviceScope.cancel()
        abandonAudioFocus()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(logTag, "onTaskRemoved() called with intent: $rootIntent")
        super.onTaskRemoved(rootIntent)
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(logTag, "OnAudioFocusChangeListener() focusChange: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(logTag, "AUDIOFOCUS_LOSS")
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Completely lost focus, likely due to an incoming or outgoing call
                Log.d(
                    logTag,
                    "AUDIOFOCUS_LOSS_TRANSIENT, restarting recording for VOICE_COMMUNICATION"
                )
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(logTag, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK")
            }

            AudioManager.AUDIOFOCUS_GAIN, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                Log.d(
                    logTag, "AUDIOFOCUS_GAIN, restarting recording for VOICE_RECOGNITION"
                )
            }
        }
    }

    private fun requestAudioFocus() {
        Log.d(logTag, "requestAudioFocus()")
        val result = audioManager.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
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
            config.sampleRateHz * (config.bitDepth.bits / 8) * config.bufferTimeLengthS

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

    private fun initializeAndBuildRecorder(): RecorderInfo {
        val micPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (micPermission == PackageManager.PERMISSION_DENIED) {
            Log.e(
                logTag, "Audio record permission not granted, can't record..."
            )
            resetBuffer()
            throw SecurityException("Audio record permission not granted.")
        }

        val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION

        val audioFormat = AudioFormat.Builder().setEncoding(config.bitDepth.encodingEnum)
            .setSampleRate(config.sampleRateHz).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()

        // 1. Calculate how many bytes are generated per second.
        val bytesPerSecond = config.sampleRateHz * (config.bitDepth.bits / 8)

        // 2. Set our target: we want to read audio in 250ms chunks (4 times per second).
        // This ensures the read() call returns quickly.
        val readChunkSize = (bytesPerSecond * 0.5).toInt()

        // 3. Make the internal hardware buffer larger than our read chunk size.
        // This is a best practice to prevent data loss if the system gets busy.
        val internalBufferSize = readChunkSize * 2

        val recorder = AudioRecord.Builder().setAudioSource(audioSource).setAudioFormat(audioFormat)
            .setBufferSizeInBytes(internalBufferSize).build()

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            // Throw another exception if the hardware fails to initialize
            throw IllegalStateException("AudioRecord failed to initialize.")
        }

        // Permission has been granted
        return RecorderInfo(recorder, readChunkSize)
    }

    override fun stopRecording() {
        if (!_isRecording.value) return
        _isRecording.value = false

        // Stop both loops
        recordingJob?.cancel()
        notificationJob?.cancel()

        // Update notification one last time
        updateNotification()
    }


    private fun updateDurationToDisplay() {
        val sampleRate = config.sampleRateHz
        val bitsPerSample = config.bitDepth.bits
        val channels = 1

        if (sampleRate == 0 || bitsPerSample == 0) {
            recorded_duration.set("00:00:00")
            return
        }

        // ✅ This value is the number of BYTES recorded.
        val totalRecordedBytes: Int = if (hasOverflowed.get()) {
            totalRingBufferSize.get()
        } else {
            recorderIndex.get()
        }

        // ✅ This is the correct formula: total bytes / (bytes per second)
        val bytesPerSecond = sampleRate * (bitsPerSample / 8) * channels
        val durationInSeconds = totalRecordedBytes.toDouble() / bytesPerSecond

        val flooredDuration = floor(durationInSeconds).toLong()

        // Format duration into HH:mm:ss
        val hours = TimeUnit.SECONDS.toHours(flooredDuration)
        val minutes = TimeUnit.SECONDS.toMinutes(flooredDuration) % 60
        val seconds = flooredDuration % 60

        val durationFormatted = String.format(
            Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds
        )

        recorded_duration.set(durationFormatted)
    }

    override fun writeWavHeader(out: OutputStream, audioDataLen: Int) {
        val channels = 1.toShort() // Recording is in mono
        val sampleRate = config.sampleRateHz
        val bitsPerSample = config.bitDepth.bits.toShort()

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

    override fun resetBuffer() {
        Log.d(logTag, "resetBuffer()")
        recorderIndex.set(0)
        hasOverflowed.set(false)
        updateDurationToDisplay()
        updateNotification()
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

    data class RecorderInfo(val recorder: AudioRecord, val readChunkSize: Int)

    override fun startRecording() {
        if (_isRecording.value) return
        _isRecording.value = true

        // Start the audio recording loop
        recordingJob?.cancel()
        recordingJob = serviceScope.launch {
            var recorder: AudioRecord? = null
            try {
                val isNewSession = recorderIndex.get() == 0 && !hasOverflowed.get()
                if (isNewSession) {
                    Log.d(logTag, "Starting a new session. Fetching latest config.")
                    // If it's new, fetch the latest config and set up the buffer.
                    config = settingsRepository.getAudioConfig()
                    updateTotalBufferSize(config)
                } else {
                    Log.d(logTag, "Continuing a paused session. Using existing config.")
                }
                val initResult = initializeAndBuildRecorder()
                recorder = initResult.recorder
                val readChunkSize = initResult.readChunkSize
                recorder.startRecording()

                // This is now a high-frequency polling loop for audio
                while (isActive && _isRecording.value) {
                    val readDataChunk = ByteArray(readChunkSize)
                    val readResult = recorder.read(
                        readDataChunk, 0, readChunkSize
                    )

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
                    } else if (readResult == AudioRecord.ERROR_INVALID_OPERATION) {
                        Log.e(
                            logTag,
                            "AudioRecord invalid operation. Params: (${audioDataStorage.size}, ${recorderIndex.get()}, $readChunkSize)"
                        )
                    } else if (readResult == AudioRecord.ERROR_BAD_VALUE) {
                        Log.e(
                            logTag,
                            "AudioRecord bad value. Params: (${audioDataStorage.size}, ${recorderIndex.get()}, $readChunkSize)"
                        )
                    } else if (recorder.state == AudioRecord.RECORDSTATE_STOPPED) {
                        Log.w(logTag, "AudioRecord stopped unexpectedly")
                    } else {
                        Log.e(
                            logTag,
                            "AudioRecord other error state: ${recorder.state}, result: $readResult. Params: (${audioDataStorage.size}, ${recorderIndex.get()}, $readChunkSize)"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(logTag, "Failed to start or run recording", e)
                _isRecording.value = false // Revert state on any failure.
            } finally {
                // This now correctly cleans up only when the coroutine is finished or cancelled.
                recorder?.stop()
                recorder?.release()
                Log.d(logTag, "Recording coroutine finished and cleaned up.")
            }
        }

        startNotificationUpdates()
    }

    // Start the separate, parallel notification timer loop
    @kotlin.OptIn(ObsoleteCoroutinesApi::class)
    private fun startNotificationUpdates() {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            val tickerChannel = ticker(delayMillis = 1000)
            while (isActive && _isRecording.value) {
                updateDurationToDisplay()
                updateNotification()
                tickerChannel.receive()
            }
        }
    }

    inner class MyBinder : Binder() {
        fun getService(): MyBufferServiceInterface = this@MyBufferService
    }

    private val binder = MyBinder()

    override fun onBind(intent: Intent): IBinder {
        return binder
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
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    @OptIn(UnstableApi::class)
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

        // Intent to open MainActivity when the notification body is clicked
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                // Add flags to ensure the activity is brought to the front if it's already running
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val recordingNotification =
            NotificationCompat.Builder(this, CHRONIC_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Buffered Recent Audio")
                .setContentText(if (_isRecording.value) "Running...\n" else "Stopped.\n")
                .setContentText(
                    "${
                        if (hasOverflowed.get()) "100%" else "${
                            ((recorderIndex.get()
                                .toFloat() / totalRingBufferSize.get()) * 100).roundToInt()
                        }%"
                    } - ${recorded_duration.get()}"
                ).setSmallIcon(R.drawable.baseline_record_voice_over_24)
                .setProgress(  // Bar visualization
                    totalRingBufferSize.get(), if (hasOverflowed.get()) {
                        totalRingBufferSize.get()
                    } else {
                        recorderIndex.get()
                    }, false
                ).addAction(
                    if (_isRecording.value) R.drawable.baseline_mic_24 else R.drawable.baseline_mic_off_24,
                    if (_isRecording.value) "Pause" else "Continue", // Update action text
                    if (_isRecording.value) stopIntent else startIntent // Update PendingIntent
                ).addAction(
                    if (_isRecording.value) R.drawable.baseline_save_alt_24 else 0,
                    if (_isRecording.value) "Save and clear" else null,
                    if (_isRecording.value) saveIntent else null
                ).setAutoCancel(false) // Keep notification after being tapped
                .setSmallIcon(R.drawable.baseline_record_voice_over_24) // Set the small icon
                .setOngoing(true) // Make it a chronic notification
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true) // IMPORTANCE_DEFAULT otherwise notifies on each update
                .setSilent(true) // Don't make sounds
                .setContentIntent(contentIntent) // Onclick open MainScreen
                .setCategory(NotificationCompat.CATEGORY_SERVICE).build()

        return recordingNotification
    }

    private fun updateNotification() {
        val notification = createNotification()
        notificationManager.notify(CHRONIC_NOTIFICATION_ID, notification)
    }
}
