/*
 * Auto Record Moments
 * Copyright (C) 2025 Morten Fjord Christensen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.mfc.recentaudiobuffer

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Notification
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
import android.os.IBinder
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
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
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import kotlin.math.floor
import kotlin.math.roundToInt

interface MyBufferServiceInterface {
    suspend fun pauseSortAndGetBuffer(): ByteBuffer
    fun stopRecording()
    fun startRecording()
    fun resetBuffer()
    suspend fun quickSaveBuffer()

    val isRecording: StateFlow<Boolean>
    val isLoading: StateFlow<Boolean>
}

@AndroidEntryPoint
class MyBufferService : Service(), MyBufferServiceInterface {
    private var config: SettingsConfig = SettingsConfig()
    private lateinit var audioDataStorage: ByteArray
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null
    private var notificationJob: Job? = null

    companion object {
        const val CHRONIC_NOTIFICATION_ID = 1
        const val CHRONIC_NOTIFICATION_CHANNEL_ID = "recording_channel"
        const val ACTION_STOP_RECORDING_SERVICE = "com.example.app.ACTION_STOP_RECORDING_SERVICE"
        const val ACTION_START_RECORDING_SERVICE = "com.example.app.ACTION_START_RECORDING_SERVICE"
        const val ACTION_SAVE_RECORDING_SERVICE = "com.example.app.ACTION_SAVE_RECORDING_SERVICE"
        const val ACTION_RESTART_WITH_NEW_SETTINGS =
            "com.mfc.recentaudiobuffer.ACTION_RESTART_WITH_NEW_SETTINGS"
        const val ACTION_ON_SAVE_SUCCESS = "com.mfc.recentaudiobuffer.ACTION_ON_SAVE_SUCCESS"
        const val ACTION_ON_SAVE_FAIL = "com.mfc.recentaudiobuffer.ACTION_ON_SAVE_FAIL"

        @VisibleForTesting
        const val ACTION_SAVE_COMPLETE = "com.mfc.recentaudiobuffer.ACTION_SAVE_COMPLETE"
        private const val REQUEST_CODE_STOP = 1
        private const val REQUEST_CODE_START = 2
        private const val REQUEST_CODE_SAVE = 3

        // Static variable to hold the buffer
        var isServiceRunning = AtomicBoolean(false)
    }

    private val _serviceError = MutableStateFlow<String?>(null)
    val serviceError: StateFlow<String?> = _serviceError.asStateFlow()

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var vadProcessor: VADProcessor

    private val _trimmingProgress = MutableStateFlow(-1f) // -1f indicates not started
    val trimmingProgress: StateFlow<Float> = _trimmingProgress.asStateFlow()

    private val _trimmingEta = MutableStateFlow(0L) // ETA in seconds
    val trimmingEta: StateFlow<Long> = _trimmingEta.asStateFlow()

    // 1. Create a private, mutable state flow that the service controls.
    private val _isRecording = MutableStateFlow(false)

    // 2. Expose it publicly as a read-only StateFlow, fulfilling the interface.
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    @VisibleForTesting
    val hasOverflowed: AtomicReference<Boolean> = AtomicReference(false)

    private val recorderIndex: AtomicReference<Int> = AtomicReference(0)

    private val totalRingBufferSize: AtomicReference<Int> = AtomicReference(100)

    private val recordedDuration: AtomicReference<String> = AtomicReference("00:00:00")

    private val lock: ReentrantLock = ReentrantLock()

    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    override fun onCreate() {
        super.onCreate()
        Timber.i("onCreate()")
        isServiceRunning.set(true)

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("onStartCommand()")

        when (intent?.action) {
            ACTION_STOP_RECORDING_SERVICE -> {
                stopRecording()
            }

            ACTION_START_RECORDING_SERVICE -> {
                startRecording()
            }

            ACTION_SAVE_RECORDING_SERVICE -> {
                Timber.d("Got ACTION_SAVE_RECORDING_SERVICE intent")
                serviceScope.launch {
                    quickSaveBuffer()
                }
            }

            ACTION_RESTART_WITH_NEW_SETTINGS -> {
                serviceScope.launch {
                    Timber.d("Restarting service with new settings...")
                    stopRecording()
                    quickSaveBuffer()
                }
            }

            ACTION_ON_SAVE_SUCCESS -> {
                Timber.d("Got ACTION_ON_SAVE_SUCCESS intent")
                resetBuffer()
                startRecording() // Restart
                _isLoading.value = false
                updateNotification() // Revert to normal notification state
                val intent = Intent(ACTION_SAVE_COMPLETE).apply {
                    setPackage(packageName)
                }
                sendBroadcast(intent)
                Timber.d("Sent ACTION_SAVE_COMPLETE broadcast.")
            }

            ACTION_ON_SAVE_FAIL -> {
                Timber.d("Got ACTION_ON_SAVE_FAIL intent")
                _isLoading.value = false
                updateNotification() // Revert to normal notification state
                val intent = Intent(ACTION_SAVE_COMPLETE).apply {
                    setPackage(packageName)
                }
                sendBroadcast(intent)
                Timber.d("Sent ACTION_SAVE_COMPLETE broadcast.")
            }
        }

        startForeground(CHRONIC_NOTIFICATION_ID, createNotification())

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("onDestroy()")
        isServiceRunning.set(false)
        stopRecording()
        serviceScope.cancel()
        abandonAudioFocus()
    }

    @VisibleForTesting
    fun prepareForTestRecording() {
        _isRecording.value = true
        // We intentionally DO NOT launch the real recordingJob.
        // The test will provide the audio data directly via writeDataToBufferForTest().
        serviceScope.launch {
            // We still need to initialize the config and buffer size
            config = settingsRepository.getSettingsConfig()
            updateTotalBufferSize(config)
        }
        // We can still start the notification updates if we want
        startNotificationUpdates()
    }

    /**
     * This method allows tests to push mock audio chunks directly into the buffer,
     * Simulates writing a chunk of data
     * into the circular buffer, respecting the overflow logic.
     */
    @VisibleForTesting
    fun writeDataToBufferForTest(data: ByteArray) {
        if (isRecording.value && ::audioDataStorage.isInitialized) {
            lock.lock()
            try {
                val readResult = data.size
                if (recorderIndex.get() + readResult > totalRingBufferSize.get()) {
                    hasOverflowed.set(true)
                    val bytesToEnd = totalRingBufferSize.get() - recorderIndex.get()
                    if (bytesToEnd > 0) {
                        System.arraycopy(data, 0, audioDataStorage, recorderIndex.get(), bytesToEnd)
                    }
                    val remainingBytes = readResult - bytesToEnd
                    System.arraycopy(data, bytesToEnd, audioDataStorage, 0, remainingBytes)
                    recorderIndex.set(remainingBytes)
                } else {
                    System.arraycopy(data, 0, audioDataStorage, recorderIndex.get(), readResult)
                    recorderIndex.set(recorderIndex.get() + readResult)
                }
            } finally {
                lock.unlock()
            }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Timber.d("OnAudioFocusChangeListener() focusChange: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Timber.d("AUDIOFOCUS_LOSS")
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Completely lost focus, likely due to an incoming or outgoing call
                Timber.d(
                    "AUDIOFOCUS_LOSS_TRANSIENT, restarting recording for VOICE_COMMUNICATION"
                )
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Timber.d("AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK")
            }

            AudioManager.AUDIOFOCUS_GAIN, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                Timber.d(
                    "AUDIOFOCUS_GAIN, restarting recording for VOICE_RECOGNITION"
                )
            }
        }
    }

    private fun requestAudioFocus() {
        Timber.d("requestAudioFocus()")
        val result = audioManager.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Timber.d("Audio focus granted")
        } else {
            Timber.e("Audio focus request failed")
        }
    }

    private fun abandonAudioFocus() {
        Timber.d("abandonAudioFocus()")
        audioManager.abandonAudioFocus(audioFocusChangeListener)
    }

    private fun updateTotalBufferSize(config: SettingsConfig) {
        var idealBufferSize =
            (config.sampleRateHz.toLong() * (config.bitDepth.bits / 8) * config.bufferTimeLengthS).toInt()
        Timber.d("Ideal buffer size calculated: $idealBufferSize bytes")

        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val bytesPerSecond = config.sampleRateHz * (config.bitDepth.bits / 8)

        if (memoryInfo.lowMemory) {
            Timber.e("Device is in a low memory state.")
            if (idealBufferSize > LOW_MEMORY_MAX_BUFFER_SIZE_B) {
                val cappedDuration = LOW_MEMORY_MAX_BUFFER_SIZE_B / bytesPerSecond
                _serviceError.value =
                    "Warning: Low memory detected. Buffer length limited to $cappedDuration seconds."
                idealBufferSize = LOW_MEMORY_MAX_BUFFER_SIZE_B
            }
        }

        if (idealBufferSize > MAX_BUFFER_SIZE_B) {
            Timber.e("Ideal buffer size exceeds app's MAX_BUFFER_SIZE_B. Capping.")
            val cappedDuration = MAX_BUFFER_SIZE_B / bytesPerSecond
            _serviceError.value =
                "Buffer size exceeds ${MAX_BUFFER_SIZE_B / 1_000_000} MB limit. Capping buffer length to $cappedDuration seconds."
            idealBufferSize = MAX_BUFFER_SIZE_B
        }

        totalRingBufferSize.set(idealBufferSize)
        var allocationSize = idealBufferSize.toFloat()
        if (config.isAiAutoClipEnabled) {
            allocationSize *= AI_ENABLED_EXTRA_MEMORY_USAGE_FRACTION // VAD needs extra memory for float conversion
        }
        audioDataStorage = ByteArray(allocationSize.toInt())
        Timber.d("Successfully allocated buffer of size: ${allocationSize.toInt()} bytes")
    }


    private fun initializeAndBuildRecorder(): RecorderInfo {
        val micPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (micPermission == PackageManager.PERMISSION_DENIED) {
            Timber.e(
                "Audio record permission not granted, can't record..."
            )
            resetBuffer()
            throw SecurityException("Audio record permission not granted.")
        }

        val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION

        val audioFormat = AudioFormat.Builder().setEncoding(config.bitDepth.encodingEnum)
            .setSampleRate(config.sampleRateHz).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()

        // 1. Calculate how many bytes are generated per second.
        val bytesPerSecond = config.sampleRateHz * (config.bitDepth.bits / 8)

        // 2. Set our target: we want to read audio in eg 250ms chunks (4 times per second).
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

        // Stop both loops async
        recordingJob?.cancel()
        notificationJob?.cancel()

        updateNotification()
    }


    private fun updateDurationToDisplay() {
        val sampleRate = config.sampleRateHz
        val bitsPerSample = config.bitDepth.bits
        val channels = 1

        if (sampleRate == 0 || bitsPerSample == 0) {
            recordedDuration.set("00:00:00")
            return
        }

        val totalRecordedBytes: Int = if (hasOverflowed.get()) {
            totalRingBufferSize.get()
        } else {
            recorderIndex.get()
        }

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

        recordedDuration.set(durationFormatted)
    }

    override suspend fun pauseSortAndGetBuffer(): ByteBuffer {
        stopRecording()
        recordingJob?.join()
        notificationJob?.join()
        lock.lock()
        try {
            // Prevent accessing uninitialized data
            if (!::audioDataStorage.isInitialized || totalRingBufferSize.get() == 0) {
                return ByteBuffer.allocate(0)
            }

            // Prevent reversing to negative index
            if (recorderIndex.get() == 0 && !hasOverflowed.get()) {
                return ByteBuffer.allocate(0)
            }

            return if (hasOverflowed.get()) {
                // Memory-efficient in-place rotation
                val index = recorderIndex.get()

                // 1. Reverse the first part (from the write head to the end)
                audioDataStorage.reverse(index, audioDataStorage.size - 1)
                // 2. Reverse the second part (from the start to before the write head)
                audioDataStorage.reverse(0, index - 1)
                // 3. Reverse the entire audioDataStorage to complete the rotation
                audioDataStorage.reverse(0, audioDataStorage.size - 1)

                recorderIndex.set(0)

                // The original audioDataStorage is now correctly ordered. Return it.
                ByteBuffer.wrap(audioDataStorage).asReadOnlyBuffer()
            } else {
                // Create a zero-copy view of the relevant part of the array.
                ByteBuffer.wrap(audioDataStorage, 0, recorderIndex.get()).asReadOnlyBuffer()
            }
        } finally {
            lock.unlock()
        }
    }

    override fun resetBuffer() {
        Timber.d("resetBuffer()")
        recorderIndex.set(0)
        hasOverflowed.set(false)
        updateDurationToDisplay()
        updateNotification()
    }

    override suspend fun quickSaveBuffer() {
        if (_isLoading.value) {
            Timber.w("quickSaveBuffer called while already saving. Ignoring.")
            return
        }
        _isLoading.value = true
        _trimmingProgress.value = 0f

        // --- Simple Moving Average (SMA) setup ---
        val movingAverageWindow = 7
        val etaHistory = ArrayDeque<Long>(movingAverageWindow)

        updateNotification() // Show "Trimming... [5% -- ETA 30s]"

        try {
            val originalBuffer = pauseSortAndGetBuffer()

            val bufferToSave = if (config.isAiAutoClipEnabled && originalBuffer.hasRemaining()) {
                var startTime = 0L
                ByteBuffer.wrap(withContext(Dispatchers.Default) {
                    // Now that the progress calculation in VADProcessor is fixed,
                    // we can just let it report progress normally.
                    val finalBytes = vadProcessor.process(
                        originalBuffer, config.toAudioConfig(), onProgress = { progress ->
                            _trimmingProgress.value = progress

                            if (progress == 0f) {
                                startTime = System.currentTimeMillis()
                            } else if (progress > 0.01f) {
                                // 1. Calculate a raw, instantaneous ETA
                                val elapsedTime = System.currentTimeMillis() - startTime
                                val totalTime = elapsedTime / progress
                                val rawRemainingTime = (totalTime - elapsedTime).toLong()
                                val rawEtaSeconds =
                                    TimeUnit.MILLISECONDS.toSeconds(rawRemainingTime)

                                // 2. Add the new estimate to the history
                                etaHistory.addLast(rawEtaSeconds)

                                // 3. Trim the history if it exceeds the window size
                                if (etaHistory.size > movingAverageWindow) {
                                    etaHistory.removeFirst()
                                }

                                // 4. Calculate the average of the current history for a smooth ETA
                                if (etaHistory.isNotEmpty()) {
                                    _trimmingEta.value = etaHistory.average().toLong()
                                }
                            }
                            updateNotification()
                        })

                    // Once done, a brief 100% state before switching to "Saving..."
                    _trimmingProgress.value = 1.0f
                    _trimmingEta.value = 0
                    updateNotification()
                    kotlinx.coroutines.delay(200)

                    finalBytes
                })
            } else {
                originalBuffer
            }

            _trimmingProgress.value = -1f // Reset for the next run
            updateNotification() // This will now show "Saving..."

            val tempFileUri = withContext(Dispatchers.IO) {
                FileSavingUtils.saveBufferToTempFile(this@MyBufferService, bufferToSave)
            }

            val destDirUri = FileSavingUtils.getCachedGrantedUri(this)

            if (tempFileUri != null && destDirUri != null) {
                val timestamp = SimpleDateFormat("yy-MM-dd_HH-mm", Locale.getDefault()).format(
                    Date()
                )
                val fileName = "quicksave_${timestamp}.wav"

                val saveIntent = Intent(this, FileSavingService::class.java).apply {
                    putExtra(FileSavingService.EXTRA_TEMP_FILE_URI, tempFileUri)
                    putExtra(FileSavingService.EXTRA_DEST_DIR_URI, destDirUri)
                    putExtra(FileSavingService.EXTRA_DEST_FILENAME, fileName)
                    putExtra(FileSavingService.EXTRA_AUDIO_CONFIG, config.toAudioConfig())
                }
                startService(saveIntent)
            } else {
                Timber.e("Failed to quick save. Temp URI: $tempFileUri, Dest Dir URI: $destDirUri")
                Toast.makeText(
                    this, "Quick save failed. No save directory set.", Toast.LENGTH_LONG
                ).show()
                val selfIntent = Intent(this, MyBufferService::class.java).apply {
                    action = ACTION_ON_SAVE_FAIL
                }
                onStartCommand(selfIntent, 0, 0)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error during quick save process")
            Toast.makeText(this, "Error: Could not save file: $e", Toast.LENGTH_LONG).show()
            val selfIntent = Intent(this, MyBufferService::class.java).apply {
                action = ACTION_ON_SAVE_FAIL
            }
            onStartCommand(selfIntent, 0, 0)
        }
    }

    data class RecorderInfo(val recorder: AudioRecord, val readChunkSize: Int)

    override fun startRecording() {
        if (_isRecording.value) return
        _isRecording.value = true

        requestAudioFocus()

        // Start the audio recording loop
        recordingJob?.cancel()
        recordingJob = serviceScope.launch {
            var recorder: AudioRecord? = null
            try {
                val isNewSession = recorderIndex.get() == 0 && !hasOverflowed.get()
                if (isNewSession) {
                    Timber.d("Starting a new session. Fetching latest config.")
                    // If it's new, fetch the latest config and set up the buffer.
                    config = settingsRepository.getSettingsConfig()
                    updateTotalBufferSize(config)
                } else {
                    Timber.d("Continuing a paused session. Using existing config.")
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

                    Timber.d(
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
                        Timber.e(
                            "AudioRecord invalid operation. Params: (${audioDataStorage.size}, ${recorderIndex.get()}, $readChunkSize)"
                        )
                    } else if (readResult == AudioRecord.ERROR_BAD_VALUE) {
                        Timber.e(
                            "AudioRecord bad value. Params: (${audioDataStorage.size}, ${recorderIndex.get()}, $readChunkSize)"
                        )
                    } else if (recorder.state == AudioRecord.RECORDSTATE_STOPPED) {
                        Timber.w("AudioRecord stopped unexpectedly")
                    } else {
                        Timber.e(
                            "AudioRecord other error state: ${recorder.state}, result: $readResult. Params: (${audioDataStorage.size}, ${recorderIndex.get()}, $readChunkSize)"
                        )
                    }
                }
            } catch (t: Throwable) {
                Timber.e("Failed to start or run recording $t")

                val errorMessage = if (t is OutOfMemoryError) {
                    "Not enough memory to start recording. Please lower the buffer size in settings."
                } else {
                    "Failed to start recording. Please try again."
                }
                _serviceError.value = errorMessage
            } finally {
                recorder?.stop()
                recorder?.release()
                Timber.d("Recording coroutine finished and cleaned up.")
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

    fun clearServiceError() {
        _serviceError.value = null
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this).setTitle("Recording Error").setMessage(message)
            .setPositiveButton("OK", null).show()
    }

    inner class MyBinder : Binder() {
        fun getService(): MyBufferServiceInterface = this@MyBufferService
    }

    private val binder = MyBinder()

    override fun onBind(intent: Intent): IBinder {
        return binder
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

        val canSave = recorderIndex.get() > 0 || hasOverflowed.get()

        val builder = NotificationCompat.Builder(this, CHRONIC_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Buffered Recent Audio")
            .setSmallIcon(R.drawable.baseline_record_voice_over_24)
            .setAutoCancel(false) // Keep notification after being tapped
            .setOngoing(true) // Make it a chronic notification
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true) // IMPORTANCE_DEFAULT otherwise notifies on each update
            .setSilent(true) // Don't make sounds
            .setContentIntent(contentIntent) // Onclick open MainScreen
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (_isLoading.value) {
            if (_trimmingProgress.value >= 0f) {
                val progressPercent = (_trimmingProgress.value * 100).roundToInt()
                val etaText = if (_trimmingEta.value > 0) " -- ETA ${_trimmingEta.value}s" else ""
                builder.setContentText("AI Trimming... [${progressPercent}%${etaText}]")
                    .setProgress(100, progressPercent, false)
            } else {
                builder.setContentText("Saving...").setProgress(0, 0, true)
            }
        } else {
            builder.setContentText(
                "${
                    if (hasOverflowed.get()) "100%" else "${
                        ((recorderIndex.get()
                            .toFloat() / totalRingBufferSize.get()) * 100).roundToInt()
                    }%"
                } - ${recordedDuration.get()}"
            ).setProgress(
                totalRingBufferSize.get(),
                if (hasOverflowed.get()) totalRingBufferSize.get() else recorderIndex.get(),
                false
            ).addAction(
                if (_isRecording.value) R.drawable.baseline_mic_24 else R.drawable.baseline_mic_off_24,
                if (_isRecording.value) "Pause" else "Continue",
                if (_isRecording.value) stopIntent else startIntent
            ).addAction(
                R.drawable.baseline_save_alt_24, "Save and Clear", if (canSave) saveIntent else null
            )
        }

        return builder.build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        notificationManager.notify(CHRONIC_NOTIFICATION_ID, notification)
    }
}
