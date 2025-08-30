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
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.floor
import kotlin.system.measureNanoTime

/**
 * Buffer pool for efficient memory reuse in audio processing
 */
class BufferPool(private val maxPoolSize: Int = 10) {
    private val floatPool = Collections.synchronizedList(mutableListOf<FloatArray>())
    private val bytePool = Collections.synchronizedList(mutableListOf<ByteArray>())
    
    fun acquireFloatArray(size: Int): FloatArray {
        synchronized(floatPool) {
            val index = floatPool.indexOfFirst { it.size >= size }
            return if (index >= 0) {
                floatPool.removeAt(index)
            } else {
                FloatArray(size)
            }
        }
    }

    fun releaseFloatArray(buffer: FloatArray) {
        synchronized(floatPool) {
            if (floatPool.size < maxPoolSize) {
                floatPool.add(buffer)
            }
        }
    }

    fun acquireByteArray(size: Int): ByteArray {
        synchronized(bytePool) {
            val index = bytePool.indexOfFirst { it.size >= size }
            return if (index >= 0) {
                bytePool.removeAt(index)
            } else {
                ByteArray(size)
            }
        }
    }

    fun releaseByteArray(buffer: ByteArray) {
        synchronized(bytePool) {
            if (bytePool.size < maxPoolSize) {
                bytePool.add(buffer)
            }
        }
    }

    fun clear() {
        synchronized(floatPool) {
            floatPool.clear()
        }
        synchronized(bytePool) {
            bytePool.clear()
        }
    }
}

/**
 * Data class for streaming results
 */
data class ProcessedSegment(
    val audioData: ByteArray,
    val offsetBytes: Long,
    val timestamps: List<VADProcessor.SpeechTimestamp>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ProcessedSegment
        if (!audioData.contentEquals(other.audioData)) return false
        if (offsetBytes != other.offsetBytes) return false
        if (timestamps != other.timestamps) return false
        return true
    }

    override fun hashCode(): Int {
        var result = audioData.contentHashCode()
        result = 31 * result + offsetBytes.hashCode()
        result = 31 * result + timestamps.hashCode()
        return result
    }
}

/**
 * VADProcessor — Restored two-parameter (mergeGapMs, paddingMs) system for predictable control,
 * while keeping the high-quality crossfade stitching and adding enhanced memory management.
 */
@Singleton
class VADProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) : AutoCloseable {
    companion object {
        const val VAD_MAX_SAMPLE_RATE = 16000
        const val VAD_MIN_SAMPLE_RATE = 8000

        // TUNINGS:
        const val DEFAULT_PADDING_MS = 500
        const val DEFAULT_MERGE_GAP_MS = 1500

        private const val SPEECH_THRESHOLD = 0.25f
        private const val DEFAULT_CHUNK_SIZE_B = 4096
        const val USE_PARALLEL_PIPELINE = true

        // Linear is safe for upsampling (ratio > 1.0) and mild downsampling (ratio >= 0.6)
        // Use sinc only for strong downsampling (< 0.6) to prevent aliasing.
        const val LINEAR_VS_SINC_RATIO = 0.6

        private const val STREAMING_WINDOW_SIZE_SAMPLES = 512
        private const val STREAMING_SILENCE_CHUNKS_THRESHOLD = 4
    }

