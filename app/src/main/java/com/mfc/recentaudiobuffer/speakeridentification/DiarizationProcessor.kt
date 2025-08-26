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
    val originalSampleRate: Int  // Add this to track sample rate
)

@Singleton
class DiarizationProcessor @Inject constructor(
    private val speakerIdentifier: SpeakerIdentifier,
    private val vadProcessor: VADProcessor
) {
    companion object {
        // Minimum segment duration for reliable embeddings (in seconds)
        const val MIN_SEGMENT_DURATION_SEC = 1.0f
        // Maximum segment duration to avoid multiple speakers - increased from 3.0f
        const val MAX_SEGMENT_DURATION_SEC = 5.0f
        // Overlap threshold for splitting segments
        const val SEGMENT_OVERLAP_THRESHOLD = 0.25f
    }

    suspend fun process(
        fullBuffer: ByteBuffer,
        fileUriString: String,
        allKnownSpeakers: List<Speaker>,
        audioConfig: AudioConfig
    ): List<UnidentifiedSegment> = withContext(Dispatchers.Default) {
        if (!fullBuffer.hasRemaining()) return@withContext emptyList()

        Timber.d("Starting diarization for file: $fileUriString")

        // Check buffer size to avoid processing huge files
        val bufferSize = fullBuffer.remaining()
        if (bufferSize > 100_000_000) { // 100MB limit
            Timber.w("File too large for diarization: ${bufferSize / 1_000_000}MB")
            return@withContext emptyList()
        }

        // 1. Determine the sample rate the VAD will use
        val vadSampleRate = if (audioConfig.sampleRateHz >= VADProcessor.VAD_MAX_SAMPLE_RATE) {
            VADProcessor.VAD_MAX_SAMPLE_RATE
        } else {
            VADProcessor.VAD_MIN_SAMPLE_RATE
        }

        // 2. Get speech timestamps with appropriate parameters for diarization
        // Use smaller merge gap to avoid combining different speakers
        val mergedTimestamps = vadProcessor.getSpeechTimestamps(
            fullBuffer,
            audioConfig,
            paddingMs = 100,  // Less padding to avoid overlap
            mergeGapMs = 200  // Smaller gap to keep speakers separate
        )

        if (mergedTimestamps.isEmpty()) {
            Timber.d("No speech found by VAD in $fileUriString.")
            return@withContext emptyList()
        }

        val bytesPerSample = audioConfig.bitDepth.bits / 8
        val originalArray = fullBuffer.array()
        val unidentified = mutableListOf<UnidentifiedSegment>()

        // Calculate scaling factor
        val scaleFactor = audioConfig.sampleRateHz.toDouble() / vadSampleRate.toDouble()

        Timber.d("VAD sample rate: $vadSampleRate, Original sample rate: ${audioConfig.sampleRateHz}")
        Timber.d("Scale factor: $scaleFactor")

        // Limit number of segments to process to avoid memory issues
        val segmentsToProcess = mergedTimestamps.take(50)

        // Process each merged speech segment
        for (segment in segmentsToProcess) {
            // Scale VAD timestamps back to original sample rate
            val startSampleOriginal = floor(segment.start * scaleFactor).toInt()
            val endSampleOriginal = ceil(segment.end * scaleFactor).toInt()

            // Debug assertion: verify duration consistency
            val vadDurationSec = (segment.end - segment.start).toFloat() / vadSampleRate
            val originalDurationSec = (endSampleOriginal - startSampleOriginal).toFloat() / audioConfig.sampleRateHz
            val durationDifference = kotlin.math.abs(vadDurationSec - originalDurationSec)

            Timber.d("Segment: VAD duration=${vadDurationSec}s, Scaled duration=${originalDurationSec}s, Diff=${durationDifference}s")

            // Assert durations match within a small tolerance (1ms)
            if (durationDifference > 0.001f) {
                Timber.e("Duration mismatch! VAD: ${vadDurationSec}s, Scaled: ${originalDurationSec}s")
                assert(false) {
                    "Timestamp scaling error: VAD duration ($vadDurationSec) != Scaled duration ($originalDurationSec)"
                }
            }

            // Calculate duration in seconds
            val durationSec = (endSampleOriginal - startSampleOriginal).toFloat() / audioConfig.sampleRateHz

            // Split long segments to avoid multiple speakers
            val subSegments = if (durationSec > MAX_SEGMENT_DURATION_SEC) {
                splitLongSegment(startSampleOriginal, endSampleOriginal, audioConfig.sampleRateHz)
            } else if (durationSec < MIN_SEGMENT_DURATION_SEC) {
                // Try to extend short segments if possible
                listOf(extendShortSegment(
                    startSampleOriginal,
                    endSampleOriginal,
                    originalArray.size / bytesPerSample,
                    audioConfig.sampleRateHz
                ))
            } else {
                listOf(Pair(startSampleOriginal, endSampleOriginal))
            }

            // Process each sub-segment
            for ((subStart, subEnd) in subSegments) {
                // Check for cancellation before processing
                coroutineContext.ensureActive()

                val startByte = subStart * bytesPerSample
                val endByte = subEnd * bytesPerSample

                if (startByte < 0 || endByte > originalArray.size || startByte >= endByte) continue

                // Limit segment size to avoid memory issues
                val maxSegmentBytes = 10_000_000 // 10MB max per segment
                val actualEndByte = min(endByte, startByte + maxSegmentBytes)

                val segmentBytes = originalArray.copyOfRange(startByte, actualEndByte)
                if (segmentBytes.isEmpty()) continue

                try {
                    // Check again right before the expensive operation
                    coroutineContext.ensureActive()

                    val segmentEmbedding = speakerIdentifier.generateEmbedding(segmentBytes)

                    // Check against known speakers
                    val bestKnownSimilarity = allKnownSpeakers.maxOfOrNull { speaker ->
                        speakerIdentifier.calculateCosineSimilarity(segmentEmbedding, speaker.embedding)
                    } ?: 0f

                    if (bestKnownSimilarity <= SpeakerIdentifier.SIMILARITY_THRESHOLD) {
                        unidentified.add(
                            UnidentifiedSegment(
                                fileUriString = fileUriString,
                                startOffsetBytes = startByte,
                                endOffsetBytes = actualEndByte,
                                embedding = segmentEmbedding,
                                originalSampleRate = audioConfig.sampleRateHz
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    // Propagate cancellation properly
                    throw e
                } catch (e: OutOfMemoryError) {
                    Timber.e("OOM generating embedding, skipping segment")
                    System.gc()
                    break // Stop processing this file
                } catch (e: Exception) {
                    Timber.w(e, "Failed to generate embedding for segment")
                }
            }
        }

        Timber.d("Diarization for $fileUriString complete. Found ${unidentified.size} unidentified segments.")
        return@withContext unidentified
    }

    private fun splitLongSegment(
        startSample: Int,
        endSample: Int,
        sampleRate: Int
    ): List<Pair<Int, Int>> {
        val segments = mutableListOf<Pair<Int, Int>>()
        if (startSample >= endSample) return segments

        val maxSamplesPerSegment = (MAX_SEGMENT_DURATION_SEC * sampleRate).toInt()
        val overlapSamples = (SEGMENT_OVERLAP_THRESHOLD * sampleRate).toInt()
        val stepSize = maxSamplesPerSegment - overlapSamples

        // Safety check: if overlap is >= max duration, stepSize would be <= 0, causing a loop.
        // In this case, we just return the original segment to avoid crashing.
        if (stepSize <= 0) {
            segments.add(Pair(startSample, endSample))
            return segments
        }

        var currentStart = startSample
        while (true) {
            val currentEnd = min(currentStart + maxSamplesPerSegment, endSample)
            segments.add(Pair(currentStart, currentEnd))

            // If the segment we just created reaches the very end, we are done.
            if (currentEnd == endSample) {
                break
            }

            // Advance the start of the next window by the step size. This guarantees progress.
            currentStart += stepSize
        }

        return segments
    }


    private fun extendShortSegment(
        startSample: Int,
        endSample: Int,
        maxSamples: Int,
        sampleRate: Int
    ): Pair<Int, Int> {
        val targetSamples = (MIN_SEGMENT_DURATION_SEC * sampleRate).toInt()
        val currentSamples = endSample - startSample
        val neededSamples = targetSamples - currentSamples

        if (neededSamples <= 0) return Pair(startSample, endSample)

        val paddingSamples = neededSamples / 2
        val newStart = (startSample - paddingSamples).coerceAtLeast(0)
        val newEnd = (endSample + paddingSamples).coerceAtMost(maxSamples)

        return Pair(newStart, newEnd)
    }
}
