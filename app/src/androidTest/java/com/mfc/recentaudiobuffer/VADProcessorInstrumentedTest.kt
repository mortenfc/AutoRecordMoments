package com.mfc.recentaudiobuffer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.rule.ServiceTestRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * This is an Instrumented Test, which runs on an Android device or emulator.
 * It uses the real Android Context, real assets (audio and model files), and the real ONNX Runtime library.
 *
 * Pre-requisites for running this test:
 * 1. This file must be in the `src/androidTest/java` directory.
 * 2. The ONNX model (`silero_vad.onnx`) must be in `src/main/assets`.
 * 3. The following test audio files must be in `src/androidTest/assets`:
 * - silence_5s.wav
 * - talking_24s.wav
 * - music_30s.wav
 * - mostly_silence_noise_5min.wav
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class VADProcessorInstrumentedTest {

    private lateinit var context: Context
    private lateinit var vadProcessor: VADProcessor

    // --- HELPER FUNCTIONS ---

    companion object {
        @BeforeClass
        @JvmStatic
        fun clearDownloadsBeforeAllTests() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            Timber.w("!!! CLEARING DEBUG FILES IN DOWNLOADS DIRECTORY !!!")
            val contentResolver = context.contentResolver

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Files.getContentUri("external")
            }

            // Query for files with names starting with "debug_"
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("debug_%")
            var deletedCount = 0

            try {
                contentResolver.query(
                    collection, arrayOf(MediaStore.MediaColumns._ID), selection, selectionArgs, null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id =
                            cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val fileUri = Uri.withAppendedPath(collection, id.toString())
                        if (contentResolver.delete(fileUri, null, null) > 0) {
                            deletedCount++
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error while clearing debug files.")
            }
            Timber.w("Deleted $deletedCount debug files from Downloads.")
        }
    }

    /**
     * Loads a test asset file, parses its WAV header, and returns the config and raw audio data.
     */
    private fun loadAudioAndConfig(fileName: String): Pair<AudioConfig, ByteArray> {
        val fullFileBytes = loadAudioFromTestAssets(fileName, skipHeader = false)
        val config = WavUtils.readWavHeader(fullFileBytes)
        val audioBytes = fullFileBytes.copyOfRange(WavUtils.WAV_HEADER_SIZE, fullFileBytes.size)
        return Pair(config, audioBytes)
    }

    /**
     * Helper function to load raw PCM data from a test asset file.
     */
    private fun loadAudioFromTestAssets(fileName: String, skipHeader: Boolean): ByteArray {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val inputStream: InputStream = testContext.assets.open(fileName)
        val byteStream = ByteArrayOutputStream()

        if (skipHeader) {
            val skipped = inputStream.skip(WavUtils.WAV_HEADER_SIZE.toLong())
            assertEquals("Should skip the full WAV header", WavUtils.WAV_HEADER_SIZE, skipped)
        }

        val buffer = ByteArray(1024)
        var len: Int
        while (inputStream.read(buffer).also { len = it } != -1) {
            byteStream.write(buffer, 0, len)
        }
        return byteStream.toByteArray()
    }

    /**
     * Generates a block of silence.
     */
    private fun generateSilence(durationMs: Int, config: AudioConfig): ByteArray {
        val numSamples = durationMs * config.sampleRateHz / 1000
        val bytesPerSample = config.bitDepth.bits / 8
        return ByteArray(numSamples * bytesPerSample) // Array of zeros
    }


    @get:Rule(order = 0)
    val uncaughtExceptionRule = UncaughtExceptionRule()

    @get:Rule(order = 1)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 2)
    val serviceRule = ServiceTestRule()

    @get:Rule(order = 3)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS
    )

    @Inject
    lateinit var settingsRepository: SettingsRepository

    // --- TEST SETUP ---
    @Before
    fun setUp() {
        hiltRule.inject()

        context = InstrumentationRegistry.getInstrumentation().targetContext
        vadProcessor = VADProcessor(context)
    }

    // --- TESTS ---
    @Test
    fun processStreamedAudioAndTriggerQuickSaveViaReceiver() {
        runBlocking {
            val appContext = ApplicationProvider.getApplicationContext<Context>()

            // --- SETUP SYNCHRONIZATION ---
            // 1. Create a latch that will block the test thread until countDown() is called.
            val saveCompletedDeferred = CompletableDeferred<Boolean>()
            // 2. Create a broadcast receiver that listens for our "save complete" signal.
            val saveCompleteReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == MyBufferService.ACTION_SAVE_COMPLETE) {
                        Timber.d("Test receiver got ACTION_SAVE_COMPLETE. Releasing latch.")
                        // When the signal is received, release the latch.
                        saveCompletedDeferred.complete(true)
                    }
                }
            }

            var service: MyBufferService? = null
            val testDir = File(appContext.cacheDir, "test-output")

            try {
                ContextCompat.registerReceiver(
                    appContext,
                    saveCompleteReceiver,
                    IntentFilter(MyBufferService.ACTION_SAVE_COMPLETE),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )

                // --- GIVEN ---
                if (testDir.exists()) {
                    testDir.deleteRecursively()
                }
                testDir.mkdirs()
                FileSavingUtils.cacheGrantedUri(appContext, testDir.toUri())

                val (audioConfig, speechChunk) = loadAudioAndConfig("talking_24s.wav")
                settingsRepository.updateSampleRate(audioConfig.sampleRateHz)
                settingsRepository.updateBitDepth(audioConfig.bitDepth)
                settingsRepository.updateBufferTimeLengthS(600)
                settingsRepository.updateIsAiAutoClipEnabled(true)

                val intent = Intent(appContext, MyBufferService::class.java)
                val binder = serviceRule.bindService(intent)
                service = (binder as MyBufferService.MyBinder).getService() as MyBufferService

                // --- WHEN ---
                service.prepareForTestRecording()
                delay(200)
                assertTrue(
                    "Service should be recording", service.isRecording.value
                )

// ✅ The simplest possible simulation:
// Write the speech chunk back-to-back 50 times.
// 50 repetitions * 24s/chunk = 1200s of audio.
// This is more than enough to fill and overflow the 1000-second buffer
// with continuous, uninterrupted speech.
                Timber.d("Writing continuous stream to buffer...")
                val repetitions = 100
                repeat(repetitions) {
                    service.writeDataToBufferForTest(speechChunk)
                    // 10% is silence
                    service.writeDataToBufferForTest(
                        generateSilence(
                            240 + (2 * VADProcessor.Companion.DEFAULT_PADDING_MS), audioConfig
                        )
                    )
                }
                Timber.d("Finished writing.")

                // Send the broadcast to start the save operation
                appContext.sendBroadcast(
                    Intent(
                        appContext, NotificationActionReceiver::class.java
                    ).apply {
                        action = NotificationActionReceiver.ACTION_SAVE_RECORDING
                    })

                // This will wait for the service to respond with its "complete" broadcast.
                try {
                    withTimeout(TimeUnit.MINUTES.toMillis(2)) {
                        saveCompletedDeferred.await()
                    }
                } catch (e: Exception) {
                    fail("VAD and save operation did not complete within the timeout.")
                }

                // --- THEN ---
                // Instead of checking the buffer content, check a state that is reset by resetBuffer()
                // and isn't immediately changed by the new recording job.
                val hasOverflowedAfterReset = service.hasOverflowed.get()
                assertEquals(
                    "The hasOverflowed flag should be reset to false after a quicksave",
                    false,
                    hasOverflowedAfterReset
                )

                Timber.d("Verifying duration of saved file...")
                val savedFiles = testDir.listFiles { _, name -> name.startsWith("quicksave_") }
                assertTrue(
                    "A saved file should exist in the test directory", !savedFiles.isNullOrEmpty()
                )

                val savedFile = savedFiles!!.first()
                val fileBytes = savedFile.readBytes()
                val wavConfig = WavUtils.readWavHeader(fileBytes)
                val audioDataSize = fileBytes.size - WavUtils.WAV_HEADER_SIZE

// Calculate duration in seconds
                val bytesPerSecond = wavConfig.sampleRateHz * (wavConfig.bitDepth.bits / 8)
                val durationInSeconds = audioDataSize.toDouble() / bytesPerSecond

                Timber.d("Saved file duration: $durationInSeconds seconds.")

// Assert that the duration is within 1000 ± 5 seconds
                val bufferS = settingsRepository.getAudioConfig().bufferTimeLengthS
                val expectedDurationRange = bufferS * 0.85..bufferS * 0.9
                assertTrue(
                    "The duration of the saved file ($durationInSeconds s) was not within the expected range of $expectedDurationRange s",
                    durationInSeconds in expectedDurationRange
                )

            } finally {
                // --- CLEANUP ---
                appContext.unregisterReceiver(saveCompleteReceiver)
                service?.stopRecording()
                service?.resetBuffer()
                FileSavingUtils.clearCachedUri(appContext)
                if (testDir.exists()) {
                    testDir.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun processBuffer_withSilenceFile_returnsEmptyOrVerySmallBuffer() {
        // Given
        val (config, audioBytes) = loadAudioAndConfig("silence_5s.wav")
        assertEquals("Expected sample rate for silence file", 22050, config.sampleRateHz)
        assertEquals(
            "Expected bit depth for silence file",
            BitDepth(16, AudioFormat.ENCODING_PCM_16BIT),
            config.bitDepth
        )

        // When
        val result = vadProcessor.process(
            ByteBuffer.wrap(audioBytes), config, debugFileBaseName = "silence_5s"
        )

        // Then
        val maxTolerableBytes =
            config.sampleRateHz / 10 * (config.bitDepth.bits / 8) // 100ms tolerance
        assertTrue(
            "Buffer from silence file should be nearly empty.", result.size < maxTolerableBytes
        )
    }

    @Test
    fun processBuffer_withSpeechFile44100Hz_resample_returnsSignificantNonEmptyBuffer() {
        // Given
        val (config, audioBytes) = loadAudioAndConfig("talking_24s.wav")
        assertEquals("Expected sample rate for speech file", 44100, config.sampleRateHz)
        assertEquals(
            "Expected bit depth for speech file",
            BitDepth(16, AudioFormat.ENCODING_PCM_16BIT),
            config.bitDepth
        )
        assertTrue("Test setup failed: Speech audio file is empty.", audioBytes.isNotEmpty())

        // When

        val result = vadProcessor.process(
            ByteBuffer.wrap(audioBytes), config, paddingMs = 0, debugFileBaseName = "talking_24s"
        )
        // Then
        assertTrue(
            "Resulting buffer should not be empty for a speech file.", result.isNotEmpty()
        )
        assertTrue(
            "Resulting buffer should be smaller than the original.", result.size < audioBytes.size
        )
        assertTrue(
            "Resulting buffer should be above 20s long", result.size > 20 * 16000 * 2
        )
    }

    @Test
    fun processBuffer_withMusicFile_returnsVerySmallBuffer() {
        // Given
        val (config, audioBytes) = loadAudioAndConfig("music_30s.wav")
        assertEquals("Expected sample rate for music file", 22050, config.sampleRateHz)
        assertEquals(
            "Expected bit depth for music file",
            BitDepth(16, AudioFormat.ENCODING_PCM_16BIT),
            config.bitDepth
        )

        // When
        val result = vadProcessor.process(
            ByteBuffer.wrap(audioBytes), config, debugFileBaseName = "music_30s"
        )

        // Then
        val maxTolerableBytes = audioBytes.size / 4
        assertTrue(
            "Music file should result in very little detected speech. Found ${result.size} bytes.",
            result.size < maxTolerableBytes
        )
    }

    @Test
    fun processBuffer_withNoisySilenceFile_returnsSmallBuffer() {
        // Given
        val (config, audioBytes) = loadAudioAndConfig("mostly_silence_noise_5min.wav")
        assertEquals("Expected sample rate for noisy file", 22050, config.sampleRateHz)
        assertEquals(
            "Expected bit depth for noisy file",
            BitDepth(16, AudioFormat.ENCODING_PCM_16BIT),
            config.bitDepth
        )

        // When
        val result = vadProcessor.process(
            ByteBuffer.wrap(audioBytes), config, debugFileBaseName = "mostly_silence_noise_5min"
        )

        // Then
        val maxTolerableBytes = audioBytes.size / 10 // Expect less than 10%
        assertTrue(
            "Noisy silence file should result in little detected speech. Found ${result.size} bytes.",
            result.size < maxTolerableBytes
        )
    }

    @Test
    fun processBuffer_withBufferSmallerThanWindowSize_doesNotCrash() {
        // Given
        val config = AudioConfig(16000, 0, BitDepth(16, AudioFormat.ENCODING_PCM_16BIT))
        val tinyBuffer = ByteArray(500 * 2) // 500 samples, 16-bit

        // When
        val result = vadProcessor.process(ByteBuffer.wrap(tinyBuffer), config)

        // Then
        assertEquals("Result for a tiny buffer should be empty", 0, result.size)
    }
}