    private fun calculateDefaultPoolSize(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores <= 2 -> 2
            cores <= 4 -> 4
            else -> min(cores - 1, 8)
        }
    }

    private val batchStateLock = Any()
    private val poolSize = calculateDefaultPoolSize()
    private val bufferPool = BufferPool()

    data class SpeechTimestamp(val start: Int, var end: Int)
    data class BenchmarkResult(val avgMs: Double, val avgAllocBytes: Long)

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

    // Batch processing state
    private var batchState: Array<Array<FloatArray>>? = null
    private var batchVadContext: FloatArray? = null

    // Streaming state
    private val streamingStateLock = Any()
    private var streamingState: Array<Array<FloatArray>>? = null
    private var streamingVadContext: FloatArray? = null
    private var internalAudioBuffer = FloatArray(STREAMING_WINDOW_SIZE_SAMPLES * 2)
    private var internalBufferFill = 0
    private var consecutiveSilenceChunks = 0
    private var isCurrentlySpeaking = false

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

    /**
     * Resets the state for the BATCH processing function (`process`).
     */
    private fun resetBatchStates() {
        synchronized(batchStateLock) {
            batchState =
                arrayOf(Array(1) { FloatArray(128) }).plus(arrayOf(Array(1) { FloatArray(128) }))
            batchVadContext = null
        }
        lastReportedProgressPercent.set(-1)
    }

    /**
     * Resets the state for the STREAMING function (`isSpeech`).
     */
    fun resetStreamingState() {
        synchronized(streamingStateLock) {
            streamingState =
                arrayOf(Array(1) { FloatArray(128) }).plus(arrayOf(Array(1) { FloatArray(128) }))
            streamingVadContext = null
            internalBufferFill = 0
            consecutiveSilenceChunks = 0
            isCurrentlySpeaking = false
            Timber.d("Streaming VAD state has been reset.")
        }
    }

    /**
     * Enhanced streaming VAD with proper synchronization
     */
    fun isSpeech(audioChunk: ByteArray): Boolean {
        val floatBuffer = bufferPool.acquireFloatArray(audioChunk.size / 2)
        
        return try {
            val floatCount = bytesToFloats(audioChunk, audioChunk.size, 16, floatBuffer)
            
            synchronized(streamingStateLock) {
                // Ensure buffer capacity
                if (internalBufferFill + floatCount > internalAudioBuffer.size) {
                    val newBuffer = FloatArray((internalBufferFill + floatCount) * 2)
                    System.arraycopy(internalAudioBuffer, 0, newBuffer, 0, internalBufferFill)
                    internalAudioBuffer = newBuffer
                }
                
                // Add new audio data to internal buffer
                System.arraycopy(
                    floatBuffer, 0, internalAudioBuffer, internalBufferFill, floatCount
                )
                internalBufferFill += floatCount
                
                // Process windows
                while (internalBufferFill >= STREAMING_WINDOW_SIZE_SAMPLES) {
                    val window = internalAudioBuffer.copyOfRange(0, STREAMING_WINDOW_SIZE_SAMPLES)
                    val speechConfidence = runStreamingVadOnWindow(window)
                    
                    if (speechConfidence >= SPEECH_THRESHOLD) {
                        consecutiveSilenceChunks = 0
                        isCurrentlySpeaking = true
                    } else {
                        consecutiveSilenceChunks++
                        if (consecutiveSilenceChunks >= STREAMING_SILENCE_CHUNKS_THRESHOLD) {
                            isCurrentlySpeaking = false
                        }
                    }
                    
                    // Shift buffer
                    internalBufferFill -= STREAMING_WINDOW_SIZE_SAMPLES
                    if (internalBufferFill > 0) {
                        System.arraycopy(
                            internalAudioBuffer,
                            STREAMING_WINDOW_SIZE_SAMPLES,
                            internalAudioBuffer,
                            0,
                            internalBufferFill
                        )
                    }
                }
                
                isCurrentlySpeaking
            }
        } finally {
            bufferPool.releaseFloatArray(floatBuffer)
        }
    }

    private fun runStreamingVadOnWindow(audioFloats: FloatArray): Float {
        val sampleRate = VAD_MAX_SAMPLE_RATE
        val windowSize = STREAMING_WINDOW_SIZE_SAMPLES
        val contextSize = 64
        var speechScore = 0.0f

        synchronized(streamingStateLock) {
            if (streamingVadContext == null) streamingVadContext = FloatArray(contextSize)

            System.arraycopy(streamingVadContext!!, 0, reusableVadInputBuffer, 0, contextSize)
            System.arraycopy(audioFloats, 0, reusableVadInputBuffer, contextSize, windowSize)

            onnxFloatBuf.rewind()
            onnxFloatBuf.put(reusableVadInputBuffer, 0, contextSize + windowSize).rewind()

            try {
                OnnxTensor.createTensor(ortEnvironment, streamingState).use { stateTensor ->
                    OnnxTensor.createTensor(ortEnvironment, longArrayOf(sampleRate.toLong()))
                        .use { srTensor ->
                            OnnxTensor.createTensor(
                                ortEnvironment,
                                onnxFloatBuf,
                                longArrayOf(1, (contextSize + windowSize).toLong())
                            ).use { inputTensor ->
                                val inputs = mapOf(
                                    "input" to inputTensor, "sr" to srTensor, "state" to stateTensor
                                )
                                session.run(inputs).use { result ->
                                    speechScore =
                                        ((result[0].value as? Array<*>)?.firstOrNull() as? FloatArray)?.firstOrNull()
                                            ?: 0.0f
                                    val newState = castToStateArray(result[1].value)
                                    if (newState != null) this.streamingState = newState
                                }
                            }
                        }
                }
            } catch (e: OrtException) {
                Timber.e(e, "Error during streaming ONNX session run")
                return 0.0f
            }

            val contextUpdateStart = windowSize - contextSize
            System.arraycopy(audioFloats, contextUpdateStart, streamingVadContext!!, 0, contextSize)
        }
        return speechScore
    }

    /**
     * Public processing function with enhanced memory management
     */
    suspend fun process(
        fullAudioBuffer: ByteBuffer,
        config: AudioConfig,
        paddingMs: Int = DEFAULT_PADDING_MS,
        mergeGapMs: Int = DEFAULT_MERGE_GAP_MS,
        debugFileBaseName: String? = null,
        onProgress: ((Float) -> Unit)? = null,
        useParallel: Boolean = USE_PARALLEL_PIPELINE,
        speechThreshold: Float = SPEECH_THRESHOLD,
    ): ByteArray {
        val speechTimestamps = getSpeechTimestamps(
            fullAudioBuffer,
            config,
            paddingMs,
            mergeGapMs,
            onProgress,
            useParallel,
            speechThreshold = speechThreshold
        )
        val bufferForStitching = fullAudioBuffer.asReadOnlyBuffer()
        val finalResultBytes = stitchAudioWithCrossfade(
            bufferForStitching, speechTimestamps, config, VAD_MAX_SAMPLE_RATE.toFloat(), paddingMs
        )
        debugFileBaseName?.let {
            FileSavingUtils.saveDebugFile(
                context, "${it}_03_final_result.wav", ByteBuffer.wrap(finalResultBytes), config
            )
        }
        return finalResultBytes
    }

    /**
     * Process large audio files with streaming to avoid memory issues
     */
    suspend fun processLargeFile(
        fileUri: Uri,
        config: AudioConfig,
        paddingMs: Int = DEFAULT_PADDING_MS,
        mergeGapMs: Int = DEFAULT_MERGE_GAP_MS,
        onProgress: ((Float) -> Unit)? = null
    ): Flow<ProcessedSegment> = flow {
        val chunkSizeBytes = 1024 * 1024 * 2 // 2MB chunks
        var processedBytes = 0L
        var totalBytes = 0L
        
        context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
            // Skip WAV header
            val headerBytes = ByteArray(44)
            inputStream.read(headerBytes)
            
            totalBytes = inputStream.available().toLong()
            
            val buffer = bufferPool.acquireByteArray(chunkSizeBytes)
            val overlapBuffer = ByteArray(config.sampleRateHz * 2) // 1 second overlap
            var overlapSize = 0
            
            try {
                while (true) {
                    val bytesToRead = chunkSizeBytes - overlapSize
                    val bytesRead = inputStream.read(buffer, overlapSize, bytesToRead)
                    if (bytesRead <= 0) break
                    
                    if (overlapSize > 0) {
                        System.arraycopy(overlapBuffer, 0, buffer, 0, overlapSize)
                    }
                    
                    val totalChunkSize = overlapSize + bytesRead
                    val chunkBuffer = ByteBuffer.wrap(buffer, 0, totalChunkSize)
                    
                    val speechTimestamps = getSpeechTimestamps(
                        chunkBuffer,
                        config,
                        paddingMs,
                        mergeGapMs,
                        useParallel = false
                    )
                    
                    if (speechTimestamps.isNotEmpty()) {
                        val processedAudio = stitchAudioWithCrossfade(
                            chunkBuffer,
                            speechTimestamps,
                            config,
                            VAD_MAX_SAMPLE_RATE.toFloat(),
                            paddingMs
                        )
                        
                        emit(ProcessedSegment(
                            audioData = processedAudio,
                            offsetBytes = processedBytes,
                            timestamps = speechTimestamps
                        ))
                    }
                    
                    overlapSize = min(totalChunkSize / 4, overlapBuffer.size)
                    System.arraycopy(buffer, totalChunkSize - overlapSize, overlapBuffer, 0, overlapSize)
                    
                    processedBytes += bytesRead
                    onProgress?.invoke(processedBytes.toFloat() / totalBytes)
                }
            } finally {
                bufferPool.releaseByteArray(buffer)
            }
        } ?: throw IOException("Cannot open input stream for $fileUri")
    }

    /**
     * Get speech timestamps with enhanced parallel processing
     */
    suspend fun getSpeechTimestamps(
        fullAudioBuffer: ByteBuffer,
        config: AudioConfig,
        paddingMs: Int = DEFAULT_PADDING_MS,
        mergeGapMs: Int = DEFAULT_MERGE_GAP_MS,
        onProgress: ((Float) -> Unit)? = null,
        useParallel: Boolean = USE_PARALLEL_PIPELINE,
        speechThreshold: Float = SPEECH_THRESHOLD,
    ): List<SpeechTimestamp> = coroutineScope {
        require(paddingMs >= 0) { "paddingMs must be non-negative" }
        require(mergeGapMs >= 0) { "mergeGapMs must be non-negative" }
        require(config.sampleRateHz > 0) { "Sample rate must be positive" }
        require(config.bitDepth.bits in listOf(8, 16)) { "Only 8-bit and 16-bit audio supported" }

        resetBatchStates()

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
        val srTensor = OnnxTensor.createTensor(ortEnvironment, longArrayOf(targetVADRate.toLong()))
        
        try {
            if (useParallel) {
                val chunks = mutableListOf<ByteArray>()
                while (fullAudioBuffer.hasRemaining()) {
                    val toRead = minOf(readBuffer.size, fullAudioBuffer.remaining())
                    val chunk = ByteArray(toRead)
                    fullAudioBuffer.get(chunk)
                    chunks.add(chunk)
                }

                val deferredResults = chunks.mapIndexed { index, chunkData ->
                    async(Dispatchers.Default) {
                        ensureActive()
                        val localFloatBuffer = bufferPool.acquireFloatArray(DEFAULT_CHUNK_SIZE_B)
                        val localResampledBuffer = bufferPool.acquireFloatArray(reusableResampledBufferSize)

                        try {
                            val floatCount = bytesToFloats(
                                chunkData,
                                chunkData.size,
                                config.bitDepth.bits,
                                localFloatBuffer
                            )
                            val ratio = targetVADRate.toDouble() / config.sampleRateHz.toDouble()
                            val resampledCount = resampleAdaptive(
                                localFloatBuffer,
                                floatCount,
                                localResampledBuffer,
                                localResampledBuffer.size,
                                ratio
                            )

                            Triple(
                                index,
                                localResampledBuffer.copyOf(resampledCount),
                                resampledCount
                            )
                        } finally {
                            bufferPool.releaseFloatArray(localFloatBuffer)
                            bufferPool.releaseFloatArray(localResampledBuffer)
                        }
                    }
                }

                val resampledChunks = deferredResults.awaitAll().sortedBy { it.first }
                var absolutePosition = 0
                resampledChunks.forEachIndexed { index, (_, bufferSlot, count) ->
                    ensureActive()
                    val timestampsInChunk = getSpeechTimestampsFromBuffer(
                        bufferSlot,
                        count,
                        targetVADRate,
                        srTensor,
                        absolutePosition,
                        speechThreshold = speechThreshold
                    )
                    allTimestamps.addAll(timestampsInChunk)
                    absolutePosition += count

                    val currentProgress = (index + 1).toFloat() / chunks.size
                    val currentProgressPercent = (currentProgress * 100).toInt()
                    if (currentProgressPercent > lastReportedProgressPercent.get()) {
                        onProgress?.invoke(currentProgress)
                        lastReportedProgressPercent.set(currentProgressPercent)
                    }
                }
                totalResampledSamples = absolutePosition

            } else { // Sequential processing
                while (fullAudioBuffer.hasRemaining()) {
                    ensureActive()
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
                        totalResampledSamples,
                        speechThreshold = speechThreshold
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
            srTensor.close()
        }

        return@coroutineScope mergeTimestamps(
            allTimestamps,
            paddingMs,
            mergeGapMs,
            targetVADRate,
            totalResampledSamples
        )
    }

    /**
     * Enhanced resampleAudioChunk with buffer pooling
     */
    fun resampleAudioChunk(sourceChunk: ByteArray, sourceRate: Int, targetRate: Int): ByteArray {
        if (sourceRate == targetRate) return sourceChunk
        if (sourceChunk.isEmpty()) return ByteArray(0)
        
        val sourceFloatCount = sourceChunk.size / 2
        val ratio = targetRate.toDouble() / sourceRate.toDouble()
        val expectedOutputFloats = ceil(sourceFloatCount * ratio).toInt()
        
        val sourceFloatBuffer = bufferPool.acquireFloatArray(sourceFloatCount)
        val resampledFloatBuffer = bufferPool.acquireFloatArray(expectedOutputFloats)
        
        return try {
            val actualFloatCount = bytesToFloats(
                sourceChunk, 
                sourceChunk.size, 
                16, 
                sourceFloatBuffer
            )
            
            val resampledFloatCount = resampleAdaptive(
                sourceFloatBuffer,
                actualFloatCount,
                resampledFloatBuffer,
                resampledFloatBuffer.size,
                ratio
            )
            
            val destBytes = ByteArray(resampledFloatCount * 2)
            val byteBuffer = ByteBuffer.wrap(destBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until resampledFloatCount) {
                val sample = (resampledFloatBuffer[i] * 32767.0f)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                byteBuffer.putShort(sample.toShort())
            }
            destBytes
        } finally {
            bufferPool.releaseFloatArray(sourceFloatBuffer)
            bufferPool.releaseFloatArray(resampledFloatBuffer)
        }
    }

    /**
     * Very fast linear resampler
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
     * High-quality sinc resampler for downsampling
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

        val fc = 0.5 * ratio
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
                val sinc = (sin(piX * 2.0f * fc.toFloat()) / piX)
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
     * Adaptive resampler choosing best algorithm
     */
    private fun resampleAdaptive(
        src: FloatArray, srcLen: Int, dst: FloatArray, dstCapacity: Int, ratio: Double
    ): Int {
        return if (ratio >= LINEAR_VS_SINC_RATIO) {
            resampleLinear(src, srcLen, dst, dstCapacity, ratio)
        } else {
            resampleSinc(src, srcLen, dst, dstCapacity, ratio, taps = 24)
        }
    }

    /**
     * Benchmark utility
     */
    suspend fun measureProcessingMsForBuffer(
        inputBuffer: ByteBuffer, config: AudioConfig, runs: Int = 3, warmup: Int = 1
    ): BenchmarkResult {
        if (runs <= 0) throw IllegalArgumentException("runs must be > 0")
        val copy = ByteArray(inputBuffer.limit())
        inputBuffer.rewind()
        inputBuffer.get(copy)
        val wrapper = ByteBuffer.wrap(copy)
        
        repeat(warmup) {
            try {
                process(wrapper.duplicate().asReadOnlyBuffer(), config, onProgress = null)
            } catch (_: Exception) {
            }
        }
        
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
        absoluteOffset: Int = 0,
        speechThreshold: Float = SPEECH_THRESHOLD
    ): List<SpeechTimestamp> {
        val windowSize = if (sampleRate == VAD_MAX_SAMPLE_RATE) 512 else 256
        val contextSize = if (sampleRate == VAD_MAX_SAMPLE_RATE) 64 else 32
        val speechSegments = mutableListOf<SpeechTimestamp>()
        var currentSpeechStart: Int? = null
        val vadInputLen = contextSize + windowSize
        var i = 0

        while (i + windowSize <= audioFloatCount) {
            synchronized(batchStateLock) {
                if (batchVadContext == null) batchVadContext = FloatArray(contextSize)

                System.arraycopy(batchVadContext!!, 0, reusableVadInputBuffer, 0, contextSize)
                System.arraycopy(audioFloats, i, reusableVadInputBuffer, contextSize, windowSize)
                onnxFloatBuf.rewind()
                onnxFloatBuf.put(reusableVadInputBuffer, 0, vadInputLen).rewind()

                try {
                    OnnxTensor.createTensor(ortEnvironment, batchState).use { stateTensor ->
                        OnnxTensor.createTensor(
                            ortEnvironment,
                            onnxFloatBuf,
                            longArrayOf(1, vadInputLen.toLong())
                        ).use { inputTensor ->
                            val inputs = mapOf(
                                "input" to inputTensor,
                                "sr" to srTensor,
                                "state" to stateTensor
                            )
                            session.run(inputs).use { result ->
                                val score =
                                    ((result[0].value as? Array<*>)?.firstOrNull() as? FloatArray)?.firstOrNull()
                                        ?: 0.0f
                                val newState = castToStateArray(result[1].value)

                                if (newState != null) {
                                    this.batchState = newState
                                } else {
                                    Timber.e("ONNX returned unexpected state shape; keeping previous state.")
                                }

                                val currentSamplePosition = i + absoluteOffset
                                if (score >= speechThreshold && currentSpeechStart == null) {
                                    currentSpeechStart = currentSamplePosition
                                } else if (currentSpeechStart != null && score < speechThreshold) {
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
                                        batchVadContext!!,
                                        0,
                                        contextSize
                                    )
                                }
                            }
                        }
                    }
                } catch (e: OrtException) {
                    Timber.e(e, "Error during ONNX session run")
                }
            }
            i += windowSize
        }
        if (currentSpeechStart != null) {
            speechSegments.add(
                SpeechTimestamp(
                    start = currentSpeechStart,
                    end = (i + absoluteOffset)
                )
            )
        }
        return speechSegments
    }

    /**
     * The robust, three-pass merging function using separate mergeGap and padding values
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
     * Helper function to perform a linear crossfade between two audio chunks
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
     * Stitches audio segments together with crossfading
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

        val originalPosition = originalBuffer.position()

        try {
            val audioChunks = segments.mapNotNull { segment ->
                val startSample = floor(segment.start * scaleFactor).toInt()
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

            var previousChunk = audioChunks.first()

            for (i in 1 until audioChunks.size) {
                val currentChunk = audioChunks[i]
                val samplesInPrev = previousChunk.size / bytesPerSample
                val samplesInCurrent = currentChunk.size / bytesPerSample
                val overlapSamples = minOf(crossfadeSamples, samplesInPrev, samplesInCurrent)

                if (overlapSamples > 0) {
                    val prevBodySize = previousChunk.size - (overlapSamples * bytesPerSample)
                    if (prevBodySize > 0) {
                        resultStream.write(previousChunk, 0, prevBodySize)
                    }

                    val prevTail = ByteArray(overlapSamples * bytesPerSample)
                    System.arraycopy(previousChunk, prevBodySize, prevTail, 0, prevTail.size)

                    val currentHead = ByteArray(overlapSamples * bytesPerSample)
                    System.arraycopy(currentChunk, 0, currentHead, 0, currentHead.size)

                    val mixedJunction =
                        crossfade(prevTail, currentHead, overlapSamples, config.bitDepth.bits)
                    resultStream.write(mixedJunction)

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
                    resultStream.write(previousChunk)
                    previousChunk = currentChunk
                }
            }

            if (previousChunk.isNotEmpty()) {
                resultStream.write(previousChunk)
            }

            return resultStream.toByteArray()
        } finally {
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
            bufferPool.clear()
        } catch (e: Exception) {
            Timber.w(e, "Error closing ONNX session")
        }
        // Don't close ortEnvironment - it's a singleton managed by OrtEnvironment itself!
    }
}