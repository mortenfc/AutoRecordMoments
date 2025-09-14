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
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.system.measureNanoTime

/**
 * VADProcessor — Restored two-parameter (mergeGapMs, paddingMs) system for predictable control,
 * while keeping the high-quality crossfade stitching.
 */
@Singleton
class VADProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) : AutoCloseable {
    companion object {
        private const val VAD_MAX_SAMPLE_RATE = 16000
        private const val VAD_MIN_SAMPLE_RATE = 8000

        // TUNINGS:
        const val DEFAULT_PADDING_MS = 500
        const val DEFAULT_MERGE_GAP_MS = 1500

        private const val SPEECH_THRESHOLD = 0.2f
        private const val DEFAULT_CHUNK_SIZE_B = 4096
        const val USE_PARALLEL_PIPELINE = true

        // Linear is safe for upsampling (ratio > 1.0) and mild downsampling (ratio >= 0.6)
        // Use sinc only for strong downsampling (< 0.6) to prevent aliasing.
        const val LINEAR_VS_SINC_RATIO = 0.6
    }

    private fun calculateDefaultPoolSize(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores <= 2 -> 2
            cores <= 4 -> 4
            else -> min(cores - 1, 8)
        }
    }

    private val stateLock = Any()

    private val poolSize = calculateDefaultPoolSize()

    data class SpeechTimestamp(val start: Int, var end: Int)
    data class BenchmarkResult(val avgMs: Double, val avgAllocBytes: Long)

    private var state: Array<Array<FloatArray>>? = null
    private var vadContext: FloatArray? = null

    private val ortEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession by lazy { createONNXSession() }

    private val maxVadInputLen = 64 + 512
    private val onnxByteBuf: ByteBuffer =
        ByteBuffer.allocateDirect(maxVadInputLen * 4).order(ByteOrder.nativeOrder())
    private val onnxFloatBuf: FloatBuffer = onnxByteBuf.asFloatBuffer()

    private val reusableFloatBuffer = FloatArray(DEFAULT_CHUNK_SIZE_B)
    private val reusableResampledBufferSize =
        ceil(DEFAULT_CHUNK_SIZE_B * (VAD_MAX_SAMPLE_RATE.toDouble() / VAD_MIN_SAMPLE_RATE)).toInt()
    private val reusableResampledBuffer = FloatArray(reusableResampledBufferSize)
    private val reusableVadInputBuffer = FloatArray(64 + 512)

    // Thread-safe progress tracking
    private val lastReportedProgressPercent = AtomicInteger(-1)

    private fun createONNXSession(): OrtSession {
        val modelBytes = context.assets.open("silero_vad.onnx").readBytes()
        try {
            val sessionOptions = OrtSession.SessionOptions()
            try {
                sessionOptions.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
            } catch (e: Exception) {
                sessionOptions.addNnapi()
            }
            return ortEnvironment.createSession(modelBytes, sessionOptions)
        } catch (e: Exception) {
            Timber.w(e, "Failed to create ONNX session with NNAPI. Falling back to CPU provider.")
        }
        return ortEnvironment.createSession(modelBytes)
    }

    private fun resetStates() {
        state = arrayOf(Array(1) { FloatArray(128) }).plus(arrayOf(Array(1) { FloatArray(128) }))
        vadContext = null
        lastReportedProgressPercent.set(-1)
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
        mergeGapMs: Int = DEFAULT_MERGE_GAP_MS,
        debugFileBaseName: String? = null,
        onProgress: ((Float) -> Unit)? = null,
        useParallel: Boolean = USE_PARALLEL_PIPELINE
    ): ByteArray {
        // Input validation
        require(paddingMs >= 0) { "paddingMs must be non-negative" }
        require(mergeGapMs >= 0) { "mergeGapMs must be non-negative" }
        require(config.sampleRateHz > 0) { "Sample rate must be positive" }
        require(config.bitDepth.bits in listOf(8, 16)) { "Only 8-bit and 16-bit audio supported" }

        resetStates()
        debugFileBaseName?.let {
            FileSavingUtils.saveDebugFile(context, "${it}_01_original.wav", fullAudioBuffer, config)
        }

        val targetVADRate = if (config.sampleRateHz >= VAD_MAX_SAMPLE_RATE) {
            VAD_MAX_SAMPLE_RATE
        } else {
            VAD_MIN_SAMPLE_RATE
        }
        val allTimestamps = mutableListOf<SpeechTimestamp>()
        var totalResampledSamples = 0
        val readBuffer = ByteArray(DEFAULT_CHUNK_SIZE_B)
        fullAudioBuffer.rewind()
        val totalBytes = fullAudioBuffer.limit().toFloat()
        onProgress?.invoke(0f)
        // Pre-create srTensor once for this process call (unchanging)
        val srTensor = OnnxTensor.createTensor(ortEnvironment, longArrayOf(targetVADRate.toLong()))
        // Channels/pool variables for finally-closing if necessary
        var availableIndices: Channel<Int>? = null
        var dataChannel: Channel<Pair<Int, Int>>? = null
        try {
            if (useParallel) {
                // Pool-based pipeline
                val pool = Array(poolSize) { FloatArray(reusableResampledBufferSize) }
                availableIndices = Channel(capacity = Channel.UNLIMITED)
                dataChannel = Channel(capacity = Channel.UNLIMITED)
                runBlocking {
                    // Fill available indices
                    for (i in 0 until poolSize) availableIndices.send(i)
                    val producer = launch(Dispatchers.Default) {
                        try {
                            while (fullAudioBuffer.hasRemaining()) {
                                val toRead = minOf(readBuffer.size, fullAudioBuffer.remaining())
                                fullAudioBuffer.get(readBuffer, 0, toRead)
                                val floatCount = bytesToFloats(
                                    readBuffer, toRead, config.bitDepth.bits, reusableFloatBuffer
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
                                if (currentProgressPercent > lastReportedProgressPercent.get()) {
                                    onProgress?.invoke(currentProgress)
                                    lastReportedProgressPercent.set(currentProgressPercent)
                                }
                            }
                        } finally {
                            dataChannel.close()
                        }
                    }
                    val consumer = launch(Dispatchers.Default) {
                        var absolutePosition = 0
                        for ((idx, count) in dataChannel) {
                            val bufferSlot = pool[idx]
                            // Process sequentially with ONNX while preserving state
                            val timestampsInChunk = getSpeechTimestampsFromBuffer(
                                bufferSlot,
                                count,
                                targetVADRate,
                                srTensor,
                                absolutePosition  // Pass absolute position
                            )
                            // Add the timestamps to the collection!
                            allTimestamps.addAll(timestampsInChunk)
                            // Timestamps are already absolute, no need to adjust
                            absolutePosition += count
                            // Return slot to pool
                            availableIndices.send(idx)
                        }
                        totalResampledSamples = absolutePosition
                    }
                    producer.join()
                    consumer.join()
                }
            } else {
                while (fullAudioBuffer.hasRemaining()) {
                    val toRead = minOf(readBuffer.size, fullAudioBuffer.remaining())
                    fullAudioBuffer.get(readBuffer, 0, toRead)
                    val floatCount =
                        bytesToFloats(readBuffer, toRead, config.bitDepth.bits, reusableFloatBuffer)
                    val ratio = targetVADRate.toDouble() / config.sampleRateHz.toDouble()
                    val resampledCount = resampleAdaptive(
                        reusableFloatBuffer,
                        floatCount,
                        reusableResampledBuffer,
                        reusableResampledBuffer.size,
                        ratio
                    )
                    val timestampsInChunk = getSpeechTimestampsFromBuffer(
                        reusableResampledBuffer,
                        resampledCount,
                        targetVADRate,
                        srTensor,
                        totalResampledSamples
                    )
                    allTimestamps.addAll(timestampsInChunk)
                    totalResampledSamples += resampledCount
                    val currentProgress = fullAudioBuffer.position() / totalBytes
                    val currentProgressPercent = (currentProgress * 100).toInt()
                    if (currentProgressPercent > lastReportedProgressPercent.get()) {
                        onProgress?.invoke(currentProgress)
                        lastReportedProgressPercent.set(currentProgressPercent)
                    }
                }
            }
        } finally {
            try {
                srTensor.close()
            } catch (_: Exception) {
            }
            // Ensure channels closed if something went wrong
            try {
                dataChannel?.cancel()
            } catch (_: Exception) {
            }
            try {
                availableIndices?.cancel()
            } catch (_: Exception) {
            }
        }

        val mergedTimestamps = mergeTimestamps(
            allTimestamps, paddingMs, mergeGapMs, targetVADRate, totalResampledSamples
        )
        val bufferForStitching = fullAudioBuffer.asReadOnlyBuffer()
        val finalResultBytes = stitchAudioWithCrossfade(
            bufferForStitching, mergedTimestamps, config, targetVADRate.toFloat(), paddingMs
        )
        debugFileBaseName?.let {
            FileSavingUtils.saveDebugFile(
                context, "${it}_03_final_result.wav", ByteBuffer.wrap(finalResultBytes), config
            )
        }

        return finalResultBytes
    }

    /**
     * Very fast linear resampler.
     */
    private fun resampleLinear(
        src: FloatArray, srcLen: Int, dst: FloatArray, dstCapacity: Int, ratio: Double
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
                val piX = (PI * x).toFloat()
                // Sinc function: sin(pi*x*2*fc) / (pi*x)
                val sinc = (sin(piX * 2.0f * fc.toFloat()) / piX)
                // Hann window
                val window = (0.5f + 0.5f * cos(piX / halfTaps.toFloat()))
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
        src: FloatArray, srcLen: Int, dst: FloatArray, dstCapacity: Int, ratio: Double
    ): Int {
        return if (ratio >= LINEAR_VS_SINC_RATIO) { // Use linear for upsampling or mild downsampling
            resampleLinear(src, srcLen, dst, dstCapacity, ratio)
        } else { // Use sinc for significant downsampling to prevent aliasing
            resampleSinc(src, srcLen, dst, dstCapacity, ratio, taps = 24)
        }
    }

    /**
     * Simple micro-benchmark to measure processing time and approximate allocation delta.
     */
    fun measureProcessingMsForBuffer(
        inputBuffer: ByteBuffer, config: AudioConfig, runs: Int = 3, warmup: Int = 1
    ): BenchmarkResult {
        if (runs <= 0) throw IllegalArgumentException("runs must be > 0")
        val copy = ByteArray(inputBuffer.limit())
        inputBuffer.rewind()
        inputBuffer.get(copy)
        val wrapper = ByteBuffer.wrap(copy)
        // Warmup
        repeat(warmup) {
            try {
                process(wrapper.duplicate().asReadOnlyBuffer(), config, onProgress = null)
            } catch (_: Exception) {
            }
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
        source: ByteArray, bytesToRead: Int, bitsPerSample: Int, dest: FloatArray
    ): Int {
        val byteBuffer = ByteBuffer.wrap(source, 0, bytesToRead).order(ByteOrder.LITTLE_ENDIAN)
        return when (bitsPerSample) {
            16 -> {
                val shortBuffer = byteBuffer.asShortBuffer()
                val count = shortBuffer.remaining()
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
        srTensor: OnnxTensor,
        absoluteOffset: Int = 0
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
                synchronized(stateLock) {
                    OnnxTensor.createTensor(ortEnvironment, state)
                }.use { stateTensor ->
                    OnnxTensor.createTensor(
                        ortEnvironment, onnxFloatBuf, longArrayOf(1, vadInputLen.toLong())
                    ).use { inputTensor ->
                        val inputs =
                            mapOf("input" to inputTensor, "sr" to srTensor, "state" to stateTensor)
                        session.run(inputs).use { result ->
                            val score =
                                ((result[0].value as? Array<*>)?.firstOrNull() as? FloatArray)?.firstOrNull()
                                    ?: ((result[0].value as? FloatArray)?.firstOrNull()) ?: 0.0f
                            val newState = castToStateArray(result[1].value)
                            synchronized(stateLock) {
                                if (newState != null) this.state = newState
                                else Timber.e("ONNX returned unexpected state shape; keeping previous state.")
                            }

                            // Use the actual window position for speech detection
                            val currentSamplePosition = i + absoluteOffset

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

                            val contextUpdateStart = i + windowSize - contextSize
                            if (contextUpdateStart >= 0 && contextUpdateStart + contextSize <= audioFloatCount) {
                                System.arraycopy(
                                    audioFloats,
                                    contextUpdateStart,
                                    vadContext!!,
                                    0,
                                    contextSize
                                )
                            }
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
            // Use the last processed position
            speechSegments.add(
                SpeechTimestamp(
                    start = currentSpeechStart,
                    end = (i + absoluteOffset)  // i is now at the position after last window
                )
            )
        }
        return speechSegments
    }

    /**
     * The robust, three-pass merging function using separate mergeGap and padding values.
     */
    private fun mergeTimestamps(
        timestamps: List<SpeechTimestamp>,
        paddingMs: Int,
        mergeGapMs: Int,
        sampleRate: Int,
        totalSamples: Int
    ): List<SpeechTimestamp> {
        if (timestamps.isEmpty()) return emptyList()

        val clampedPaddingMs = paddingMs.coerceAtLeast(0)
        val paddingSamples = (clampedPaddingMs / 1000f * sampleRate).toInt()
        val mergeGapSamples = (mergeGapMs.coerceAtLeast(0) / 1000f * sampleRate).toInt()

        // First, merge close segments using mergeGapSamples
        val prelimMerged = mutableListOf<SpeechTimestamp>()
        var current = timestamps.first().copy()
        for (i in 1 until timestamps.size) {
            val next = timestamps[i]
            val gap = next.start - current.end
            if (gap <= mergeGapSamples) {
                current.end = next.end
            } else {
                prelimMerged.add(current)
                current = next.copy()
            }
        }
        prelimMerged.add(current)

        // Apply padding, then merge any resulting overlapping intervals
        val padded = prelimMerged.map {
            val start = (it.start - paddingSamples).coerceAtLeast(0)
            val end = (it.end + paddingSamples).coerceAtMost(totalSamples)
            SpeechTimestamp(start = start, end = end)
        }.sortedBy { it.start }

        if (padded.isEmpty()) return emptyList()

        val finalMerged = mutableListOf<SpeechTimestamp>()
        var cur = padded.first().copy()
        for (i in 1 until padded.size) {
            val nxt = padded[i]
            if (nxt.start <= cur.end) {
                cur.end = maxOf(cur.end, nxt.end)
            } else {
                finalMerged.add(cur)
                cur = nxt.copy()
            }
        }
        finalMerged.add(cur)

        return finalMerged
    }

    /**
     * Helper function to perform a linear crossfade between two audio chunks.
     */
    private fun crossfade(
        prevTail: ByteArray, nextHead: ByteArray, overlapSamples: Int, bits: Int
    ): ByteArray {
        val bps = bits / 8
        val resultStream = ByteArrayOutputStream(overlapSamples * bps)
        val fadeSamples = overlapSamples.coerceAtLeast(1)
        val denom = if (fadeSamples > 1) (fadeSamples - 1).toFloat() else 1.0f

        for (s in 0 until overlapSamples) {
            val t = s.toFloat() / denom
            val fadeOut = 1.0f - t
            val fadeIn = t

            when (bits) {
                16 -> {
                    val pIdx = s * bps
                    val nIdx = s * bps

                    // Little-endian assemble with masking to avoid sign-extension issues
                    val pLo = prevTail[pIdx].toInt() and 0xFF
                    val pHi = prevTail[pIdx + 1].toInt() and 0xFF
                    val pSampleRaw = (pLo or (pHi shl 8))
                    val pSample = if (pSampleRaw >= 0x8000) pSampleRaw - 0x10000 else pSampleRaw

                    val nLo = nextHead[nIdx].toInt() and 0xFF
                    val nHi = nextHead[nIdx + 1].toInt() and 0xFF
                    val nSampleRaw = (nLo or (nHi shl 8))
                    val nSample = if (nSampleRaw >= 0x8000) nSampleRaw - 0x10000 else nSampleRaw

                    val mixed = (pSample * fadeOut + nSample * fadeIn).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

                    resultStream.write(mixed and 0xFF)
                    resultStream.write((mixed shr 8) and 0xFF)
                }

                8 -> {
                    val pNorm = (prevTail[s].toInt() and 0xFF) - 128
                    val nNorm = (nextHead[s].toInt() and 0xFF) - 128
                    val mixed = (pNorm * fadeOut + nNorm * fadeIn).toInt() + 128
                    resultStream.write(mixed.coerceIn(0, 255))
                }
            }
        }
        return resultStream.toByteArray()
    }

    /**
     * Stitches audio segments together with crossfading.
     * Fixed implementation that properly handles overlaps and maintains buffer position.
     */
    private fun stitchAudioWithCrossfade(
        originalBuffer: ByteBuffer,
        segments: List<SpeechTimestamp>,
        config: AudioConfig,
        vadSampleRate: Float,
        paddingMs: Int
    ): ByteArray {
        if (segments.isEmpty()) return ByteArray(0)

        val bytesPerSample = config.bitDepth.bits / 8
        val originalSampleRate = config.sampleRateHz
        val scaleFactor = originalSampleRate.toDouble() / vadSampleRate
        val resultStream = ByteArrayOutputStream()

        val crossfadeSamples = (paddingMs * originalSampleRate / 1000).coerceAtLeast(0)

        // Save original buffer position
        val originalPosition = originalBuffer.position()

        try {
            // Extract audio chunks
            val audioChunks = segments.mapNotNull { segment ->
                // --- stitchAudioWithCrossfade: use rounding when mapping samples to bytes ---
                val startSample = kotlin.math.floor(segment.start * scaleFactor).toInt()
                val endSample = ceil(segment.end * scaleFactor).toInt()
                val startByte = startSample * bytesPerSample
                val endByte = endSample * bytesPerSample
                val lengthInBytes = endByte - startByte

                if (lengthInBytes <= 0 || startByte < 0 || endByte > originalBuffer.limit()) {
                    null
                } else {
                    ByteArray(lengthInBytes).also {
                        originalBuffer.position(startByte)
                        originalBuffer.get(it)
                    }
                }
            }

            if (audioChunks.isEmpty()) return ByteArray(0)
            if (audioChunks.size == 1) return audioChunks.first()

            // Process first chunk
            var previousChunk = audioChunks.first()

            for (i in 1 until audioChunks.size) {
                val currentChunk = audioChunks[i]
                val samplesInPrev = previousChunk.size / bytesPerSample
                val samplesInCurrent = currentChunk.size / bytesPerSample
                val overlapSamples = minOf(crossfadeSamples, samplesInPrev, samplesInCurrent)

                if (overlapSamples > 0) {
                    // Write the non-overlapping part of previous chunk
                    val prevBodySize = previousChunk.size - (overlapSamples * bytesPerSample)
                    if (prevBodySize > 0) {
                        resultStream.write(previousChunk, 0, prevBodySize)
                    }

                    // Extract overlapping parts
                    val prevTail = ByteArray(overlapSamples * bytesPerSample)
                    System.arraycopy(previousChunk, prevBodySize, prevTail, 0, prevTail.size)

                    val currentHead = ByteArray(overlapSamples * bytesPerSample)
                    System.arraycopy(currentChunk, 0, currentHead, 0, currentHead.size)

                    // Write crossfaded junction
                    val mixedJunction =
                        crossfade(prevTail, currentHead, overlapSamples, config.bitDepth.bits)
                    resultStream.write(mixedJunction)

                    // Prepare next iteration: current chunk minus its head
                    val remainingSize = currentChunk.size - (overlapSamples * bytesPerSample)
                    if (remainingSize > 0) {
                        previousChunk = ByteArray(remainingSize)
                        System.arraycopy(
                            currentChunk,
                            overlapSamples * bytesPerSample,
                            previousChunk,
                            0,
                            remainingSize
                        )
                    } else {
                        previousChunk = ByteArray(0)
                    }
                } else {
                    // No overlap possible, just write previous chunk
                    resultStream.write(previousChunk)
                    previousChunk = currentChunk
                }
            }

            // Write the remaining part of the last chunk
            if (previousChunk.isNotEmpty()) {
                resultStream.write(previousChunk)
            }

            return resultStream.toByteArray()
        } finally {
            // Restore original buffer position
            originalBuffer.position(originalPosition)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun castToStateArray(value: Any?): Array<Array<FloatArray>>? {
        if (value !is Array<*> || value.any { it !is Array<*> }) return null
        return value as? Array<Array<FloatArray>>
    }

    override fun close() {
        try {
            session.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing ONNX session")
        }
        // Don't close ortEnvironment - it's a singleton managed by OrtEnvironment itself!
        // Closing it would break any other components using ONNX Runtime in the app
    }
}