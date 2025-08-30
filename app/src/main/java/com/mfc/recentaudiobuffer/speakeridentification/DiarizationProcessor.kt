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
    companion object {
        // Match the model's expectation of 1.5s
        const val MIN_SEGMENT_DURATION_SEC = 1.5f
        // Process in chunks up to 3s to get varied speech
        const val MAX_SEGMENT_DURATION_SEC = 3.0f
        // Overlap when splitting long segments to avoid cutting words
        const val SEGMENT_OVERLAP_WINDOW_S = 0.1f

        const val MERGE_GAP_MS = 150 // Tighter gap to prevent merging different speakers
        const val PADDING_MS = 50
        const val IS_SPEECH_THRESHOLD = 0.3f
    }

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

        // 1. Get all speech timestamps from VAD
        val speechTimestamps = vadProcessor.getSpeechTimestamps(
            fullBuffer,
            audioConfig,
            paddingMs = PADDING_MS,
            mergeGapMs = MERGE_GAP_MS,
            speechThreshold = IS_SPEECH_THRESHOLD
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

        // 2. Process each individual speech segment
        for (segment in speechTimestamps.take(200)) { // Limit segments per file
            coroutineContext.ensureActive()
            val startVad = segment.start
            val endVad = segment.end
            val durationSec = (endVad - startVad).toFloat() / vadSampleRate

            // 3. IMPORTANT: Only process segments that are long enough on their own
            if (durationSec < MIN_SEGMENT_DURATION_SEC) {
                continue // Skip short segments entirely
            }
            validSegmentsCount++

            val startSampleOriginal = floor(startVad * scaleFactor).toInt()
            val endSampleOriginal = ceil(endVad * scaleFactor).toInt()

            // Split the chunk if it's longer than our max duration
            val subSegments = splitLongSegment(startSampleOriginal, endSampleOriginal, audioConfig.sampleRateHz)

            for ((subStart, subEnd) in subSegments) {
                coroutineContext.ensureActive()
                val startByte = subStart * bytesPerSample
                val endByte = subEnd * bytesPerSample

                if (startByte < 0 || endByte > originalArray.size || startByte >= endByte) continue

                val segmentBytes = originalArray.copyOfRange(startByte, endByte)
                if (segmentBytes.isEmpty()) continue

                try {
                    val segmentEmbedding = speakerIdentifier.generateEmbedding(segmentBytes)
                    if (segmentEmbedding.isNotEmpty()) {
                        val bestKnownSimilarity = allKnownSpeakers.maxOfOrNull { speaker ->
                            speakerIdentifier.calculateCosineSimilarity(segmentEmbedding, speaker.embedding)
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

        Timber.d("VAD produced ${speechTimestamps.size} segments, ${validSegmentsCount} were long enough for processing.")
        Timber.d("Diarization for $fileUriString complete. Found ${unidentified.size} unidentified segments.")
        return@withContext unidentified
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

