package com.mfc.recentaudiobuffer.speakeridentification

import com.mfc.recentaudiobuffer.AudioConfig
import com.mfc.recentaudiobuffer.VADProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

data class UnidentifiedSegment(
    val fileUriString: String,
    val startOffsetBytes: Int,
    val endOffsetBytes: Int,
    val embedding: SpeakerEmbedding,
    val originalSampleRate: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UnidentifiedSegment

        if (startOffsetBytes != other.startOffsetBytes) return false
        if (endOffsetBytes != other.endOffsetBytes) return false
        if (originalSampleRate != other.originalSampleRate) return false
        if (fileUriString != other.fileUriString) return false
        if (!embedding.contentEquals(other.embedding)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = startOffsetBytes
        result = 31 * result + endOffsetBytes
        result = 31 * result + originalSampleRate
        result = 31 * result + fileUriString.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

@Singleton
class DiarizationProcessor @Inject constructor(
    private val speakerIdentifier: SpeakerIdentifier, private val vadProcessor: VADProcessor
) {

    // Update the companion object constants in DiarizationProcessor:
    companion object {
        // Lowered to 1.0s to capture more speakers, but still filter very short noise
        const val MIN_SEGMENT_DURATION_SEC = 1.0f  // Was 1.5f

        // Process in chunks up to 3s to get varied speech
        const val MAX_SEGMENT_DURATION_SEC = 3.0f

        // Overlap when splitting long segments to avoid cutting words
        const val SEGMENT_OVERLAP_WINDOW_S = 0.15f  // Increased from 0.1f for better continuity

        // Tighter VAD parameters to filter out non-speech
        const val MERGE_GAP_MS = 200  // Increased from 150 to allow natural pauses
        const val PADDING_MS = 100    // Increased from 50 for better context
        const val IS_SPEECH_THRESHOLD = 0.55f  // RAISED from 0.3f to filter kitchen noise

        // NEW: Energy threshold to filter out quiet/noise segments
        const val MIN_SPEECH_ENERGY_RMS = 0.002f  // Minimum RMS energy for speech
    }

    // Add this helper function after the companion object:
    private fun calculateRMS(audioBytes: ByteArray): Float {
        if (audioBytes.isEmpty() || audioBytes.size % 2 != 0) return 0f

        var sumOfSquares = 0.0
        val byteBuffer = java.nio.ByteBuffer.wrap(audioBytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val shortBuffer = byteBuffer.asShortBuffer()
        val sampleCount = shortBuffer.remaining()

        for (i in 0 until sampleCount) {
            val sample = shortBuffer.get(i).toFloat() / 32768.0f
            sumOfSquares += (sample * sample).toDouble()
        }

        return kotlin.math.sqrt(sumOfSquares / sampleCount).toFloat()
    }

    // Replace the process function with this enhanced version:
    suspend fun process(
        fullBuffer: ByteBuffer,
        fileUriString: String,
        allKnownSpeakers: List<Speaker>,
        audioConfig: AudioConfig
    ): List<UnidentifiedSegment> = withContext(Dispatchers.Default) {
        if (!fullBuffer.hasRemaining()) return@withContext emptyList()
        Timber.d("Starting diarization for file: $fileUriString")

        val vadSampleRate = if (audioConfig.sampleRateHz >= VADProcessor.VAD_MAX_SAMPLE_RATE) {
            VADProcessor.VAD_MAX_SAMPLE_RATE
        } else {
            VADProcessor.VAD_MIN_SAMPLE_RATE
        }

        // 1. Get all speech timestamps from VAD with stricter threshold
        val speechTimestamps = vadProcessor.getSpeechTimestamps(
            fullBuffer,
            audioConfig,
            paddingMs = PADDING_MS,
            mergeGapMs = MERGE_GAP_MS,
            speechThreshold = IS_SPEECH_THRESHOLD  // Now 0.55f instead of 0.3f
        )

        if (speechTimestamps.isEmpty()) {
            Timber.d("No speech found by VAD in $fileUriString.")
            return@withContext emptyList()
        }

        val bytesPerSample = audioConfig.bitDepth.bits / 8
        val originalArray = fullBuffer.array()
        val unidentified = mutableListOf<UnidentifiedSegment>()
        val scaleFactor = audioConfig.sampleRateHz.toDouble() / vadSampleRate.toDouble()
        var validSegmentsCount = 0
        var rejectedForEnergy = 0
        var rejectedForDuration = 0

        // Track adjacent short segments for concatenation
        val shortSegmentsBuffer = mutableListOf<VADProcessor.SpeechTimestamp>()

        // 2. Process each speech segment with enhanced filtering
        for ((index, segment) in speechTimestamps.withIndex()) {
            coroutineContext.ensureActive()

            val startVad = segment.start
            val endVad = segment.end
            val durationSec = (endVad - startVad).toFloat() / vadSampleRate

            // Handle short segments by trying to concatenate with nearby segments
            if (durationSec < MIN_SEGMENT_DURATION_SEC) {
                shortSegmentsBuffer.add(segment)

                // Check if we should process accumulated short segments
                val totalDuration = shortSegmentsBuffer.sumOf { segment ->
                    ((segment.end - segment.start).toFloat() / vadSampleRate).toDouble()
                }.toFloat()

                // If we have enough accumulated duration OR this is the last segment
                if (totalDuration >= MIN_SEGMENT_DURATION_SEC || index == speechTimestamps.size - 1) {
                    // Process concatenated segments
                    val concatenatedSegment = processConcatenatedSegments(
                        shortSegmentsBuffer,
                        vadSampleRate,
                        scaleFactor,
                        bytesPerSample,
                        originalArray,
                        fileUriString,
                        audioConfig,
                        allKnownSpeakers
                    )
                    concatenatedSegment?.let { unidentified.add(it) }
                    shortSegmentsBuffer.clear()
                }

                rejectedForDuration++
                continue
            }

            // Clear any remaining short segments buffer when we hit a long segment
            shortSegmentsBuffer.clear()

            validSegmentsCount++

            val startSampleOriginal = floor(startVad * scaleFactor).toInt()
            val endSampleOriginal = ceil(endVad * scaleFactor).toInt()

            // Split the chunk if it's longer than our max duration
            val subSegments =
                splitLongSegment(startSampleOriginal, endSampleOriginal, audioConfig.sampleRateHz)

            for ((subStart, subEnd) in subSegments) {
                coroutineContext.ensureActive()
                val startByte = subStart * bytesPerSample
                val endByte = subEnd * bytesPerSample

                if (startByte < 0 || endByte > originalArray.size || startByte >= endByte) continue

                val segmentBytes = originalArray.copyOfRange(startByte, endByte)
                if (segmentBytes.isEmpty()) continue

                // NEW: Check audio energy to filter out non-speech noise
                val rms = calculateRMS(segmentBytes)
                if (rms < MIN_SPEECH_ENERGY_RMS) {
                    rejectedForEnergy++
                    Timber.d("Rejected segment with low energy: RMS=$rms")
                    continue
                }

                try {
                    val segmentEmbedding = speakerIdentifier.generateEmbedding(segmentBytes)
                    if (segmentEmbedding.isNotEmpty()) {
                        val bestKnownSimilarity = allKnownSpeakers.maxOfOrNull { speaker ->
                            speakerIdentifier.calculateCosineSimilarity(
                                segmentEmbedding,
                                speaker.embedding
                            )
                        } ?: 0f

                        if (bestKnownSimilarity <= SpeakerIdentifier.SIMILARITY_THRESHOLD) {
                            unidentified.add(
                                UnidentifiedSegment(
                                    fileUriString = fileUriString,
                                    startOffsetBytes = startByte,
                                    endOffsetBytes = endByte,
                                    embedding = segmentEmbedding,
                                    originalSampleRate = audioConfig.sampleRateHz
                                )
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Failed to generate embedding for segment")
                }
            }
        }

        Timber.d("VAD produced ${speechTimestamps.size} segments, $validSegmentsCount were long enough for processing.")
        Timber.d("Rejected $rejectedForDuration segments for duration, $rejectedForEnergy for low energy.")
        Timber.d("Diarization for $fileUriString complete. Found ${unidentified.size} unidentified segments.")
        return@withContext unidentified
    }

    // NEW: Function to handle concatenated short segments
    private suspend fun processConcatenatedSegments(
        segments: List<VADProcessor.SpeechTimestamp>,
        vadSampleRate: Int,
        scaleFactor: Double,
        bytesPerSample: Int,
        originalArray: ByteArray,
        fileUriString: String,
        audioConfig: AudioConfig,
        allKnownSpeakers: List<Speaker>
    ): UnidentifiedSegment? {
        if (segments.isEmpty()) return null

        // Find the overall span of these segments
        val firstStart = segments.first().start
        val lastEnd = segments.last().end

        // Convert to original sample rate
        val startSampleOriginal = floor(firstStart * scaleFactor).toInt()
        val endSampleOriginal = ceil(lastEnd * scaleFactor).toInt()

        val startByte = startSampleOriginal * bytesPerSample
        val endByte = endSampleOriginal * bytesPerSample

        if (startByte < 0 || endByte > originalArray.size || startByte >= endByte) return null

        val concatenatedBytes = originalArray.copyOfRange(startByte, endByte)

        // Check energy
        val rms = calculateRMS(concatenatedBytes)
        if (rms < MIN_SPEECH_ENERGY_RMS) {
            Timber.d("Concatenated segments rejected for low energy: RMS=$rms")
            return null
        }

        return try {
            val embedding = speakerIdentifier.generateEmbedding(concatenatedBytes)
            if (embedding.isNotEmpty()) {
                val bestKnownSimilarity = allKnownSpeakers.maxOfOrNull { speaker ->
                    speakerIdentifier.calculateCosineSimilarity(embedding, speaker.embedding)
                } ?: 0f

                if (bestKnownSimilarity <= SpeakerIdentifier.SIMILARITY_THRESHOLD) {
                    UnidentifiedSegment(
                        fileUriString = fileUriString,
                        startOffsetBytes = startByte,
                        endOffsetBytes = endByte,
                        embedding = embedding,
                        originalSampleRate = audioConfig.sampleRateHz
                    )
                } else null
            } else null
        } catch (e: Exception) {
            Timber.w(e, "Failed to generate embedding for concatenated segments")
            null
        }
    }

    private fun splitLongSegment(
        startSample: Int, endSample: Int, sampleRate: Int
    ): List<Pair<Int, Int>> {
        val segments = mutableListOf<Pair<Int, Int>>()
        val totalSamples = endSample - startSample
        val totalDurationSec = totalSamples.toFloat() / sampleRate

        if (totalDurationSec <= MAX_SEGMENT_DURATION_SEC) {
            segments.add(Pair(startSample, endSample))
            return segments
        }

        val maxSamplesPerSegment = (MAX_SEGMENT_DURATION_SEC * sampleRate).toInt()
        val overlapSamples = (SEGMENT_OVERLAP_WINDOW_S * sampleRate).toInt()
        val stepSize = maxSamplesPerSegment - overlapSamples

        if (stepSize <= 0) {
            segments.add(Pair(startSample, endSample))
            return segments
        }

        var currentStart = startSample
        while (currentStart < endSample) {
            val currentEnd = min(currentStart + maxSamplesPerSegment, endSample)
            segments.add(Pair(currentStart, currentEnd))
            if (currentEnd == endSample) break
            currentStart += stepSize
        }
        return segments
    }
}

