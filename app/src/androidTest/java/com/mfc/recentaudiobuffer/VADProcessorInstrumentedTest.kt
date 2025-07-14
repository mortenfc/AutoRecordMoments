package com.mfc.recentaudiobuffer

import android.content.Context
import android.media.AudioFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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
 * 2. The ONNX model (`silero_vad_half.onnx`) must be in `src/main/assets`.
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

    // Standard WAV header size is 44 bytes. We skip this to get to the raw PCM data.
    private val WAV_HEADER_SIZE = 44L

    /**
     * Reads the sample rate and bit depth from a WAV file's header.
     * Assumes the input ByteArray contains at least 44 bytes of header.
     * @return A Pair containing Sample Rate (Int) and Bit Depth (Int).
     */
    private fun readWavHeader(wavBytes: ByteArray): AudioConfig {
        if (wavBytes.size < WAV_HEADER_SIZE) {
            throw IllegalArgumentException("Invalid WAV header: file is too small.")
        }
        val buffer = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = buffer.getInt(24)
        val bitDepthValue = buffer.getShort(34).toInt()

        Timber.d("Read from WAV: sampleRate: $sampleRate, bitDepth: $bitDepthValue")

        // Safely look up the bit depth
        val bitDepth = bitDepths["$bitDepthValue"]
            ?: throw IllegalArgumentException("Unsupported bit depth found in WAV header: $bitDepthValue")

        return AudioConfig(sampleRate, 0, bitDepth)
    }

    @Before
    fun setUp() {
        // Get the context of the application under test. This is a real context.
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Instantiate the VADProcessor with the real context. It will load the real ONNX model.
        vadProcessor = VADProcessor(context)
    }

    @Test
    fun processBuffer_withSilenceFile_returnsEmptyOrVerySmallBuffer() {
        // Given: A real audio file containing only silence.
        val silenceAudioBytes = loadAudioFromTestAssets("silence_5s.wav", skipHeader = true)
        val config = readWavHeader(silenceAudioBytes)
        assertEquals(config.sampleRateHz, 16000)
        assertEquals(config.bitDepth, BitDepth(16, AudioFormat.ENCODING_PCM_16BIT))

        // When: The buffer is processed by the real VAD model
        val result = vadProcessor.processBuffer(silenceAudioBytes, config, paddingMs = 200)

        // Then: The resulting buffer should be empty or extremely small, as no speech is detected.
        val maxTolerableBytes =
            config.sampleRateHz / 10 * (config.bitDepth.bits / 8) // 100ms tolerance
        assertTrue(
            "Buffer from silence file should be nearly empty.", result.size < maxTolerableBytes
        )
    }

    @Test
    fun processBuffer_withSpeechFile44100Hz_resample_returnsSignificantNonEmptyBuffer() {
        // Given: A real audio file containing speech.
        val speechAudioBytes = loadAudioFromTestAssets("talking_24s.wav", skipHeader = true)
        val config = readWavHeader(speechAudioBytes)
        assertEquals(config.sampleRateHz, 44100)
        assertEquals(config.bitDepth, BitDepth(16, AudioFormat.ENCODING_PCM_16BIT))
        assertTrue("Test setup failed: Speech audio file is empty.", speechAudioBytes.isNotEmpty())

        // When: The buffer is processed by the real VAD model
        val result = vadProcessor.processBuffer(speechAudioBytes, config, paddingMs = 300)

        // Then: The resulting buffer should contain significant data.
        assertTrue("Resulting buffer should not be empty for a speech file.", result.isNotEmpty())
        assertTrue(
            "Resulting buffer should be smaller than the original.",
            result.size < speechAudioBytes.size
        )
    }

    @Test
    fun processBuffer_withMusicFile_returnsVerySmallBuffer() {
        // Given: A file containing music. The VAD should ideally ignore this
        val audioBytes = loadAudioFromTestAssets("music_30s.wav", skipHeader = true)
        val config = readWavHeader(audioBytes)
        assertEquals(config.sampleRateHz, 16000)
        assertEquals(config.bitDepth, BitDepth(16, AudioFormat.ENCODING_PCM_16BIT))

        // When: The buffer is processed
        val result = vadProcessor.processBuffer(audioBytes, config, paddingMs = 300)

        // Then: The result should be very small. If the music has vocals, some detection is expected.
        // We'll assert that less than 25% of the file is classified as speech.
        val maxTolerableBytes = audioBytes.size / 4
        assertTrue(
            "Music file should result in very little or no detected speech. Found ${result.size} bytes.",
            result.size < maxTolerableBytes
        )
    }

    @Test
    fun processBuffer_withNoisySilenceFile_returnsSmallBuffer() {
        // Given: A long file with mostly silence but some background noise.
        val audioBytes = loadAudioFromTestAssets("mostly_silence_noise_5min.wav", skipHeader = true)
        val config = readWavHeader(audioBytes)
        assertEquals(config.sampleRateHz, 16000)
        assertEquals(config.bitDepth, BitDepth(16, AudioFormat.ENCODING_PCM_16BIT))

        // When: The buffer is processed
        val result = vadProcessor.processBuffer(audioBytes, config, paddingMs = 300)

        // Then: The result should be small relative to the original file size,
        // demonstrating robustness to noise.
        val maxTolerableBytes =
            audioBytes.size / 10 // Expect less than 10% of the file to be detected
        assertTrue(
            "Noisy silence file should result in little detected speech. Found ${result.size} bytes.",
            result.size < maxTolerableBytes
        )
    }

    @Test
    fun processBuffer_withBufferSmallerThanWindowSize_doesNotCrash() {
        // Given: A tiny audio buffer, smaller than the VAD's 512-sample window.
        val config = AudioConfig(16000, 0, BitDepth(16, AudioFormat.ENCODING_PCM_16BIT))
        val tinyBuffer = ByteArray(500 * 2) // 500 samples, 16-bit
        val bb = ByteBuffer.wrap(tinyBuffer).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 500) {
            bb.putShort(100)
        }

        // When: The buffer is processed
        val result = vadProcessor.processBuffer(tinyBuffer, config)

        // Then: The processor should handle it gracefully and not crash. The result should be empty.
        assertEquals("Result for a tiny buffer should be empty", 0, result.size)
    }

    /**
     * Helper function to load raw PCM data from a test asset file.
     * @param fileName The name of the file in `src/androidTest/assets`.
     * @param skipHeader If true, skips the first 44 bytes, assuming a standard WAV header.
     */
    private fun loadAudioFromTestAssets(fileName: String, skipHeader: Boolean = false): ByteArray {
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
}
