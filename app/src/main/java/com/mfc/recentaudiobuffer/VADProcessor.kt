import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mfc.recentaudiobuffer.AudioConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.resample.RateTransposer

@Singleton
class VADProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val VAD_MAX_SAMPLE_RATE = 16000f
    private val VAD_MIN_SAMPLE_RATE = 8000f
    private val session: OrtSession
    private val ortEnvironment = OrtEnvironment.getEnvironment()

    init {
        val modelBytes = context.assets.open("silero_vad_half.onnx").readBytes()
        session = ortEnvironment.createSession(modelBytes)
    }

    fun processBuffer(
        fullAudioBuffer: ByteArray, config: AudioConfig, paddingMs: Int = 5000
    ): ByteArray {
        if (fullAudioBuffer.isEmpty()) return ByteArray(0)

        val audioFloats = bytesToFloats(fullAudioBuffer, config.bitDepth.bits)

        val originalSampleRate = config.sampleRateHz.toFloat()
        var processedFloats = audioFloats
        var vadSampleRate = originalSampleRate

        val targetVADRate = if (originalSampleRate >= VAD_MAX_SAMPLE_RATE) {
            VAD_MAX_SAMPLE_RATE
        } else if (originalSampleRate >= VAD_MIN_SAMPLE_RATE) {
            VAD_MIN_SAMPLE_RATE
        } else {
            Timber.e("Unsupported sample rate for VAD: $originalSampleRate Hz.")
            return fullAudioBuffer
        }

        if (originalSampleRate != targetVADRate) {
            Timber.d("Resampling audio from ${originalSampleRate}Hz to ${targetVADRate}Hz...")
            processedFloats =
                resample(processedFloats, originalSampleRate, targetVADRate, config.bitDepth.bits)
            vadSampleRate = targetVADRate
        }

        val speechTimestamps = getSpeechTimestamps(processedFloats, vadSampleRate.toInt())
        val mergedTimestamps = mergeTimestamps(
            speechTimestamps, paddingMs, vadSampleRate.toInt(), processedFloats.size
        )
        return stitchAudio(fullAudioBuffer, mergedTimestamps, config, vadSampleRate)
    }

    /**
     * Converts a ByteArray of PCM audio to a FloatArray.
     * Handles both 8-bit (unsigned) and 16-bit (signed) audio.
     */
    private fun bytesToFloats(bytes: ByteArray, bitDepth: Int): FloatArray {
        if (bytes.isEmpty()) return FloatArray(0)

        return when (bitDepth) {
            16 -> {
                // Handle 16-bit signed audio
                val shorts = ShortArray(bytes.size / 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                FloatArray(shorts.size) { shorts[it] / 32768.0f }
            }

            8 -> {
                // Handle 8-bit unsigned audio
                // 8-bit PCM is typically unsigned (0-255). We convert it to signed (-128 to 127)
                // and then normalize to a float between -1.0 and 1.0.
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

    // In VADProcessor.kt

    private fun getSpeechTimestamps(
        audioFloats: FloatArray, sampleRate: Int
    ): List<Map<String, Int>> {
        val windowSize = if (sampleRate == 16000) 512 else 256
        val speechThreshold = 0.5f

        val speechSegments = mutableListOf<Map<String, Int>>()
        var currentSpeechStart: Int? = null

        for (i in 0 until audioFloats.size step windowSize) {
            val chunkEnd = i + windowSize
            if (chunkEnd > audioFloats.size) break

            val chunk = audioFloats.sliceArray(i until chunkEnd)
            val inputName = session.inputNames.first()
            val floatBuffer = FloatBuffer.wrap(chunk)
            val tensor = OnnxTensor.createTensor(
                ortEnvironment, floatBuffer, longArrayOf(1, windowSize.toLong())
            )

            try {
                val result = session.run(mapOf(inputName to tensor))
                val outputValue = result[0]?.value // Use safe call ?.value

                when (outputValue) {
                    is Array<*> -> {
                        // The compiler now knows outputValue is an Array.
                        // We can safely check its contents.
                        if (outputValue.isNotEmpty() && outputValue[0] is FloatArray) {
                            // The compiler now smart-casts outputValue[0] to FloatArray
                            val scoreArray = outputValue[0] as FloatArray
                            if (scoreArray.isNotEmpty()) {
                                val score = scoreArray[0]

                                // Your state machine logic
                                if (score >= speechThreshold && currentSpeechStart == null) {
                                    currentSpeechStart = i
                                } else if (score < speechThreshold && currentSpeechStart != null) {
                                    speechSegments.add(
                                        mapOf(
                                            "start" to currentSpeechStart, "end" to i
                                        )
                                    )
                                    currentSpeechStart = null
                                }
                            }
                        } else {
                            Timber.e("ONNX model output was an array, but not of the expected FloatArray type or was empty.")
                        }
                    }

                    else -> {
                        // This block runs if outputValue is null or not an Array at all.
                        Timber.e("Unexpected ONNX model output type: ${outputValue?.javaClass?.name}")
                    }
                }

                result.close()
            } finally {
                tensor.close()
            }
        }

        if (currentSpeechStart != null) {
            speechSegments.add(mapOf("start" to currentSpeechStart, "end" to audioFloats.size))
        }

        return speechSegments
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

            System.arraycopy(originalBuffer, startByte, newBuffer, currentPosition, lengthInBytes)
            currentPosition += lengthInBytes
        }
        return newBuffer.copyOf(currentPosition)
    }
}