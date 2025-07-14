package com.mfc.recentaudiobuffer

import android.content.Context
import android.media.AudioFormat
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mfc.recentaudiobuffer.VADProcessor.Companion.readWavHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

            // --- REVISED DELETION LOGIC ---
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


    // --- TEST SETUP ---
    @Before
    fun setUp() {
        // This ensures Timber is always active for your tests.
        Timber.plant(Timber.DebugTree())

        context = InstrumentationRegistry.getInstrumentation().targetContext
        vadProcessor = VADProcessor(context)
    }


    // --- TESTS ---

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

        val result =
            vadProcessor.processBuffer(audioBytes, config, paddingMs = 0, debugFileBaseName = "talking_24s")
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
