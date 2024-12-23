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
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max
import kotlin.properties.Delegates

interface MyBufferServiceInterface {
    fun getBuffer(): ByteArray
    fun isRecording(): Boolean
    fun writeWavHeader(out: OutputStream, audioDataLen: Long)
    fun stopRecording()
    fun startRecording()
    fun resetBuffer()
    var recordingStateListener: RecordingStateListener?
}

interface RecordingStateListener {
    fun onRecordingStateChanged(isRecording: Boolean)
    fun onRecordingDurationChange(duration: String)
}

class MyBufferService : Service(), MyBufferServiceInterface {
    private val logTag = "MyBufferService"

    // Calculate the maximum total buffer size
    private lateinit var config: AudioConfig
    private var totalRingbufferSize by Delegates.notNull<Int>()
    private lateinit var buffer: ByteArray

    private var hasOverflowed = false
    private var recorder: AudioRecord? = null
    private var isRecording = false
    private var recorderIndex = 0
    private val lock = ReentrantLock()
    private val recordingStartedLatch = CountDownLatch(1)

    override var recordingStateListener: RecordingStateListener? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        recordingStateListener = ViewModelHolder.getSharedViewModel() // Set the listener
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
        totalRingbufferSize =
            config.SAMPLE_RATE_HZ * (config.BIT_DEPTH.bytes / 8) * config.BUFFER_TIME_LENGTH_S
        buffer = ByteArray(totalRingbufferSize)
    }

    private fun updateForegroundNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val notification =
            NotificationCompat.Builder(this, "running_notification_channel_id")
                .setContentTitle("Recording Recent Audio").setContentText(
                    "Buffering... ${if (hasOverflowed) "100%" else "${recorderIndex / totalRingbufferSize * 100}%"} - ${getLengthInTime()}"
                ).setSmallIcon(R.drawable.baseline_record_voice_over_24)
                .setProgress(  // Bar visualization
                    totalRingbufferSize, if (hasOverflowed) {
                        totalRingbufferSize
                    } else {
                        recorderIndex
                    }, false
                ).setContentIntent(pendingIntent).build()

        // Update the existing notification:
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(MainActivity.NOTIFICATION_ID, notification)
    }

    private fun prepareAndStartRecording(): Int {
        Log.i(logTag, "prepareAndStartRecording")

        this.syncPreferences()

        this.updateForegroundNotification()

        val micPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (micPermission == PackageManager.PERMISSION_DENIED) {
            // Without mic permissions the service cannot run in the foreground
            // Consider informing user or updating your app UI if visible.
            stopSelf()
            return STOP_FOREGROUND_DETACH
        }

        try {
            startBuffering()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        return START_NOT_STICKY
    }

    private fun startBuffering() {
        val audioFormat = AudioFormat.Builder().setEncoding(config.BIT_DEPTH.encodingEnum)
            .setSampleRate(config.SAMPLE_RATE_HZ).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minReadChunkSize = AudioRecord.getMinBufferSize(
            audioFormat.sampleRate, audioFormat.channelMask, audioFormat.encoding
        )

        if (minReadChunkSize <= 0) {
            Log.e(logTag, "Minimum buffer size <= 0 somehow")
            Toast.makeText(
                this, "ERROR: Minimum buffer size <= 0 somehow", Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }

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
            .setAudioFormat(audioFormat).setBufferSizeInBytes(totalRingbufferSize).build()

        try {
            isRecording = true
            recordingStateListener?.onRecordingStateChanged(true)
            Thread {

                recorder?.startRecording() // Start recording
                recordingStartedLatch.countDown()
                Log.d(logTag, "Recording thread started")
                while (isRecording) {
                    lock.lock()
                    try {
                        // Read in chunks of minReadChunkSize.
                        // If next chunk will cause overflow, continue recording from beginning and overriding
                        if (recorderIndex + minReadChunkSize >= totalRingbufferSize) {
                            recorderIndex = 0
                            hasOverflowed = true
                            Log.d(logTag, "Buffer overflowed. recorderIndex reset to 0")
                        }

                        val readResult =
                            recorder?.read(buffer, recorderIndex, minReadChunkSize) ?: -1
                        Log.d(
                            logTag,
                            "readResult: $readResult, recorderIndex before update: $recorderIndex"
                        ) // Log before update
                        if (readResult > 0) {
                            recorderIndex = (recorderIndex + readResult) % totalRingbufferSize
                            recordingStateListener?.onRecordingDurationChange(getLengthInTime())
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
                recordingStateListener?.onRecordingStateChanged(false)
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
        recordingStateListener?.onRecordingStateChanged(false)
    }

    private fun getLengthInTime(): String {
        val sampleRate = config.SAMPLE_RATE_HZ
        val bitDepth = config.BIT_DEPTH.bytes
        val channels = 1 // Recording is in mono

        val lengthIndex: Int = if (hasOverflowed) {
            totalRingbufferSize
        } else {
            recorderIndex
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

        return durationFormatted
    }

    override fun writeWavHeader(out: OutputStream, audioDataLen: Long) {
        val channels = 1.toShort() // Recording is in mono
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
        var shiftedBuffer: ByteArray? = null
        lock.lock()
        try {
            if (hasOverflowed) {
                // Return the entire buffer, shifted at recorderIndex
                shiftedBuffer = ByteArray(totalRingbufferSize)
                val bytesToEnd = totalRingbufferSize - recorderIndex
                System.arraycopy(buffer, recorderIndex, shiftedBuffer, 0, bytesToEnd)
                System.arraycopy(buffer, 0, shiftedBuffer, bytesToEnd, recorderIndex)
                return shiftedBuffer
            } else {
                // Return only the relevant portion up to recorderIndex
                shiftedBuffer = buffer.copyOf(recorderIndex)
            }
        } finally {
            lock.unlock()
        }

        return shiftedBuffer!!
    }

    private fun syncPreferences() {
        // Access DataStore using application context
        val dataStore = applicationContext.dataStore

        // Read from DataStore synchronously (blocking call)
        val preferences = runBlocking {
            dataStore.data.catch { exception ->
                // Log the error for debugging
                Log.e("syncPreferences", "Error reading settings", exception)
                // Emit empty preferences to avoid crashes
                emit(emptyPreferences())
            }.first() // Get the first (and only) value emitted by the flow
        }

        val sampleRate = preferences[SettingsRepository.SAMPLE_RATE] ?: 22050
        val bufferTimeLengthS = preferences[SettingsRepository.BUFFER_TIME_LENGTH_S] ?: 120

        val bitDepthString = preferences[SettingsRepository.BIT_DEPTH] ?: "8"
        val bitDepth = BitDepth.fromString(bitDepthString) ?: bitDepths["8"]!!
        val saveLocationOnSaveAudio = preferences[SettingsRepository.SAVE_LOCATION_ON_SAVE_AUDIO] ?: false

        // Initialize config directly
        config = AudioConfig(
            SAMPLE_RATE_HZ = sampleRate,
            BUFFER_TIME_LENGTH_S = bufferTimeLengthS,
            BIT_DEPTH = bitDepth,
            SAVE_LOCATION_ON_SAVE_AUDIO = saveLocationOnSaveAudio
        )
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

    override fun resetBuffer() {
        lock.lock()
        try {
            recorderIndex = 0
            recordingStateListener?.onRecordingDurationChange(getLengthInTime())
            hasOverflowed = false
            Arrays.fill(buffer, 0.toByte()) // Clear the buffer
            Log.i(logTag, "Buffer reset")
            Toast.makeText(
                this, "Buffer cleared!", Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(logTag, "ERROR: Failed to clear buffer $e")
            Toast.makeText(
                this, "ERROR: Failed to clear buffer $e", Toast.LENGTH_LONG
            ).show()
        } finally {
            lock.unlock()
        }
    }


    override fun startRecording() {
        if (!isRecording) {
            prepareAndStartRecording()
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
}
