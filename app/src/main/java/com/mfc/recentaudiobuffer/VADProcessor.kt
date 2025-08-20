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
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.annotation.SuppressLint
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.min
import kotlin.system.measureNanoTime
/**
 * Final combined VADProcessor — upgraded:
 * - Replaced RateTransposer with a fast, low-allocation linear resampler (resampleLinear)
 * - Added a micro-benchmark helper measureProcessingMsForBuffer(...) to measure time and allocated bytes
 * - Kept pool-based producer/consumer + reusable buffers
 *
 * The linear resampler is intentionally simple and very fast; it's a good tradeoff for VAD where extreme
 * resampling fidelity is less important than throughput and low allocations. If you want a higher-quality
 * resampler later (sinc / polyphase), I can plug that in.
 */
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
        // Toggle parallel pipeline
        const val USE_PARALLEL_PIPELINE = true
    }
    private fun calculateDefaultPoolSize(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores <= 2 -> 2
            cores <= 4 -> 4
            else -> min(cores - 1, 8)
        }
    }
    private val poolSize = calculateDefaultPoolSize()
    data class SpeechTimestamp(val start: Int, var end: Int)
    // Benchmark result container
    data class BenchmarkResult(val avgMs: Double, val avgAllocBytes: Long)
    // VAD model state
    private var state: Array<Array<FloatArray>>? = null
    private var vadContext: FloatArray? = null
    // ONNX Environment and Session
    private val ortEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession by lazy { createONNXSession() }
    // --- Reusable buffers to avoid allocation churn ---
    // FIX: Sized for worst-case (8-bit audio), where 1 byte = 1 float.
    private val reusableFloatBuffer = FloatArray(DEFAULT_CHUNK_SIZE_B)
    // Conservative size for resampled buffer (upsampling safety margin)
    private val reusableResampledBufferSize =
        ceil(DEFAULT_CHUNK_SIZE_B * (VAD_MAX_SAMPLE_RATE.toDouble() / VAD_MIN_SAMPLE_RATE)).toInt()
    private val reusableResampledBuffer = FloatArray(reusableResampledBufferSize)
    // Buffer for VAD model input: max context (64) + max window (512)
    private val reusableVadInputBuffer = FloatArray(64 + 512)
    // ---
    private fun createONNXSession(): OrtSession {
        val modelBytes = context.assets.open("silero_vad.onnx").readBytes()
        try {
            val sessionOptions = OrtSession.SessionOptions()
            try {
                Timber.d("Attempting to configure NNAPI with USE_FP16 flag.")
                sessionOptions.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
            } catch (e: Exception) {
                Timber.w(e, "Failed to configure NNAPI with FP16, falling back to default NNAPI.")
                sessionOptions.addNnapi()
            }
            Timber.d("Attempting to create ONNX session with NNAPI provider.")
            return ortEnvironment.createSession(modelBytes, sessionOptions)
        } catch (e: Exception) {
            Timber.w(e, "Failed to create ONNX session with NNAPI. Falling back to CPU provider.")
        }
        Timber.d("Creating ONNX session with default CPU provider.")
        return ortEnvironment.createSession(modelBytes)
    }
    private fun resetStates() {
        state = arrayOf(Array(1) { FloatArray(128) }).plus(arrayOf(Array(1) { FloatArray(128) }))
        vadContext = null
    }
    /**
     * Public processing function (unchanged external signature).
     * Internally uses the fast adaptive resampler.
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
        debugFileBaseName?.let {
            FileSavingUtils.saveDebugFile(context, "${it}_01_original.wav", fullAudioBuffer, config)
        }
        val targetVADRate = when {
            config.sampleRateHz >= VAD_MAX_SAMPLE_RATE -> VAD_MAX_SAMPLE_RATE
            config.sampleRateHz >= VAD_MIN_SAMPLE_RATE -> VAD_MIN_SAMPLE_RATE
            else -> config.sampleRateHz
        }
        val allTimestamps = mutableListOf<SpeechTimestamp>()
        var totalResampledSamples = 0
        val readBuffer = ByteArray(DEFAULT_CHUNK_SIZE_B)
        fullAudioBuffer.rewind()
        val totalBytes = fullAudioBuffer.limit().toFloat()
        var lastReportedProgressPercent = -1
        onProgress?.invoke(0f)
        // Pre-create srTensor once for this process call (unchanging)
        val srTensor = OnnxTensor.createTensor(ortEnvironment, longArrayOf(targetVADRate.toLong()))
        // We'll allocate an off-heap FloatBuffer for ONNX input and reuse it for every window.
        val maxVadInputLen = 64 + 512
        val onnxByteBuf =
            ByteBuffer.allocateDirect(maxVadInputLen * 4).order(ByteOrder.nativeOrder())
        val onnxFloatBuf = onnxByteBuf.asFloatBuffer()
        // Channels/pool variables for finally-closing if necessary
        var availableIndices: Channel<Int>? = null
        var dataChannel: Channel<Pair<Int, Int>>? = null
        try {
            if (USE_PARALLEL_PIPELINE) {
                // Pool-based pipeline
                val pool = Array(poolSize) { FloatArray(reusableResampledBufferSize) }
                availableIndices = Channel(capacity = poolSize)
                dataChannel = Channel(capacity = poolSize) // Pair(index, validCount)
                runBlocking {
                    // Fill available indices
                    for (i in 0 until poolSize) availableIndices.send(i)
                    val producer = launch(Dispatchers.Default) {
                        try {
                            while (fullAudioBuffer.hasRemaining()) {
                                val toRead = minOf(readBuffer.size, fullAudioBuffer.remaining())
                                fullAudioBuffer.get(readBuffer, 0, toRead)
                                // Convert bytes -> floats (reusable buffer)
                                val floatCount = bytesToFloats(
                                    readBuffer,
                                    toRead,
                                    config.bitDepth.bits,
                                    reusableFloatBuffer
                                )
                                // Fast adaptive resample INTO pool slot
                                val idx = availableIndices.receive()
                                val slot = pool[idx]
                                val ratio =
                                    targetVADRate.toDouble() / config.sampleRateHz.toDouble()
                                val resampledCount = resampleAdaptive(
                                    src = reusableFloatBuffer,
                                    srcLen = floatCount,
                                    dst = slot,
                                    dstCapacity = slot.size,
                                    ratio = ratio
                                )
                                // Send index + length to consumer
                                dataChannel.send(Pair(idx, resampledCount))
                                // Progress reporting
                                val currentProgress = fullAudioBuffer.position() / totalBytes
                                val currentProgressPercent = (currentProgress * 100).toInt()
                                if (currentProgressPercent > lastReportedProgressPercent) {
                                    onProgress?.invoke(currentProgress)
                                    lastReportedProgressPercent = currentProgressPercent
                                }
                            }
                        } finally {
                            dataChannel.close()
                        }
                    }
                    val consumer = launch(Dispatchers.Default) {
                        var processedResampledSamples = 0
                        for ((idx, count) in dataChannel) {
                            val bufferSlot = pool[idx]
                            // Process sequentially with ONNX while preserving state
                            val timestampsInChunk = getSpeechTimestampsFromBuffer(
                                bufferSlot,
                                count,
                                targetVADRate,
                                onnxFloatBuf,
                                srTensor
                            )
                            timestampsInChunk.forEach {
                                allTimestamps.add(
                                    SpeechTimestamp(
                                        start = it.start + processedResampledSamples,
                                        end = it.end + processedResampledSamples
                                    )
                                )
                            }
                            processedResampledSamples += count
                            // Return slot to pool
                            availableIndices!!.send(idx)
                        }
                        totalResampledSamples = processedResampledSamples
                    }
                    producer.join()
                    consumer.join()
                }
                // Close availableIndices after all slots returned
                try {
                    availableIndices.close()
                } catch (_: Exception) {
                }
            } else {
                // Single-threaded path (also uses adaptive resampler)
                while (fullAudioBuffer.hasRemaining()) {
                    val toRead = minOf(readBuffer.size, fullAudioBuffer.remaining())
                    fullAudioBuffer.get(readBuffer, 0, toRead)
                    val floatCount =
                        bytesToFloats(readBuffer, toRead, config.bitDepth.bits, reusableFloatBuffer)
                    val ratio = targetVADRate.toDouble() / config.sampleRateHz.toDouble()
                    val resampledCount = resampleAdaptive(
                        src = reusableFloatBuffer,
                        srcLen = floatCount,
                        dst = reusableResampledBuffer,
                        dstCapacity = reusableResampledBuffer.size,
                        ratio = ratio
                    )
                    val timestampsInChunk = getSpeechTimestampsFromBuffer(
                        reusableResampledBuffer,
                        resampledCount,
                        targetVADRate,
                        onnxFloatBuf,
                        srTensor
                    )
                    timestampsInChunk.forEach {
                        allTimestamps.add(
                            SpeechTimestamp(
                                start = it.start + totalResampledSamples,
                                end = it.end + totalResampledSamples
                            )
                        )
                    }
                    totalResampledSamples += resampledCount
                    val currentProgress = fullAudioBuffer.position() / totalBytes
                    val currentProgressPercent = (currentProgress * 100).toInt()
                    if (currentProgressPercent > lastReportedProgressPercent) {
                        onProgress?.invoke(currentProgress)
                        lastReportedProgressPercent = currentProgressPercent
                    }
                }
            }
        } finally {
            // Ensure srTensor closed under all conditions
            try {
                srTensor.close()
            } catch (_: Exception) {
            }
            // Ensure channels closed if something went wrong
            try {
                dataChannel?.close()
            } catch (_: Exception) {
            }
            try {
                availableIndices?.close()
            } catch (_: Exception) {
            }
        }
        val mergedTimestamps =
            mergeTimestamps(allTimestamps, paddingMs, targetVADRate, totalResampledSamples)
        val bufferForStitching = fullAudioBuffer.asReadOnlyBuffer()
        val finalResultBytes =
            stitchAudio(bufferForStitching, mergedTimestamps, config, targetVADRate.toFloat())
        debugFileBaseName?.let {
            FileSavingUtils.saveDebugFile(
                context,
                "${it}_03_final_result.wav",
                ByteBuffer.wrap(finalResultBytes),
                config
            )
        }
        return finalResultBytes
    }
    /**
     * Very fast linear resampler.
     */
    private fun resampleLinear(
        src: FloatArray,
        srcLen: Int,
        dst: FloatArray,
        dstCapacity: Int,
        ratio: Double
    ): Int {
        if (srcLen <= 0 || ratio <= 0.0) return 0

        val outLen = min((srcLen * ratio).toInt(), dstCapacity)
        if (outLen == 0) return 0

        val step = 1.0 / ratio
        var srcPos = 0.0
        var outIdx = 0
        while (outIdx < outLen) {
            val i0 = srcPos.toInt()
            val i1 = (i0 + 1).coerceAtMost(srcLen - 1)
            val s0 = src[i0.coerceIn(0, srcLen - 1)]
            val s1 = src[i1]
            val frac = (srcPos - i0).toFloat()
            dst[outIdx] = s0 + frac * (s1 - s0)
            outIdx++
            srcPos += step
        }
        return outIdx
    }
    /**
     * High-quality sinc resampler for downsampling.
     */
    private fun resampleSinc(
        src: FloatArray,
        srcLen: Int,
        dst: FloatArray,
        dstCapacity: Int,
        ratio: Double,
        taps: Int = 24
    ): Int {
        if (srcLen <= 0 || ratio <= 0.0) return 0
        val outLen = min((srcLen * ratio).toInt(), dstCapacity)
        if (outLen == 0) return 0

        val fc = 0.5 * ratio // Normalized cutoff frequency
        val halfTaps = taps / 2.0
        var outIdx = 0
        var pos = 0.0
        val step = 1.0 / ratio

        while (outIdx < outLen) {
            val center = pos
            var sum = 0.0f
            var weightSum = 0.0f
            val iStart = (center - halfTaps).toInt().coerceAtLeast(0)
            val iEnd = (center + halfTaps).toInt().coerceAtMost(srcLen - 1)

            for (i in iStart..iEnd) {
                val x = (center - i)
                if (x == 0.0) {
                    sum += src[i]
                    weightSum += 1.0f
                    continue
                }
                val piX = (Math.PI * x).toFloat()
                // Sinc function: sin(pi*x*2*fc) / (pi*x)
                val sinc = (kotlin.math.sin(piX * 2.0f * fc.toFloat()) / piX)
                // Hann window
                val window = (0.5f + 0.5f * kotlin.math.cos(piX / halfTaps.toFloat()))
                val coeff = sinc * window
                sum += coeff * src[i]
                weightSum += coeff
            }

            dst[outIdx] = if (weightSum != 0.0f) sum / weightSum else sum
            outIdx++
            pos += step
        }
        return outIdx
    }
    /**
     * Chooses the best resampler. Linear is fast for upsampling, but sinc is needed
     * for downsampling to prevent aliasing artifacts that can confuse the VAD.
     */
    private fun resampleAdaptive(
        src: FloatArray,
        srcLen: Int,
        dst: FloatArray,
        dstCapacity: Int,
        ratio: Double
    ): Int {
        return if (ratio >= 0.7) { // Use linear for upsampling or mild downsampling
            resampleLinear(src, srcLen, dst, dstCapacity, ratio)
        } else { // Use sinc for significant downsampling to prevent aliasing
            resampleSinc(src, srcLen, dst, dstCapacity, ratio, taps = 24)
        }
    }
    /**
     * Simple micro-benchmark to measure processing time and approximate allocation delta.
     */
    fun measureProcessingMsForBuffer(
        inputBuffer: ByteBuffer,
        config: AudioConfig,
        runs: Int = 3,
        warmup: Int = 1
    ): BenchmarkResult {
        if (runs <= 0) throw IllegalArgumentException("runs must be > 0")
        val copy = ByteArray(inputBuffer.limit())
        inputBuffer.rewind()
        inputBuffer.get(copy)
        val wrapper = ByteBuffer.wrap(copy)
        // Warmup
        repeat(warmup) {
            try {
                // small warmup run, do not collect timings
                process(wrapper.duplicate().asReadOnlyBuffer(), config, onProgress = null)
            } catch (_: Exception) {}
        }
        // Try to reduce GC noise
        System.gc()
        val runtimes = LongArray(runs)
        val allocs = LongArray(runs)
        for (i in 0 until runs) {
            val memBefore = usedMemory()
            val t = measureNanoTime {
                try {
                    process(wrapper.duplicate().asReadOnlyBuffer(), config, onProgress = null)
                } catch (e: Exception) {
                    Timber.w(e, "Benchmark run failed")
                }
            }
            val memAfter = usedMemory()
            runtimes[i] = t
            allocs[i] = (memAfter - memBefore).coerceAtLeast(0L)
            // small delay to allow system to settle
            Thread.sleep(50)
        }
        val avgMs = runtimes.average() / 1_000_000.0
        val avgAlloc = allocs.average().toLong()
        return BenchmarkResult(avgMs, avgAlloc)
    }
    private fun usedMemory(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }
    private fun bytesToFloats(
        source: ByteArray,
        bytesToRead: Int,
        bitsPerSample: Int,
        dest: FloatArray
    ): Int {
        val byteBuffer = ByteBuffer.wrap(source, 0, bytesToRead).order(ByteOrder.LITTLE_ENDIAN)
        return when (bitsPerSample) {
            16 -> {
                val shortBuffer = byteBuffer.asShortBuffer()
                val count = shortBuffer.remaining()
                // manual get is a little faster than creating an intermediate ShortArray
                for (i in 0 until count) {
                    dest[i] = shortBuffer.get(i) / 32768.0f
                }
                count
            }
            8 -> {
                val count = byteBuffer.remaining()
                for (i in 0 until count) {
                    dest[i] = (byteBuffer.get(i).toUByte().toInt() - 128) / 128.0f
                }
                count
            }
            else -> throw IllegalArgumentException("Unsupported bit depth: $bitsPerSample")
        }
    }
    private fun getSpeechTimestampsFromBuffer(
        audioFloats: FloatArray,
        audioFloatCount: Int,
        sampleRate: Int,
        onnxFloatBuf: FloatBuffer,
        srTensor: OnnxTensor
    ): List<SpeechTimestamp> {
        val windowSize = if (sampleRate == 16000) 512 else 256
        val contextSize = if (sampleRate == 16000) 64 else 32
        val speechSegments = mutableListOf<SpeechTimestamp>()
        var currentSpeechStart: Int? = null
        if (vadContext == null) vadContext = FloatArray(contextSize)
        val vadInputLen = contextSize + windowSize
        var i = 0
        while (i + windowSize <= audioFloatCount) {
            // fill reusableVadInputBuffer: context + window
            System.arraycopy(vadContext!!, 0, reusableVadInputBuffer, 0, contextSize)
            System.arraycopy(audioFloats, i, reusableVadInputBuffer, contextSize, windowSize)
            // Fill direct FloatBuffer (onnxFloatBuf) with only the active length
            onnxFloatBuf.rewind()
            onnxFloatBuf.put(reusableVadInputBuffer, 0, vadInputLen)
            onnxFloatBuf.rewind()
            try {
                val stateTensor = OnnxTensor.createTensor(ortEnvironment, state)
                OnnxTensor.createTensor(
                    ortEnvironment,
                    onnxFloatBuf,
                    longArrayOf(1, vadInputLen.toLong())
                ).use { inputTensor ->
                    try {
                        val inputs =
                            mapOf("input" to inputTensor, "sr" to srTensor, "state" to stateTensor)
                        session.run(inputs).use { result ->
                            val score =
                                ((result[0].value as? Array<*>)?.firstOrNull() as? FloatArray)?.firstOrNull()
                                    ?: ((result[0].value as? FloatArray)?.firstOrNull())
                                    ?: 0.0f
                            val newState = castToStateArray(result[1].value)
                            this.state = newState
                            val startIdx = i + windowSize - contextSize
                            if (startIdx >= 0) System.arraycopy(
                                audioFloats,
                                startIdx,
                                vadContext!!,
                                0,
                                contextSize
                            )
                            val currentSamplePosition = i
                            if (score >= SPEECH_THRESHOLD && currentSpeechStart == null) {
                                currentSpeechStart = currentSamplePosition
                            } else if (currentSpeechStart != null && score < SPEECH_THRESHOLD) {
                                speechSegments.add(
                                    SpeechTimestamp(
                                        start = currentSpeechStart!!,
                                        end = currentSamplePosition
                                    )
                                )
                                currentSpeechStart = null
                            }
                        }
                    } finally {
                        try {
                            stateTensor.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: OrtException) {
                Timber.e(e, "Error during ONNX session run")
                return emptyList()
            }
            i += windowSize
        }
        if (currentSpeechStart != null) {
            speechSegments.add(SpeechTimestamp(start = currentSpeechStart, end = audioFloatCount))
        }
        return speechSegments
    }
    private fun mergeTimestamps(
        timestamps: List<SpeechTimestamp>, paddingMs: Int, sampleRate: Int, totalSamples: Int
    ): List<SpeechTimestamp> {
        if (timestamps.isEmpty()) return emptyList()
        val paddingSamples = (paddingMs / 1000f * sampleRate).toInt()
        val mergeGapSamples = sampleRate * 2
        val merged = mutableListOf<SpeechTimestamp>()
        var currentSegment = timestamps.first().copy()
        for (i in 1 until timestamps.size) {
            val nextTimestamp = timestamps[i]
            val gap = nextTimestamp.start - currentSegment.end
            if (gap < mergeGapSamples) {
                currentSegment.end = nextTimestamp.end
            } else {
                merged.add(currentSegment)
                currentSegment = nextTimestamp.copy()
            }
        }
        merged.add(currentSegment)
        return merged.map {
            val start = (it.start - paddingSamples).coerceAtLeast(0)
            val end = (it.end + paddingSamples).coerceAtMost(totalSamples)
            it.copy(start = start, end = end)
        }
    }
    @Suppress("UNCHECKED_CAST")
    private fun castToStateArray(value: Any?): Array<Array<FloatArray>>? {
        if (value !is Array<*> || value.any { it !is Array<*> }) return null
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
        val resultStream = ByteArrayOutputStream()
        segments.forEach { segment ->
            val startByte = (segment.start * scaleFactor).toInt() * bytesPerSample
            val endByte = (segment.end * scaleFactor).toInt() * bytesPerSample
            val lengthInBytes = endByte - startByte
            if (lengthInBytes > 0 && startByte + lengthInBytes <= originalBuffer.capacity()) {
                val segmentBytes = ByteArray(lengthInBytes)
                originalBuffer.position(startByte)
                originalBuffer.get(segmentBytes)
                resultStream.write(segmentBytes)
            } else if (lengthInBytes < 0) {
                Timber.e("Stitch error: calculated segment has negative length.")
            }
        }
        return resultStream.toByteArray()
    }
}
