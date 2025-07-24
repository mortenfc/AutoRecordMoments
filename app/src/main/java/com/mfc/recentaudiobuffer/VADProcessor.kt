package com.mfc.recentaudiobuffer

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.resample.RateTransposer
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VADProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val VAD_MAX_SAMPLE_RATE = 16000
        private const val VAD_MIN_SAMPLE_RATE = 8000
        private const val WAV_HEADER_SIZE = 44

        // TUNINGS:
        private const val DEFAULT_PADDING_MS = 1300
        private const val SPEECH_THRESHOLD = 0.4f

        /**
         * Reads the sample rate and bit depth from a WAV file's header.
         */
        fun readWavHeader(wavBytes: ByteArray): AudioConfig {
            if (wavBytes.size < WAV_HEADER_SIZE) {
                throw IllegalArgumentException("Invalid WAV header: file is too small.")
            }
            val buffer = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
            val sampleRate = buffer.getInt(24)
            val bitDepthValue = buffer.getShort(34).toInt()

            Timber.d("Read from WAV: sampleRate: $sampleRate, bitDepth: $bitDepthValue")

            val bitDepth = bitDepths["$bitDepthValue"]
                ?: throw IllegalArgumentException("Unsupported bit depth found in WAV header: $bitDepthValue")

            return AudioConfig(sampleRate, 0, bitDepth)
        }
    }

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

    fun processBuffer(
        fullAudioBuffer: ByteArray,
        config: AudioConfig,
        paddingMs: Int = DEFAULT_PADDING_MS,
        debugFileBaseName: String? = null
    ): ByteArray {
        if (fullAudioBuffer.isEmpty()) return ByteArray(0)

        // Reset state for each new buffer processing call to ensure no bleed-over.
        resetStates()

        if (debugFileBaseName != null) {
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
                return fullAudioBuffer
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
                    resampledBytes,
                    resampledConfig
                )
            }
        }

        val speechTimestamps = getSpeechTimestamps(processedFloats, vadSampleRate)
        val mergedTimestamps =
            mergeTimestamps(speechTimestamps, paddingMs, vadSampleRate, processedFloats.size)
        val finalResultBytes =
            stitchAudio(fullAudioBuffer, mergedTimestamps, config, vadSampleRate.toFloat())

        if (debugFileBaseName != null) {
            FileSavingUtils.saveDebugFile(
                context, "${debugFileBaseName}_03_final_result.wav", finalResultBytes, config
            )
        }

        return finalResultBytes
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

    private fun getSpeechTimestamps(
        audioFloats: FloatArray, sampleRate: Int
    ): List<Map<String, Int>> {
        val windowSize = if (sampleRate == 16000) 512 else 256
        val contextSize = if (sampleRate == 16000) 64 else 32

        val speechSegments = mutableListOf<Map<String, Int>>()
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
                            mapOf(
                                "start" to currentSpeechStart!!, "end" to currentSamplePosition
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
                mapOf(
                    "start" to currentSpeechStart!!, "end" to audioFloats.size
                )
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
        timestamps: List<Map<String, Int>>, paddingMs: Int, sampleRate: Int, totalSamples: Int
    ): List<Map<String, Int>> {
        if (timestamps.isEmpty()) return emptyList()
        val paddingSamples = (paddingMs / 1000f * sampleRate).toInt()
        val mergeGapSamples = sampleRate * 2
        val merged = mutableListOf<Map<String, Int>>()
        var currentSegment = timestamps.first().toMutableMap()
        for (i in 1 until timestamps.size) {
            val nextTimestamp = timestamps[i]
            val gap = nextTimestamp["start"]!! - currentSegment["end"]!!
            if (gap < mergeGapSamples) {
                currentSegment["end"] = nextTimestamp["end"]!!
            } else {
                merged.add(currentSegment)
                currentSegment = nextTimestamp.toMutableMap()
            }
        }
        merged.add(currentSegment)
        return merged.map {
            val start = (it["start"]!! - paddingSamples).coerceAtLeast(0)
            val end = (it["end"]!! + paddingSamples).coerceAtMost(totalSamples)
            mapOf("start" to start, "end" to end)
        }
    }

    private fun stitchAudio(
        originalBuffer: ByteArray,
        segments: List<Map<String, Int>>,
        config: AudioConfig,
        vadSampleRate: Float
    ): ByteArray {
        val bytesPerSample = config.bitDepth.bits / 8
        val originalSampleRate = config.sampleRateHz
        val scaleFactor = originalSampleRate.toDouble() / vadSampleRate

        val totalSizeInBytes = segments.sumOf {
            val originalStart = (it["start"]!! * scaleFactor).toInt()
            val originalEnd = (it["end"]!! * scaleFactor).toInt()
            (originalEnd - originalStart) * bytesPerSample
        }

        if (totalSizeInBytes == 0) return ByteArray(0)
        val newBuffer = ByteArray(totalSizeInBytes)
        var currentPosition = 0

        segments.forEach { segment ->
            val startByte = (segment["start"]!! * scaleFactor).toInt() * bytesPerSample
            val endByte = (segment["end"]!! * scaleFactor).toInt() * bytesPerSample
            val lengthInBytes = endByte - startByte
            if (startByte + lengthInBytes > originalBuffer.size || lengthInBytes < 0) {
                Timber.e("Stitch error: calculated segment is out of bounds.")
                return@forEach
            }
            System.arraycopy(
                originalBuffer, startByte, newBuffer, currentPosition, lengthInBytes
            )
            currentPosition += lengthInBytes
        }
        return newBuffer.copyOf(currentPosition)
    }
}