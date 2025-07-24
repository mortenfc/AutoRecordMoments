package com.mfc.recentaudiobuffer

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.mfc.recentaudiobuffer.VADProcessor.Companion.readWavHeader
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.InputStream
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

    // Standard WAV header size is 44 bytes.
    private val WAV_HEADER_SIZE = 44L

    // --- HELPER FUNCTIONS ---

    companion object {
        @BeforeClass
        @JvmStatic
        fun clearDownloadsBeforeAllTests() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            Timber.plant(Timber.DebugTree())
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
        val config = readWavHeader(fullFileBytes)
        val audioBytes = fullFileBytes.copyOfRange(WAV_HEADER_SIZE.toInt(), fullFileBytes.size)
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
            val skipped = inputStream.skip(WAV_HEADER_SIZE)
            assertEquals("Should skip the full WAV header", WAV_HEADER_SIZE, skipped)
        }

        val buffer = ByteArray(1024)
        var len: Int
        while (inputStream.read(buffer).also { len = it } != -1) {
            byteStream.write(buffer, 0, len)
        }
        return byteStream.toByteArray()
    }

    /**
     * Loads the real speech sample from the test assets.
     */
    private fun loadSpeechSample(): ByteArray {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val inputStream = testContext.assets.open("talking_24s.wav")
        // Skip the 44-byte WAV header to get raw PCM data
        inputStream.skip(44L)
        return inputStream.readBytes()
    }

    /**
     * Generates a block of silence.
     */
    private fun generateSilence(durationMs: Int, config: AudioConfig): ByteArray {
        val numSamples = durationMs * config.sampleRateHz / 1000
        val bytesPerSample = config.bitDepth.bits / 8
        return ByteArray(numSamples * bytesPerSample) // Array of zeros
    }

    /**
     * Generates a sine wave to simulate speech.
     */
    private fun generateSineWave(durationMs: Int, freq: Double, config: AudioConfig): ByteArray {
        val sampleRate = config.sampleRateHz
        val numSamples = durationMs * sampleRate / 1000
        val bytesPerSample = config.bitDepth.bits / 8
        val output = ByteArray(numSamples * bytesPerSample)

        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * i.toDouble() / (sampleRate / freq)
            val sample = (Math.sin(angle) * 32767.0).toInt() // For 16-bit audio
            output[i * 2] = (sample and 0xff).toByte()
            output[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
        }
        return output
    }

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val serviceRule = ServiceTestRule()

    @Inject
    lateinit var settingsRepository: SettingsRepository

    // --- TEST SETUP ---
    @Before
    fun setUp() {
        hiltRule.inject()
        // This ensures Timber is always active for your tests.
        Timber.plant(Timber.DebugTree())

        context = InstrumentationRegistry.getInstrumentation().targetContext
        vadProcessor = VADProcessor(context)
    }

    // --- TESTS ---
    // In: VADProcessorInstrumentedTest.kt
// In: VADProcessorInstrumentedTest.kt

    @Test
    fun processStreamedAudioAndTriggerQuickSaveViaReceiver() = runTest {
        // --- GIVEN ---
        val appContext = ApplicationProvider.getApplicationContext<Context>()

        // We cache a non-null URI. Our fake FileSavingUtils will approve it.
        FileSavingUtils.cacheGrantedUri(appContext, Uri.parse("content://fake-directory"))

        val sampleRate = 16000
        val audioConfig = AudioConfig(sampleRate, 0, BitDepth(16, AudioFormat.ENCODING_PCM_16BIT))
        settingsRepository.updateSampleRate(audioConfig.sampleRateHz)
        settingsRepository.updateBitDepth(audioConfig.bitDepth)
        settingsRepository.updateBufferTimeLength(1200)
        settingsRepository.updateIsAiAutoClipEnabled(true)

        val intent = Intent(appContext, MyBufferService::class.java)
        val binder = serviceRule.bindService(intent)
        val service = (binder as MyBufferService.MyBinder).getService()

        try {
            // --- WHEN ---
            service.startRecording()
            delay(200)
            assertTrue("Service should be recording", service.isRecording.value)

            val speechChunk = loadSpeechSample()
            val totalDurationSeconds = 1000
            val chunkDurationMs = 1000

            for (timeMs in 0 until totalDurationSeconds * 1000 step chunkDurationMs) {
                if ((timeMs / 1000) % 15 == 0) { // Inject speech every 15 seconds
                    (service as MyBufferService).writeDataToBufferForTest(speechChunk)
                } else {
                    val silenceChunk = generateSilence(chunkDurationMs, audioConfig)
                    (service as MyBufferService).writeDataToBufferForTest(silenceChunk)
                }
                delay(1) // Yield to prevent the test from becoming unresponsive
            }

            assertTrue(
                "Buffer should contain data after long stream", service.getBuffer().isNotEmpty()
            )

            val saveIntent = Intent(appContext, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_SAVE_RECORDING
            }
            appContext.sendBroadcast(saveIntent)

            delay(3000)

            // --- THEN ---
            val bufferAfterSave = service.getBuffer()
            assertEquals(
                "Buffer should be empty after quicksave and reset", 0, bufferAfterSave.size
            )

        } finally {
            // --- CLEANUP ---
            service.stopRecording()
            FileSavingUtils.clearCachedUri(appContext)
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
        val result =
            vadProcessor.processBuffer(audioBytes, config, debugFileBaseName = "silence_5s")

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

        val result = vadProcessor.processBuffer(
            audioBytes, config, paddingMs = 0, debugFileBaseName = "talking_24s"
        )
        // Then
        assertTrue("Resulting buffer should not be empty for a speech file.", result.isNotEmpty())
        assertTrue(
            "Resulting buffer should be smaller than the original.", result.size < audioBytes.size
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
        val result = vadProcessor.processBuffer(audioBytes, config, debugFileBaseName = "music_30s")

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
        val result = vadProcessor.processBuffer(
            audioBytes, config, debugFileBaseName = "mostly_silence_noise_5min"
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
        val result = vadProcessor.processBuffer(tinyBuffer, config)

        // Then
        assertEquals("Result for a tiny buffer should be empty", 0, result.size)
    }
}
