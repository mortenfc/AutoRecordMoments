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
        const val DEFAULT_PADDING_MS = 1300
        private const val SPEECH_THRESHOLD = 0.4f
        private const val DEFAULT_CHUNK_SIZE_B = 4096

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

    /**
     * Processes a full audio buffer by streaming it through the VAD in small,
     * memory-efficient pieces to detect speech.
     */
    @SuppressLint("VisibleForTests")
    fun process(
        fullAudioBuffer: ByteBuffer,
        config: AudioConfig,
        paddingMs: Int = DEFAULT_PADDING_MS,
        debugFileBaseName: String? = null,
        onProgress: ((Float) -> Unit)? = null
    ): ByteArray {
        resetStates()

        if (debugFileBaseName != null) {
            FileSavingUtils.saveDebugFile(
                context, "${debugFileBaseName}_01_original.wav", fullAudioBuffer, config
            )
        }

        val targetVADRate = when {
            config.sampleRateHz >= VAD_MAX_SAMPLE_RATE -> VAD_MAX_SAMPLE_RATE
            config.sampleRateHz >= VAD_MIN_SAMPLE_RATE -> VAD_MIN_SAMPLE_RATE
            else -> config.sampleRateHz
        }

        val allTimestamps = mutableListOf<SpeechTimestamp>()
        var totalResampledSamples = 0

        val resampler = RateTransposer((targetVADRate.toDouble() / config.sampleRateHz.toDouble()))
        val tarsosFormat = TarsosDSPAudioFormat(
            config.sampleRateHz.toFloat(), config.bitDepth.bits, 1, true, false
        )

        val readBuffer = ByteArray(DEFAULT_CHUNK_SIZE_B)
        fullAudioBuffer.rewind()

        val totalBytes = fullAudioBuffer.limit().toFloat()
        var lastReportedProgressPercent = -1

        while (fullAudioBuffer.hasRemaining()) {
            val toRead = minOf(readBuffer.size, fullAudioBuffer.remaining())
            fullAudioBuffer.get(readBuffer, 0, toRead)
            val audioChunk =
                if (toRead < DEFAULT_CHUNK_SIZE_B) readBuffer.copyOfRange(0, toRead) else readBuffer

            val floatChunk = bytesToFloats(ByteBuffer.wrap(audioChunk), config.bitDepth.bits)

            val audioEvent = AudioEvent(tarsosFormat).apply { floatBuffer = floatChunk }
            resampler.process(audioEvent)
            val resampledFloats = audioEvent.floatBuffer

            val timestampsInChunk = getSpeechTimestamps(resampledFloats, targetVADRate)

            timestampsInChunk.forEach {
                allTimestamps.add(
                    SpeechTimestamp(
                        start = it.start + totalResampledSamples,
                        end = it.end + totalResampledSamples
                    )
                )
            }
            totalResampledSamples += resampledFloats.size

            val currentProgress = fullAudioBuffer.position() / totalBytes
            val currentProgressPercent = (currentProgress * 100).toInt()
            // Only report on whole percentage changes to avoid spamming updates.
            if (currentProgressPercent > lastReportedProgressPercent) {
                onProgress?.invoke(currentProgress)
                lastReportedProgressPercent = currentProgressPercent
            }
        }

        val mergedTimestamps =
            mergeTimestamps(allTimestamps, paddingMs, targetVADRate, totalResampledSamples)

        val bufferForStitching = fullAudioBuffer.asReadOnlyBuffer()
        val finalResultBytes =
            stitchAudio(bufferForStitching, mergedTimestamps, config, targetVADRate.toFloat())

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

    private fun bytesToFloats(buffer: ByteBuffer, bitsPerSample: Int): FloatArray {
        buffer.rewind()

        return when (bitsPerSample) {
            16 -> {
                val shortBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val shortArray = ShortArray(shortBuffer.remaining())
                shortBuffer.get(shortArray)
                FloatArray(shortArray.size) { i ->
                    shortArray[i] / 32768.0f
                }
            }

            8 -> {
                val byteArray = ByteArray(buffer.remaining())
                buffer.get(byteArray)
                FloatArray(byteArray.size) { i ->
                    // Convert unsigned 8-bit int to signed float in [-1.0, 1.0]
                    (byteArray[i].toUByte().toInt() - 128) / 128.0f
                }
            }

            else -> {
                Timber.e("Unsupported bit depth for VAD processing: $bitsPerSample")
                throw IllegalArgumentException("Unsupported bit depth: $bitsPerSample")
            }
        }
    }

    private fun getSpeechTimestamps(
        audioFloats: FloatArray, sampleRate: Int
    ): List<SpeechTimestamp> {
        val windowSize = if (sampleRate == 16000) 512 else 256
        val contextSize = if (sampleRate == 16000) 64 else 32

        val speechSegments = mutableListOf<SpeechTimestamp>()
        var currentSpeechStart: Int? = null

        if (vadContext == null) {
            vadContext = FloatArray(contextSize)
        }

        audioFloats.toList().chunked(windowSize).forEachIndexed { i, chunk ->
            if (chunk.size < windowSize) return@forEachIndexed

            val chunkArray = chunk.toFloatArray()
            val concatenatedInput = vadContext!! + chunkArray

            try {
                val inputTensor =
                    OnnxTensor.createTensor(ortEnvironment, arrayOf(concatenatedInput))
                val srTensor =
                    OnnxTensor.createTensor(ortEnvironment, longArrayOf(sampleRate.toLong()))
                val stateTensor = OnnxTensor.createTensor(ortEnvironment, state)

                val inputs = mapOf("input" to inputTensor, "sr" to srTensor, "state" to stateTensor)

                session.run(inputs).use { result ->
                    val score =
                        ((result[0].value as? Array<*>)?.firstOrNull() as? FloatArray)?.firstOrNull()
                            ?: 0.0f
                    val newState = castToStateArray(result[1].value)

                    this.state = newState
                    this.vadContext = chunkArray.takeLast(contextSize).toFloatArray()

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
            speechSegments.add(SpeechTimestamp(start = currentSpeechStart, end = audioFloats.size))
        }

        return speechSegments
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

    @Suppress("UNCHECKED_CAST")
    private fun castToStateArray(value: Any?): Array<Array<FloatArray>>? {
        if (value !is Array<*> || value.any { it !is Array<*> }) {
            return null
        }
        // At this point, we have manually checked the structure is Array<Array<*>>.
        // The final cast is as safe as it can be.
        return value as? Array<Array<FloatArray>>
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