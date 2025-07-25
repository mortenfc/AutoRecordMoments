package com.mfc.recentaudiobuffer

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import androidx.annotation.VisibleForTesting
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.resample.RateTransposer
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VADProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val VAD_MAX_SAMPLE_RATE = 16000
        private const val VAD_MIN_SAMPLE_RATE = 8000

        // TUNINGS:
        private const val DEFAULT_PADDING_MS = 1300
        private const val SPEECH_THRESHOLD = 0.4f
        private const val DEFAULT_CHUNK_SIZE_S = 60

    }

    data class SpeechTimestamp(val start: Int, var end: Int)

    // --- State variables modeled after the Java example ---
    private var state: Array<Array<FloatArray>>? = null
    private var vadContext: FloatArray? = null
    private var lastSr: Int = 0
    private var lastBatchSize: Int = 0

    private val ortEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession by lazy {
        val modelBytes = context.assets.open("silero_vad.onnx").readBytes()
        ortEnvironment.createSession(modelBytes)
    }

    /**
     * Resets the VAD internal state.
     */
    private fun resetStates() {
        state = arrayOf(Array(1) { FloatArray(128) }) // Shape [1, 1, 128] for h
            .plus(arrayOf(Array(1) { FloatArray(128) })) // Shape [1, 1, 128] for c -> total [2, 1, 128]
        vadContext = null
        lastSr = 0
        lastBatchSize = 0
    }

    @SuppressLint("VisibleForTests")
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun processBuffer(
        fullAudioBuffer: ByteBuffer,
        config: AudioConfig,
        paddingMs: Int = DEFAULT_PADDING_MS,
        debugFileBaseName: String? = null
    ): ByteArray {
        if (fullAudioBuffer.remaining() == 0) return ByteArray(0)

        resetStates()

        if (debugFileBaseName != null) {
            // Note: This requires the new saveDebugFile overload shown in the next section.
            FileSavingUtils.saveDebugFile(
                context, "${debugFileBaseName}_01_original.wav", fullAudioBuffer, config
            )
        }
        val audioFloats = bytesToFloats(fullAudioBuffer, config.bitDepth.bits)
        val originalSampleRate = config.sampleRateHz
        var processedFloats = audioFloats
        var vadSampleRate = originalSampleRate

        val targetVADRate = when {
            originalSampleRate >= VAD_MAX_SAMPLE_RATE -> VAD_MAX_SAMPLE_RATE
            originalSampleRate >= VAD_MIN_SAMPLE_RATE -> VAD_MIN_SAMPLE_RATE
            else -> {
                Timber.e("Unsupported sample rate for VAD: $originalSampleRate Hz.")
                // Return an empty ByteArray, not the ByteBuffer
                return ByteArray(0)
            }
        }

        if (originalSampleRate != targetVADRate) {
            processedFloats = resample(
                processedFloats,
                originalSampleRate.toFloat(),
                targetVADRate.toFloat(),
                config.bitDepth.bits
            )
            vadSampleRate = targetVADRate
            if (debugFileBaseName != null) {
                val resampledBytes = floatsToBytes(processedFloats)
                val resampledConfig = config.copy(sampleRateHz = vadSampleRate)
                FileSavingUtils.saveDebugFile(
                    context,
                    "${debugFileBaseName}_02_resampled.wav",
                    ByteBuffer.wrap(resampledBytes),
                    resampledConfig
                )
            }
        }

        val speechTimestamps = getSpeechTimestamps(processedFloats, vadSampleRate)
        val mergedTimestamps =
            mergeTimestamps(speechTimestamps, paddingMs, vadSampleRate, processedFloats.size)
        // Call the new overloaded stitchAudio that accepts a ByteBuffer
        val finalResultBytes =
            stitchAudio(fullAudioBuffer, mergedTimestamps, config, vadSampleRate.toFloat())

        if (debugFileBaseName != null) {
            FileSavingUtils.saveDebugFile(
                context,
                "${debugFileBaseName}_03_final_result.wav",
                ByteBuffer.wrap(finalResultBytes),
                config
            )
        }

        return finalResultBytes
    }

    fun processBufferInChunks(
        fullAudioBuffer: ByteBuffer,
        config: AudioConfig,
        chunkSizeInSeconds: Int = DEFAULT_CHUNK_SIZE_S
    ): ByteArray {
        val bytesPerSample = config.bitDepth.bits / 8
        val bytesPerSecond = config.sampleRateHz * bytesPerSample
        val chunkSizeBytes = chunkSizeInSeconds * bytesPerSecond

        val resultStream = ByteArrayOutputStream()
        fullAudioBuffer.rewind()

        while (fullAudioBuffer.hasRemaining()) {
            val currentChunkSize = minOf(chunkSizeBytes, fullAudioBuffer.remaining())
            val chunkBytes = ByteArray(currentChunkSize)
            fullAudioBuffer.get(chunkBytes)

            val chunkBuffer = ByteBuffer.wrap(chunkBytes)

            // Process this smaller chunk with your existing function
            val speechInChunk = processBuffer(chunkBuffer, config)

            if (speechInChunk.isNotEmpty()) {
                resultStream.write(speechInChunk)
            }
        }

        return resultStream.toByteArray()
    }

    @Suppress("UNCHECKED_CAST")
    private fun castToStateArray(value: Any?): Array<Array<FloatArray>>? {
        if (value !is Array<*> || value.any { it !is Array<*> }) {
            return null
        }
        // At this point, we have manually checked the structure is Array<Array<*>>.
        // The final cast is as safe as it can be.
        return value as? Array<Array<FloatArray>>
    }

    private fun bytesToFloats(buffer: ByteBuffer, bitsPerSample: Int): FloatArray {
        // Ensure the buffer is read from the beginning
        buffer.rewind()

        // Create a view of the ByteBuffer as a ShortBuffer
        val shortBuffer: ShortBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shortArray = ShortArray(shortBuffer.remaining())
        shortBuffer.get(shortArray)

        // Convert short array to float array
        return FloatArray(shortArray.size) { i ->
            // Normalize to the range [-1.0, 1.0]
            shortArray[i] / 32768.0f
        }
    }

    private fun getSpeechTimestamps(
        audioFloats: FloatArray, sampleRate: Int
    ): List<SpeechTimestamp> {
        val windowSize = if (sampleRate == 16000) 512 else 256
        val contextSize = if (sampleRate == 16000) 64 else 32

        val speechSegments = mutableListOf<SpeechTimestamp>()
        var currentSpeechStart: Int? = null

        // Since we process one buffer at a time, batchSize is always 1.
        val batchSize = 1

        // Reset state if sample rate or batch size changes (as per Java example)
        if (lastSr != sampleRate || lastBatchSize != batchSize) {
            resetStates()
        }
        lastSr = sampleRate
        lastBatchSize = batchSize

        // Initialize context buffer if it's null
        if (vadContext == null) {
            vadContext = FloatArray(contextSize)
        }

        // Process audio in chunks
        audioFloats.toList().chunked(windowSize).forEachIndexed { i, chunk ->
            if (chunk.size < windowSize) return@forEachIndexed // Skip incomplete chunks at the end

            val chunkArray = chunk.toFloatArray()

            // Concatenate context from previous chunk with the current chunk
            val concatenatedInput = vadContext!! + chunkArray

            try {
                // Create Tensors
                val inputTensor =
                    OnnxTensor.createTensor(ortEnvironment, arrayOf(concatenatedInput))
                val srTensor =
                    OnnxTensor.createTensor(ortEnvironment, longArrayOf(sampleRate.toLong()))
                val stateTensor = OnnxTensor.createTensor(ortEnvironment, state)

                val inputs = mapOf(
                    "input" to inputTensor, "sr" to srTensor, "state" to stateTensor
                )

                session.run(inputs).use { result ->
                    val score =
                        ((result[0].value as? Array<*>)?.firstOrNull() as? FloatArray)?.firstOrNull()
                            ?: 0.0f

                    // --- Use the new helper function for a clean, warning-free cast ---
                    val newState = castToStateArray(result[1].value)

                    // Update state and context for the next iteration
                    this.state = newState
                    this.vadContext = chunkArray.takeLast(contextSize).toFloatArray()

                    // Find speech segments based on score
                    val currentSamplePosition = i * windowSize
                    if (score >= SPEECH_THRESHOLD && currentSpeechStart == null) {
                        currentSpeechStart = currentSamplePosition
                    } else if ((currentSpeechStart != null) && (score < SPEECH_THRESHOLD)) {
                        speechSegments.add(
                            SpeechTimestamp(
                                start = currentSpeechStart!!, end = currentSamplePosition
                            )
                        )
                        currentSpeechStart = null
                    }
                }

                inputTensor.close()
                srTensor.close()
                stateTensor.close()

            } catch (e: OrtException) {
                Timber.e(e, "Error during ONNX session run")
                return emptyList()
            }
        }

        if (currentSpeechStart != null) {
            speechSegments.add(
                SpeechTimestamp(start = currentSpeechStart, end = audioFloats.size)
            )
        }

        return speechSegments
    }

    // Helper to convert float array back to bytes for saving
    private fun floatsToBytes(floats: FloatArray): ByteArray {
        val bytes = ByteArray(floats.size * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) {
            val x = (f * 32767.0f).coerceIn(-32768f, 32767f).toInt().toShort()
            buffer.putShort(x)
        }
        return bytes
    }

    private fun bytesToFloats(bytes: ByteArray, bitDepth: Int): FloatArray {
        if (bytes.isEmpty()) return FloatArray(0)
        return when (bitDepth) {
            16 -> {
                val shorts = ShortArray(bytes.size / 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                FloatArray(shorts.size) { shorts[it] / 32768.0f }
            }

            8 -> {
                FloatArray(bytes.size) { (bytes[it].toUByte().toInt() - 128) / 128.0f }
            }

            else -> {
                Timber.e("Unsupported bit depth for VAD processing: $bitDepth")
                throw IllegalArgumentException("Unsupported bit depth: $bitDepth")
            }
        }
    }

    private fun resample(
        input: FloatArray, fromRate: Float, toRate: Float, bitDepth: Int
    ): FloatArray {
        val format = TarsosDSPAudioFormat(fromRate, bitDepth, 1, true, false)
        val audioEvent = AudioEvent(format)
        audioEvent.floatBuffer = input
        val rateTransposer = RateTransposer((toRate / fromRate).toDouble())
        rateTransposer.process(audioEvent)
        return audioEvent.floatBuffer
    }

    private fun mergeTimestamps(
        timestamps: List<SpeechTimestamp>, paddingMs: Int, sampleRate: Int, totalSamples: Int
    ): List<SpeechTimestamp> {
        if (timestamps.isEmpty()) return emptyList()

        val paddingSamples = (paddingMs / 1000f * sampleRate).toInt()
        val mergeGapSamples = sampleRate * 2 // Merge segments closer than 2 seconds

        val merged = mutableListOf<SpeechTimestamp>()
        var currentSegment =
            timestamps.first().copy() // Use copy to avoid modifying the original list item

        for (i in 1 until timestamps.size) {
            val nextTimestamp = timestamps[i]
            val gap = nextTimestamp.start - currentSegment.end
            if (gap < mergeGapSamples) {
                // Merge segments by extending the end of the current one
                currentSegment.end = nextTimestamp.end
            } else {
                merged.add(currentSegment)
                currentSegment = nextTimestamp.copy()
            }
        }
        merged.add(currentSegment)

        // Apply padding to the final merged segments
        return merged.map {
            val start = (it.start - paddingSamples).coerceAtLeast(0)
            val end = (it.end + paddingSamples).coerceAtMost(totalSamples)
            it.copy(start = start, end = end)
        }
    }

    private fun stitchAudio(
        originalBuffer: ByteBuffer,
        segments: List<SpeechTimestamp>,
        config: AudioConfig,
        vadSampleRate: Float
    ): ByteArray {
        val bytesPerSample = config.bitDepth.bits / 8
        val originalSampleRate = config.sampleRateHz
        val scaleFactor = originalSampleRate.toDouble() / vadSampleRate

        // Use a stream to build the final byte array efficiently
        val resultStream = ByteArrayOutputStream()

        segments.forEach { segment ->
            val startByte = (segment.start * scaleFactor).toInt() * bytesPerSample
            val endByte = (segment.end * scaleFactor).toInt() * bytesPerSample
            val lengthInBytes = endByte - startByte

            if (lengthInBytes > 0 && startByte + lengthInBytes <= originalBuffer.capacity()) {
                val segmentBytes = ByteArray(lengthInBytes)
                originalBuffer.position(startByte) // Move the buffer's "read head"
                originalBuffer.get(segmentBytes)      // Read the data into our temp array
                resultStream.write(segmentBytes)
            } else if (lengthInBytes < 0) {
                Timber.e("Stitch error: calculated segment has negative length.")
            }
        }
        return resultStream.toByteArray()
    }
}