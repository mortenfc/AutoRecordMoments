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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.rule.ServiceTestRule
import com.mfc.recentaudiobuffer.bitDepths
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Comprehensive instrumented tests for VADProcessor including edge cases.
 *
 * Pre-requisites:
 *  - silero_vad.onnx in src/main/assets
 *  - test audio files in src/androidTest/assets:
 *    silence_5s.wav, talking_24s.wav, music_30s.wav, mostly_silence_noise_5min.wav
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class VADProcessorInstrumentedTest {

    companion object {
        // Constants for clarity
        private const val VAD_TARGET_SAMPLE_RATE = 16000
        private const val BYTES_PER_SAMPLE_16BIT = 2
        private const val BYTES_PER_SAMPLE_8BIT = 1
        private const val MAX_PROCESSING_TIME_MS = 10000

        // Test asset files
        private val REQUIRED_TEST_ASSETS = listOf(
            "silence_5s.wav", "talking_24s.wav", "music_30s.wav", "mostly_silence_noise_5min.wav"
        )

        private var clearedOnce = false
    }

    private fun clearDebugFiles() {
        // Clear from app's external files
        val debugDir = File(context.getExternalFilesDir(null), "debug")

        if (debugDir.exists()) {
            debugDir.deleteRecursively()
            Timber.d("Cleared debug directory: ${debugDir.absolutePath}")
        }
    }

    @After
    fun tearDown() {
        try {
            vadProcessor.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing VADProcessor in tearDown")
        }
    }

    private lateinit var context: Context
    private lateinit var vadProcessor: VADProcessor

    @get:Rule(order = 0)
    val uncaughtExceptionRule = UncaughtExceptionRule()

    @get:Rule(order = 1)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 2)
    val serviceRule = ServiceTestRule()

    @get:Rule(order = 3)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.POST_NOTIFICATIONS,
    )

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        vadProcessor = VADProcessor(context)
        verifyTestAssets()

        if (!clearedOnce) {
            clearDebugFiles()
            clearedOnce = true
        }
    }

    // --- HELPER FUNCTIONS ---

    private fun verifyTestAssets() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        REQUIRED_TEST_ASSETS.forEach { fileName ->
            try {
                testContext.assets.open(fileName).use {
                    // Just opening and closing to verify existence
                }
            } catch (e: Exception) {
                fail("Required test asset not found: $fileName. Ensure all test WAV files are in src/androidTest/assets/")
            }
        }
    }

    private fun loadAudioAndConfig(fileName: String): Pair<AudioConfig, ByteArray> {
        val fullFileBytes = loadAudioFromTestAssets(fileName, skipHeader = false)
        val config = WavUtils.readWavHeader(fullFileBytes)
        val audioBytes = fullFileBytes.copyOfRange(WavUtils.WAV_HEADER_SIZE, fullFileBytes.size)
        return Pair(config, audioBytes)
    }

    private fun loadAudioFromTestAssets(fileName: String, skipHeader: Boolean): ByteArray {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        return testContext.assets.open(fileName).use { inputStream ->
            val byteStream = ByteArrayOutputStream()

            if (skipHeader) {
                val skipped = inputStream.skip(WavUtils.WAV_HEADER_SIZE.toLong())
                assertEquals(
                    "Should skip the full WAV header", WavUtils.WAV_HEADER_SIZE.toLong(), skipped
                )
            }

            val buffer = ByteArray(4096)
            var len: Int
            while (inputStream.read(buffer).also { len = it } != -1) {
                byteStream.write(buffer, 0, len)
            }
            byteStream.toByteArray()
        }
    }

    private fun generateSilence(durationMs: Int, config: AudioConfig): ByteArray {
        val numSamples = durationMs * config.sampleRateHz / 1000
        val bytesPerSample = config.bitDepth.bits / 8
        return ByteArray(numSamples * bytesPerSample) // zeros
    }

    private fun generateSineWave(
        frequencyHz: Double, durationMs: Int, config: AudioConfig, amplitude: Double = 0.5
    ): ByteArray {
        val numSamples = durationMs * config.sampleRateHz / 1000
        val byteArray = ByteArrayOutputStream()

        for (i in 0 until numSamples) {
            val value =
                amplitude * kotlin.math.sin(2.0 * Math.PI * frequencyHz * i / config.sampleRateHz)
            when (config.bitDepth.bits) {
                16 -> {
                    val sample = (value * 32767).toInt().toShort()
                    byteArray.write(sample.toInt() and 0xFF)
                    byteArray.write((sample.toInt() shr 8) and 0xFF)
                }

                8 -> {
                    val sample = ((value + 1.0) * 127).toInt().toByte()
                    byteArray.write(sample.toInt())
                }

                else -> throw IllegalArgumentException("Unsupported bit depth: ${config.bitDepth.bits}")
            }
        }
        return byteArray.toByteArray()
    }

    /**
     * Make a simple 16-bit PCM little-endian mono buffer:
     * 2s at 16kHz with a 200ms sine burst at 1s.
     */
    private fun makeTestBuffer16bit(sampleRate: Int = 16000): ByteBuffer {
        val durationSec = 2.0
        val totalSamples = (durationSec * sampleRate).toInt()
        val burstStartSec = 1.0
        val burstMs = 200
        val burstStartSample = (burstStartSec * sampleRate).toInt()
        val burstEndSample = burstStartSample + (burstMs * sampleRate / 1000)

        val amplitude = 0.6 // scale
        val freqHz = 440.0
        val out = ByteArray(totalSamples * 2) // 16-bit little-endian

        var idx = 0
        for (s in 0 until totalSamples) {
            val sampleVal = if (s in burstStartSample until burstEndSample) {
                val t = s.toDouble() / sampleRate.toDouble()
                (amplitude * Short.MAX_VALUE * sin(2.0 * PI * freqHz * t)).toInt()
            } else {
                0
            }
            // little-endian
            out[idx++] = (sampleVal and 0xFF).toByte()
            out[idx++] = ((sampleVal shr 8) and 0xFF).toByte()
        }
        return ByteBuffer.wrap(out)
    }

    private fun generateWhiteNoise(
        durationMs: Int, config: AudioConfig, amplitude: Double = 0.3
    ): ByteArray {
        val numSamples = durationMs * config.sampleRateHz / 1000
        val byteArray = ByteArrayOutputStream()
        val random = Random(42) // Fixed seed for reproducibility

        for (i in 0 until numSamples) {
            val value = (random.nextDouble() * 2.0 - 1.0) * amplitude
            when (config.bitDepth.bits) {
                16 -> {
                    val sample = (value * 32767).toInt().toShort()
                    byteArray.write(sample.toInt() and 0xFF)
                    byteArray.write((sample.toInt() shr 8) and 0xFF)
                }

                8 -> {
                    val sample = ((value + 1.0) * 127).toInt().toByte()
                    byteArray.write(sample.toInt())
                }

                else -> throw IllegalArgumentException("Unsupported bit depth: ${config.bitDepth.bits}")
            }
        }
        return byteArray.toByteArray()
    }

    //// Original tests

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
            // Use app's external files directory for tests - same as debug files, but different subdir
            val testDir = File(appContext.getExternalFilesDir(null), "test_recordings")
            try {
                // Ensure directory is created

                if (testDir.exists()) {
                    testDir.deleteRecursively()
                    Timber.d("Cleaned up test directory: ${testDir.absolutePath}")
                }

                val created = testDir.mkdirs()
                Timber.d("Test directory created: $created at ${testDir.absolutePath}")

                // Verify it exists before caching
                if (!testDir.exists() || !testDir.isDirectory) {
                    fail("Failed to create test directory at: ${testDir.absolutePath}")
                }

                // Double-check the directory is accessible
                val testFile = File(testDir, "test.txt")
                testFile.writeText("test")
                if (!testFile.exists()) {
                    fail("Cannot write to test directory: ${testDir.absolutePath}")
                }
                testFile.delete()

                FileSavingUtils.cacheGrantedUri(appContext, testDir.toUri())
                Timber.d("Cached URI: ${testDir.toUri()}")

                ContextCompat.registerReceiver(
                    appContext,
                    saveCompleteReceiver,
                    IntentFilter(MyBufferService.ACTION_SAVE_COMPLETE),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )

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

                // The simplest possible simulation:
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

                // Assert that the duration is within a reasonable range
                val bufferS = settingsRepository.getAudioConfig().bufferTimeLengthS
                val expectedDurationRange = bufferS * 0.7..bufferS * 0.9
                assertTrue(
                    "The duration of the saved file ($durationInSeconds s) was not within the expected range of $expectedDurationRange s",
                    durationInSeconds in expectedDurationRange
                )
                //  BREAKPOINT HERE TO CHECK THE SAVED FILE
            } finally {
                // --- CLEANUP ---
                appContext.unregisterReceiver(saveCompleteReceiver)
                service?.stopRecording()
                service?.resetBuffer()
                FileSavingUtils.clearCachedUri(appContext)
            }
        }
    }

    @Test
    fun processBuffer_withSilenceFile_returnsEmptyOrVerySmallBuffer() {
        val (config, audioBytes) = loadAudioAndConfig("silence_5s.wav")
        assertEquals("Expected sample rate for silence file", 22050, config.sampleRateHz)
        assertEquals(
            "Expected bit depth for silence file",
            BitDepth(16, AudioFormat.ENCODING_PCM_16BIT),
            config.bitDepth
        )

        val result = vadProcessor.process(
            ByteBuffer.wrap(audioBytes), config, debugFileBaseName = "debug_silence_5s"
        )

        val maxTolerableBytes =
            config.sampleRateHz / 10 * (config.bitDepth.bits / 8) // 100ms tolerance
        assertTrue(
            "Buffer from silence file should be nearly empty. Found ${result.size} bytes.",
            result.size < maxTolerableBytes
        )
    }

    @Test
    fun processBuffer_withSpeechFile44100Hz_resample_returnsSignificantNonEmptyBuffer() {
        val (config, audioBytes) = loadAudioAndConfig("talking_24s.wav")
        assertEquals("Expected sample rate for speech file", 44100, config.sampleRateHz)
        assertEquals(
            "Expected bit depth for speech file",
            BitDepth(16, AudioFormat.ENCODING_PCM_16BIT),
            config.bitDepth
        )
        assertTrue("Test setup failed: Speech audio file is empty.", audioBytes.isNotEmpty())

        val result = vadProcessor.process(
            ByteBuffer.wrap(audioBytes),
            config,
            paddingMs = 0,
            debugFileBaseName = "debug_talking_24s"
        )

        assertTrue(
            "Resulting buffer should not be empty for a speech file.", result.isNotEmpty()
        )
        assertTrue(
            "Resulting buffer should be smaller than the original.", result.size < audioBytes.size
        )

        val minExpectedDurationSeconds = 20
        val minExpectedBytes =
            minExpectedDurationSeconds * VAD_TARGET_SAMPLE_RATE * BYTES_PER_SAMPLE_16BIT
        assertTrue(
            "Resulting buffer should be above ${minExpectedDurationSeconds}s long (${minExpectedBytes} bytes), but was ${result.size} bytes",
            result.size > minExpectedBytes
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

    //// Newer tests
    @Test
    fun parallel_and_nonParallel_produce_same_output() {
        // Arrange
        val sampleRate = 16000
        val buffer = makeTestBuffer16bit(sampleRate)
        val audioConfig = AudioConfig(sampleRateHz = sampleRate, bitDepth = bitDepths["16"]!!)

        // Act
        val processor = VADProcessor(ApplicationProvider.getApplicationContext())
        // Use parallel run
        val outParallel = processor.process(
            buffer.duplicate().asReadOnlyBuffer(),
            audioConfig,
            useParallel = true
        )
        // Non-parallel run
        val outNonParallel = processor.process(
            buffer.duplicate().asReadOnlyBuffer(),
            audioConfig,
            useParallel = false
        )

        // Assert
        assertArrayEquals(
            "Parallel and non-parallel outputs should match",
            outParallel,
            outNonParallel
        )

        processor.close()
    }

    @Test
    fun processBuffer_withEmptyBuffer_returnsEmpty() {
        val config = AudioConfig(16000, 0, BitDepth(16, AudioFormat.ENCODING_PCM_16BIT))
        val emptyBuffer = ByteArray(0)

        val result = try {
            vadProcessor.process(ByteBuffer.wrap(emptyBuffer), config)
        } catch (e: Exception) {
            fail("VAD processing threw an exception for empty buffer: ${e.message}")
            ByteArray(0)
        }

        assertEquals("Result for empty buffer should be empty", 0, result.size)
    }

    @Test
    fun processBuffer_withSingleSample_doesNotCrash() {
        val config = AudioConfig(16000, 0, BitDepth(16, AudioFormat.ENCODING_PCM_16BIT))
        val singleSample = ByteArray(2) // One 16-bit sample

        val result = try {
            vadProcessor.process(ByteBuffer.wrap(singleSample), config)
        } catch (e: Exception) {
            fail("VAD processing threw an exception for single sample: ${e.message}")
            ByteArray(0)
        }

        assertEquals("Result for single sample should be empty", 0, result.size)
    }

    @Test
    fun processBuffer_withExactWindowSize_processes() {
        val config = AudioConfig(16000, 0, BitDepth(16, AudioFormat.ENCODING_PCM_16BIT))
        // Window size for 16kHz is 512 samples, context is 64
        val windowSize = 512
        val contextSize = 64
        val totalSamples = windowSize + contextSize
        val buffer = generateSineWave(440.0, totalSamples * 1000 / 16000, config)

        val result = try {
            vadProcessor.process(ByteBuffer.wrap(buffer), config)
        } catch (e: Exception) {
            fail("VAD processing threw an exception for exact window size: ${e.message}")
            ByteArray(0)
        }

        assertNotNull("Should process buffer of exact window size", result)
    }

    @Test
    fun processBuffer_withVeryLowSampleRate_handlesCorrectly() {
        // Test with sample rate below VAD_MIN_SAMPLE_RATE (8000Hz)
        val config = AudioConfig(4000, 0, BitDepth(16, AudioFormat.ENCODING_PCM_16BIT))
        val audio = generateSineWave(440.0, 1000, config)

        val result = try {
            vadProcessor.process(ByteBuffer.wrap(audio), config)
        } catch (e: Exception) {
            fail("VAD processing threw an exception for 4kHz sample rate: ${e.message}")
            ByteArray(0)
        }

        assertNotNull("Should handle 4kHz sample rate", result)
    }

    @Test
    fun processBuffer_withVeryHighSampleRate_downsamples() {
        // Test with very high sample rate (96kHz)
        val config = AudioConfig(96000, 0, BitDepth(16, AudioFormat.ENCODING_PCM_16BIT))
        val audio = generateSineWave(440.0, 1000, config)

        val result = try {
            vadProcessor.process(ByteBuffer.wrap(audio), config)
        } catch (e: Exception) {
            fail("VAD processing threw an exception for 96kHz sample rate: ${e.message}")
            ByteArray(0)
        }

        assertNotNull("Should handle 96kHz sample rate", result)
        assertTrue("Should downsample 96kHz audio", result.size < audio.size)
    }

    @Test
    fun processBuffer_with8BitAudio_processes() {
        val config = AudioConfig(16000, 0, BitDepth(8, AudioFormat.ENCODING_PCM_8BIT))
        val audio = generateSineWave(440.0, 2000, config)

        val result = try {
            vadProcessor.process(ByteBuffer.wrap(audio), config)
        } catch (e: Exception) {
            fail("VAD processing threw an exception for 8-bit audio: ${e.message}")
            ByteArray(0)
        }

        assertNotNull("Should handle 8-bit audio", result)
    }

    @Test
    fun processBuffer_withAlternatingShortSpeechAndSilence_mergesCorrectly() {
        val config = AudioConfig(16000, 0, BitDepth(16, AudioFormat.ENCODING_PCM_16BIT))
        val byteStream = ByteArrayOutputStream()

        // Create pattern: 100ms speech, 50ms silence, repeated
        repeat(10) {
            byteStream.write(generateSineWave(200.0, 100, config, amplitude = 0.8))
            byteStream.write(generateSilence(50, config))
        }

        val audio = byteStream.toByteArray()
        val result = vadProcessor.process(ByteBuffer.wrap(audio), config, paddingMs = 100)

        assertNotNull("Should process alternating pattern", result)
        // With padding and merging, segments should be combined
        assertTrue("Should have some output for alternating pattern", result.size > 0)
    }

    @Test
    fun processBuffer_withProgressCallback_reportsMonotonicallyIncreasing() {
        val (config, audioBytes) = loadAudioAndConfig("talking_24s.wav")
        val progressValues = mutableListOf<Float>()

        try {
            vadProcessor.process(
                ByteBuffer.wrap(audioBytes),
                config,
                onProgress = { progress -> progressValues.add(progress) })
        } catch (e: Exception) {
            fail("VAD processing threw an exception: ${e.message}")
        }

        assertTrue("Should report progress at least once", progressValues.isNotEmpty())
        assertTrue("Progress should start at or near 0", progressValues.first() <= 0.1f)
        assertTrue("Progress should end at or near 1", progressValues.last() >= 0.9f)

        for (i in 1 until progressValues.size) {
            assertTrue(
                "Progress should be monotonically increasing (${progressValues[i - 1]} -> ${progressValues[i]})",
                progressValues[i] >= progressValues[i - 1]
            )
        }
    }

    @Test
    fun processBuffer_withWhiteNoise_filtersOutMostNoise() {
        val config = AudioConfig(16000, 0, BitDepth(16, AudioFormat.ENCODING_PCM_16BIT))
        val noise = generateWhiteNoise(5000, config)

        val result = vadProcessor.process(
            ByteBuffer.wrap(noise), config, debugFileBaseName = "debug_white_noise"
        )

        // White noise should not be detected as speech
        val maxTolerablePercent = 0.2 // Allow up to 20% to be detected as speech
        val maxTolerableBytes = (noise.size * maxTolerablePercent).toInt()
        assertTrue(
            "White noise should result in minimal detected speech. Found ${result.size} bytes (max: $maxTolerableBytes)",
            result.size < maxTolerableBytes
        )
    }

    @Test
    fun processBuffer_withClippedAudio_handlesGracefully() {
        val config = AudioConfig(16000, 0, BitDepth(16, AudioFormat.ENCODING_PCM_16BIT))
        val byteStream = ByteArrayOutputStream()

        // Generate clipped audio (max amplitude sine wave)
        for (i in 0 until 16000) { // 1 second
            val value = kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16000.0)
            val sample = if (value > 0) 32767 else -32768 // Clip to max values
            byteStream.write(sample and 0xFF)
            byteStream.write((sample shr 8) and 0xFF)
        }

        val clippedAudio = byteStream.toByteArray()
        val result = try {
            vadProcessor.process(ByteBuffer.wrap(clippedAudio), config)
        } catch (e: Exception) {
            fail("VAD processing threw an exception for clipped audio: ${e.message}")
            ByteArray(0)
        }

        assertNotNull("Should handle clipped audio without crashing", result)
    }

    @Test
    fun processBuffer_withDifferentPaddingValues_adjustsOutput() {
        val (config, audioBytes) = loadAudioAndConfig("talking_24s.wav")

        val resultNoPadding = vadProcessor.process(
            ByteBuffer.wrap(audioBytes), config, paddingMs = 0
        )

        val resultWithPadding = vadProcessor.process(
            ByteBuffer.wrap(audioBytes), config, paddingMs = 500
        )

        assertTrue(
            "Result with padding should be larger than without",
            resultWithPadding.size > resultNoPadding.size
        )
    }

    @Test
    fun processBuffer_withReadOnlyByteBuffer_processes() {
        val (config, audioBytes) = loadAudioAndConfig("talking_24s.wav")
        val readOnlyBuffer = ByteBuffer.wrap(audioBytes).asReadOnlyBuffer()

        val result = try {
            vadProcessor.process(readOnlyBuffer, config)
        } catch (e: Exception) {
            fail("VAD processing threw an exception for read-only buffer: ${e.message}")
            ByteArray(0)
        }

        assertNotNull("Should process read-only ByteBuffer", result)
        assertTrue("Should return non-empty result for speech", result.isNotEmpty())
    }

    @Test
    fun processBuffer_withDirectByteBuffer_processes() {
        val (config, audioBytes) = loadAudioAndConfig("talking_24s.wav")
        val directBuffer = ByteBuffer.allocateDirect(audioBytes.size)
        directBuffer.put(audioBytes)
        directBuffer.rewind()

        val result = try {
            vadProcessor.process(directBuffer, config)
        } catch (e: Exception) {
            fail("VAD processing threw an exception for direct buffer: ${e.message}")
            ByteArray(0)
        }

        assertNotNull("Should process direct ByteBuffer", result)
        assertTrue("Should return non-empty result for speech", result.isNotEmpty())
    }

    @Test
    fun processBuffer_benchmark_completesInReasonableTime() {
        val (config, audioBytes) = loadAudioAndConfig("talking_24s.wav")

        val benchmark = try {
            vadProcessor.measureProcessingMsForBuffer(
                ByteBuffer.wrap(audioBytes), config, runs = 3, warmup = 1
            )
        } catch (e: Exception) {
            fail("Benchmark failed: ${e.message}")
            VADProcessor.BenchmarkResult(0.0, 0L)
        }

        Timber.i("VAD benchmark: avgMs=${benchmark.avgMs}, avgAllocBytes=${benchmark.avgAllocBytes}")

        assertTrue(
            "Processing should complete in reasonable time (${benchmark.avgMs}ms > ${MAX_PROCESSING_TIME_MS}ms)",
            benchmark.avgMs < MAX_PROCESSING_TIME_MS
        )

        // Check that allocations are reasonable (less than 10MB for 24s audio)
        val maxAllocBytes = 10 * 1024 * 1024 // 10MB
        assertTrue(
            "Allocations should be reasonable (${benchmark.avgAllocBytes} bytes > $maxAllocBytes bytes)",
            benchmark.avgAllocBytes < maxAllocBytes
        )
    }

    @Test
    fun processBuffer_withOddNumberOfBytes_handlesGracefully() {
        val config = AudioConfig(16000, 0, BitDepth(16, AudioFormat.ENCODING_PCM_16BIT))
        // Create buffer with odd number of bytes (not aligned to 16-bit samples)
        val oddBuffer = ByteArray(1001)

        val result = try {
            vadProcessor.process(ByteBuffer.wrap(oddBuffer), config)
        } catch (e: Exception) {
            fail("VAD processing threw an exception for odd byte count: ${e.message}")
            ByteArray(0)
        }

        assertNotNull("Should handle odd byte count gracefully", result)
    }

    @Test
    fun processBuffer_multipleCallsWithSameProcessor_maintainsConsistency() {
        val (config, audioBytes) = loadAudioAndConfig("talking_24s.wav")

        // Process same audio twice
        val result1 = vadProcessor.process(ByteBuffer.wrap(audioBytes), config)
        val result2 = vadProcessor.process(ByteBuffer.wrap(audioBytes), config)

        // Results should be identical since state is reset
        assertArrayEquals(
            "Multiple calls with same input should produce same output", result1, result2
        )
    }

    @Test
    fun processBuffer_withNegativePadding() {
        val (config, audioBytes) = loadAudioAndConfig("talking_24s.wav")

        try {
            vadProcessor.process(
                ByteBuffer.wrap(audioBytes), config, paddingMs = -100 // Invalid negative padding
            )
            fail("Should not accept negative padding.")
        } catch (e: Exception) {
            Timber.d("Test passed because it threw error: $e")
        }
    }

    @Test
    fun processBuffer_withExtremelyLargePadding_doesNotOverflow() {
        val config = AudioConfig(16000, 0, BitDepth(16, AudioFormat.ENCODING_PCM_16BIT))
        val audio = generateSineWave(440.0, 1000, config)

        val result = try {
            vadProcessor.process(
                ByteBuffer.wrap(audio), config, paddingMs = Int.MAX_VALUE // Extreme padding
            )
        } catch (e: Exception) {
            fail("Should handle extreme padding without overflow: ${e.message}")
            ByteArray(0)
        }

        assertNotNull("Should handle extreme padding", result)
        // Result should not be larger than original (padding is clamped)
        assertTrue(
            "Result should not exceed original size with clamped padding", result.size <= audio.size
        )
    }
}