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
import com.mfc.recentaudiobuffer.speakeridentification.Speaker
import com.mfc.recentaudiobuffer.speakeridentification.SpeakerIdentifier
import com.mfc.recentaudiobuffer.speakeridentification.SpeakerRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ticker
import kotlinx.coroutines.delay
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
import java.util.concurrent.ConcurrentHashMap
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
    private var autoSaveJob: Job? = null
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

        // --- Constants for Auto-Save Feature ---
        private const val SILENCE_SAVE_THRESHOLD_MS = 15_000L
        private const val AUTO_SAVE_CHECK_INTERVAL_MS = 3_000L

        var isServiceRunning = AtomicBoolean(false)
    }

    private val _serviceError = MutableStateFlow<String?>(null)
    val serviceError: StateFlow<String?> = _serviceError.asStateFlow()

    @Inject
    lateinit var settingsRepository: SettingsRepository
    @Inject
    lateinit var speakerRepository: SpeakerRepository
    @Inject
    lateinit var speakerIdentifier: SpeakerIdentifier
    @Inject
    lateinit var vadProcessor: VADProcessor

    private val _trimmingProgress = MutableStateFlow(-1f)
    val trimmingProgress: StateFlow<Float> = _trimmingProgress.asStateFlow()
    private val _trimmingEta = MutableStateFlow(0L)
    val trimmingEta: StateFlow<Long> = _trimmingEta.asStateFlow()
    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    @VisibleForTesting
    val hasOverflowed: AtomicReference<Boolean> = AtomicReference(false)
    private val recorderIndex: AtomicReference<Int> = AtomicReference(0)
    private val totalRingBufferSize: AtomicReference<Int> = AtomicReference(100)
    private val recordedDuration: AtomicReference<String> = AtomicReference("00:00:00")
    private val lock = ReentrantLock()
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    // --- State for Automatic Saving Feature ---
    private var knownSpeakers: List<Speaker> = emptyList()
    private val activeUtteranceBuffers = ConcurrentHashMap<String, MutableList<ByteArray>>()
    private val speakerLastHeardTimestamp = ConcurrentHashMap<String, Long>()

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
            FileSavingService.ACTION_SILENT_SAVE_COMPLETE -> {
                // This is a special action from an auto-save, we don't want to reset buffer or stop.
                Timber.d("Auto-save completed silently.")
                return START_STICKY
            }
            ACTION_STOP_RECORDING_SERVICE -> stopRecording()
            ACTION_START_RECORDING_SERVICE -> startRecording()
            ACTION_SAVE_RECORDING_SERVICE -> serviceScope.launch { quickSaveBuffer() }
            ACTION_RESTART_WITH_NEW_SETTINGS -> serviceScope.launch {
                Timber.d("Restarting service with new settings...")
                stopRecording(savePendingUtterances = true) // Save pending utterances before stopping
                startRecording()
            }
            ACTION_ON_SAVE_SUCCESS -> {
                Timber.d("Manual save successful.")
                resetBuffer()
                startRecording()
                _isLoading.value = false
                updateNotification()
                sendBroadcast(Intent(ACTION_SAVE_COMPLETE).setPackage(packageName))
            }
            ACTION_ON_SAVE_FAIL -> {
                _isLoading.value = false
                updateNotification()
                sendBroadcast(Intent(ACTION_SAVE_COMPLETE).setPackage(packageName))
            }
        }
        startForeground(CHRONIC_NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("onDestroy()")
        isServiceRunning.set(false)
        stopRecording(savePendingUtterances = true)
        serviceScope.cancel()
        abandonAudioFocus()
    }

    override fun startRecording() {
        if (_isRecording.value) return
        _isRecording.value = true

        serviceScope.launch { settingsRepository.updateWasBufferingActive(true) }
        requestAudioFocus()

        recordingJob?.cancel()
        autoSaveJob?.cancel()

        recordingJob = serviceScope.launch(Dispatchers.IO) {
            var recorder: AudioRecord? = null
            try {
                config = settingsRepository.getSettingsConfig()
                updateTotalBufferSize(config)
                resetBuffer() // Ensure a clean state on start

                if (config.isSpeakerAutoClipEnabled) {
                    knownSpeakers = speakerRepository.getSpeakersByIds(config.selectedSpeakerIds.toList())
                    // Reset VAD state for a new session.
                    vadProcessor.resetStreamingState()
                    Timber.d("Auto-Save Mode enabled for ${knownSpeakers.size} speakers.")
                }

                val initResult = initializeAndBuildRecorder()
                recorder = initResult.recorder
                val readChunkSize = initResult.readChunkSize
                recorder.startRecording()

                while (isActive && _isRecording.value) {
                    val audioChunk = ByteArray(readChunkSize)
                    val readResult = recorder.read(audioChunk, 0, readChunkSize)
                    if (readResult <= 0) continue

                    val validChunk = if (readResult < readChunkSize) audioChunk.copyOf(readResult) else audioChunk

                    // 1. Always write to the main circular buffer for manual saves
                    writeToCircularBuffer(validChunk)

                    // 2. If Auto-Save is enabled, process the chunk
                    if (config.isSpeakerAutoClipEnabled && knownSpeakers.isNotEmpty()) {
                        // Resample the chunk before passing it to the VAD using the processor's utility
                        val chunkForVAD = vadProcessor.resampleAudioChunk(
                            sourceChunk = validChunk,
                            sourceRate = config.sampleRateHz,
                            targetRate = VADProcessor.VAD_MAX_SAMPLE_RATE
                        )
                        processAudioChunkForAutoSave(chunkForVAD)
                    }
                }
            } catch (t: Throwable) {
                Timber.e(t, "Recording job failed")
                _serviceError.value = "Failed to start recording. Please try again."
            } finally {
                recorder?.stop()
                recorder?.release()
                stopRecording()
            }
        }

        if (config.isSpeakerAutoClipEnabled) {
            autoSaveJob = serviceScope.launch {
                while (isActive && _isRecording.value) {
                    delay(AUTO_SAVE_CHECK_INTERVAL_MS)
                    checkForFinishedUtterances()
                }
            }
        }
        startNotificationUpdates()
    }

    private suspend fun processAudioChunkForAutoSave(chunk: ByteArray) {
        val isSpeech = vadProcessor.isSpeech(chunk)

        if (isSpeech) {
            // Offload the potentially heavy ML task to a computation thread pool
            withContext(Dispatchers.Default) {
                val speakerId = speakerIdentifier.identifySpeaker(chunk, knownSpeakers)
                if (speakerId != null) {
                    // An active, selected speaker was detected
                    activeUtteranceBuffers.getOrPut(speakerId) { mutableListOf() }.add(chunk)
                    speakerLastHeardTimestamp[speakerId] = System.currentTimeMillis()
                }
            }
        }
    }

    private suspend fun checkForFinishedUtterances() {
        val now = System.currentTimeMillis()
        val finishedSpeakers = speakerLastHeardTimestamp.filterValues { now - it > SILENCE_SAVE_THRESHOLD_MS }.keys

        for (speakerId in finishedSpeakers) {
            Timber.i("Silence threshold reached for speaker $speakerId. Saving utterance.")
            val utteranceChunks = activeUtteranceBuffers.remove(speakerId)
            speakerLastHeardTimestamp.remove(speakerId)
            if (!utteranceChunks.isNullOrEmpty()) {
                saveUtterance(speakerId, utteranceChunks)
            }
        }
    }

    private fun handleOverflowSave() {
        Timber.w("Main buffer overflowed. Triggering auto-save for all active utterances.")
        serviceScope.launch {
            val speakerIds = activeUtteranceBuffers.keys.toList()
            for (speakerId in speakerIds) {
                val utteranceChunks = activeUtteranceBuffers.remove(speakerId)
                speakerLastHeardTimestamp.remove(speakerId)
                if (!utteranceChunks.isNullOrEmpty()) {
                    saveUtterance(speakerId, utteranceChunks)
                }
            }
        }
    }

    private suspend fun saveUtterance(speakerId: String, chunks: List<ByteArray>) {
        val speaker = knownSpeakers.find { it.id == speakerId } ?: return
        val totalSize = chunks.sumOf { it.size }
        if (totalSize == 0) return

        val combinedBuffer = ByteBuffer.allocate(totalSize).apply {
            chunks.forEach { put(it) }
            flip()
        }

        withContext(Dispatchers.IO) {
            val tempFileUri = FileSavingUtils.saveBufferToTempFile(this@MyBufferService, combinedBuffer)
            val destDirUri = FileSavingUtils.getCachedGrantedUri(this@MyBufferService)

            if (tempFileUri != null && destDirUri != null) {
                val timestamp = SimpleDateFormat("yyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "rec_${speaker.name}_${timestamp}.wav"

                // Create an AudioConfig specifically for the 16kHz resampled audio.
                // Using the original config would write an incorrect WAV header.
                val vadAudioConfig = AudioConfig(
                    sampleRateHz = VADProcessor.VAD_MAX_SAMPLE_RATE,
                    bitDepth = config.bitDepth, // Bit depth is unchanged by our resampling.
                    bufferTimeLengthS = 0 // Not relevant for a saved file.
                )

                val saveIntent = Intent(this@MyBufferService, FileSavingService::class.java).apply {
                    putExtra(FileSavingService.EXTRA_TEMP_FILE_URI, tempFileUri)
                    putExtra(FileSavingService.EXTRA_DEST_DIR_URI, destDirUri)
                    putExtra(FileSavingService.EXTRA_DEST_FILENAME, fileName)
                    putExtra(FileSavingService.EXTRA_AUDIO_CONFIG, vadAudioConfig)
                    // Use a silent action so the service doesn't restart or reset after auto-save
                    action = FileSavingService.ACTION_SILENT_SAVE_COMPLETE
                }
                startService(saveIntent)
                Timber.i("Auto-saving file: $fileName")
            } else {
                Timber.e("Auto-save failed. Temp URI: $tempFileUri, Dest Dir URI: $destDirUri")
            }
        }
    }

    private fun writeToCircularBuffer(data: ByteArray) {
        lock.lock()
        try {
            val wasNotFull = !hasOverflowed.get()
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

            // Check if the buffer just became full on this write operation
            if (wasNotFull && hasOverflowed.get() && config.isSpeakerAutoClipEnabled) {
                handleOverflowSave()
            }
        } finally {
            lock.unlock()
        }
    }

    fun stopRecording(savePendingUtterances: Boolean) {
        if (!_isRecording.value) return
        _isRecording.value = false

        serviceScope.launch { settingsRepository.updateWasBufferingActive(false) }

        if (savePendingUtterances && config.isSpeakerAutoClipEnabled) {
            Timber.i("Stopping service, saving all pending utterances.")
            handleOverflowSave() // Re-use this logic to flush all buffers
        }

        recordingJob?.cancel()
        autoSaveJob?.cancel()
        notificationJob?.cancel()

        updateNotification()
    }

    override fun stopRecording() {
        stopRecording(savePendingUtterances = false)
    }

    override fun resetBuffer() {
        Timber.d("resetBuffer()")
        // This resets both the main buffer and the auto-save buffers
        recorderIndex.set(0)
        hasOverflowed.set(false)
        activeUtteranceBuffers.clear()
        speakerLastHeardTimestamp.clear()
        updateDurationToDisplay()
        updateNotification()
    }

    @VisibleForTesting
    fun prepareForTestRecording() {
        _isRecording.value = true
        serviceScope.launch {
            config = settingsRepository.getSettingsConfig()
            updateTotalBufferSize(config)
        }
        startNotificationUpdates()
    }

    @VisibleForTesting
    fun writeDataToBufferForTest(data: ByteArray) {
        if (isRecording.value && ::audioDataStorage.isInitialized) {
            writeToCircularBuffer(data)
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Timber.d("OnAudioFocusChangeListener() focusChange: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Timber.d("AUDIOFOCUS_LOSS")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Timber.d("AUDIOFOCUS_LOSS_TRANSIENT, restarting recording for VOICE_COMMUNICATION")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Timber.d("AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK")
            }
            AudioManager.AUDIOFOCUS_GAIN, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                Timber.d("AUDIOFOCUS_GAIN, restarting recording for VOICE_RECOGNITION")
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
        if (!::audioDataStorage.isInitialized || audioDataStorage.size != idealBufferSize) {
            audioDataStorage = ByteArray(idealBufferSize)
            Timber.d("Successfully allocated circular buffer of size: $idealBufferSize bytes")
        }
    }

    private fun initializeAndBuildRecorder(): RecorderInfo {
        val micPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (micPermission == PackageManager.PERMISSION_DENIED) {
            Timber.e("Audio record permission not granted, can't record...")
            resetBuffer()
            throw SecurityException("Audio record permission not granted.")
        }

        val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
        val audioFormat = AudioFormat.Builder().setEncoding(config.bitDepth.encodingEnum)
            .setSampleRate(config.sampleRateHz).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()

        val bytesPerSecond = config.sampleRateHz * (config.bitDepth.bits / 8)
        val readChunkSize = (bytesPerSecond * 0.5).toInt()
        val internalBufferSize = readChunkSize * 2

        val recorder = AudioRecord.Builder().setAudioSource(audioSource).setAudioFormat(audioFormat)
            .setBufferSizeInBytes(internalBufferSize).build()

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord failed to initialize.")
        }
        return RecorderInfo(recorder, readChunkSize)
    }

    private fun updateDurationToDisplay() {
        val sampleRate = config.sampleRateHz
        val bitsPerSample = config.bitDepth.bits
        if (sampleRate == 0 || bitsPerSample == 0) {
            recordedDuration.set("00:00:00")
            return
        }
        val totalRecordedBytes: Int = if (hasOverflowed.get()) {
            totalRingBufferSize.get()
        } else {
            recorderIndex.get()
        }
        val bytesPerSecond = sampleRate * (bitsPerSample / 8)
        val durationInSeconds = totalRecordedBytes.toDouble() / bytesPerSecond
        val flooredDuration = floor(durationInSeconds).toLong()
        val hours = TimeUnit.SECONDS.toHours(flooredDuration)
        val minutes = TimeUnit.SECONDS.toMinutes(flooredDuration) % 60
        val seconds = flooredDuration % 60
        recordedDuration.set(String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds))
    }

    override suspend fun pauseSortAndGetBuffer(): ByteBuffer {
        stopRecording(savePendingUtterances = true)
        recordingJob?.join()
        autoSaveJob?.join()
        notificationJob?.join()
        lock.lock()
        try {
            if (!::audioDataStorage.isInitialized || totalRingBufferSize.get() == 0) {
                return ByteBuffer.allocate(0)
            }
            if (recorderIndex.get() == 0 && !hasOverflowed.get()) {
                return ByteBuffer.allocate(0)
            }
            return if (hasOverflowed.get()) {
                val index = recorderIndex.get()
                // In-place rotation of the circular buffer array
                audioDataStorage.reverse(index, audioDataStorage.size - 1)
                audioDataStorage.reverse(0, index - 1)
                audioDataStorage.reverse(0, audioDataStorage.size - 1)
                recorderIndex.set(0) // After sorting, the logical start is 0
                hasOverflowed.set(false) // The buffer is now linear, not overflowed
                ByteBuffer.wrap(audioDataStorage).asReadOnlyBuffer()
            } else {
                ByteBuffer.wrap(audioDataStorage, 0, recorderIndex.get()).asReadOnlyBuffer()
            }
        } finally {
            lock.unlock()
        }
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
                    delay(200)

                    finalBytes
                })
            } else {
                originalBuffer
            }
            _trimmingProgress.value = -1f
            updateNotification()

            val tempFileUri = withContext(Dispatchers.IO) {
                FileSavingUtils.saveBufferToTempFile(this@MyBufferService, bufferToSave)
            }
            val destDirUri = FileSavingUtils.getCachedGrantedUri(this)

            if (tempFileUri != null && destDirUri != null) {
                val timestamp = SimpleDateFormat("yy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
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
                Toast.makeText(this, "Quick save failed. No save directory set.", Toast.LENGTH_LONG).show()
                onStartCommand(Intent(this, MyBufferService::class.java).apply { action = ACTION_ON_SAVE_FAIL }, 0, 0)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error during quick save process")
            Toast.makeText(this, "Error: Could not save file: $e", Toast.LENGTH_LONG).show()
            onStartCommand(Intent(this, MyBufferService::class.java).apply { action = ACTION_ON_SAVE_FAIL }, 0, 0)
        }
    }

    data class RecorderInfo(val recorder: AudioRecord, val readChunkSize: Int)

    @kotlin.OptIn(ObsoleteCoroutinesApi::class)
    private fun startNotificationUpdates() {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            val tickerChannel = ticker(delayMillis = 1000)
            while (isActive && _isRecording.value) {
                settingsRepository.updateLastActiveTimestamp(System.currentTimeMillis())
                updateDurationToDisplay()
                updateNotification()
                tickerChannel.receive()
            }
        }
    }

    fun clearServiceError() {
        _serviceError.value = null
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
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val canSave = recorderIndex.get() > 0 || hasOverflowed.get()
        val builder = NotificationCompat.Builder(this, CHRONIC_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Buffered Recent Audio")
            .setSmallIcon(R.drawable.baseline_record_voice_over_24)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
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
                        ((recorderIndex.get().toFloat() / totalRingBufferSize.get()) * 100).roundToInt()
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

    // Helper to reverse a portion of a byte array
    private fun ByteArray.reverse(from: Int, to: Int) {
        var i = from
        var j = to
        while (i < j) {
            val temp = this[i]
            this[i] = this[j]
            this[j] = temp
            i++
            j--
        }
    }
}
