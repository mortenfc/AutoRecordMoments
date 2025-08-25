package com.mfc.recentaudiobuffer.speakeridentification

import com.mfc.recentaudiobuffer.AudioConfig
import com.mfc.recentaudiobuffer.VADProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Represents a segment of speech that could not be matched to a known speaker.
 * It holds the raw embedding and location data needed for later clustering.
 */
data class UnidentifiedSegment(
    val fileUriString: String,
    val startOffsetBytes: Int,
    val endOffsetBytes: Int,
    val embedding: SpeakerEmbedding
)

@Singleton
class DiarizationProcessor @Inject constructor(
    private val speakerIdentifier: SpeakerIdentifier,
    private val vadProcessor: VADProcessor
) {
    /**
     * Processes a raw audio buffer to find speech segments and identify which ones
     * belong to known speakers. For segments that don't match, it extracts their
     * embedding and location data for later processing.
     *
     * @param fullBuffer The complete audio data for a single recording.
     * @param fileUriString A unique identifier for the audio file being processed.
     * @param allKnownSpeakers The list of already enrolled speakers.
     * @param audioConfig The audio configuration for interpreting the byte buffer.
     * @return A list of [UnidentifiedSegment] objects, each representing a piece of speech
     * from an unknown speaker, ready for global clustering.
     */
    suspend fun process(
        fullBuffer: ByteBuffer,
        fileUriString: String,
        allKnownSpeakers: List<Speaker>,
        audioConfig: AudioConfig
    ): List<UnidentifiedSegment> = withContext(Dispatchers.Default) {
        if (!fullBuffer.hasRemaining()) return@withContext emptyList()

        Timber.d("Starting diarization for file: $fileUriString")

        // 1. Determine the sample rate the VAD will use.
        val vadSampleRate = if (audioConfig.sampleRateHz >= VADProcessor.VAD_MAX_SAMPLE_RATE) {
            VADProcessor.VAD_MAX_SAMPLE_RATE
        } else {
            VADProcessor.VAD_MIN_SAMPLE_RATE
        }

        // 2. Use VAD to get speech timestamps. These timestamps are relative to the vadSampleRate.
        val mergedTimestamps = vadProcessor.getSpeechTimestamps(
            fullBuffer,
            audioConfig,
            mergeGapMs = VADProcessor.DEFAULT_MERGE_GAP_MS
        )

        if (mergedTimestamps.isEmpty()) {
            Timber.d("No speech found by VAD in $fileUriString.")
            return@withContext emptyList()
        }

        val bytesPerSample = audioConfig.bitDepth.bits / 8
        val originalArray = fullBuffer.array()
        val unidentified = mutableListOf<UnidentifiedSegment>()

        // 3. FIX: Calculate the scaling factor to convert VAD timestamps back to the original sample rate.
        val scaleFactor = audioConfig.sampleRateHz.toDouble() / vadSampleRate.toDouble()

        // 4. Process each merged speech segment.
        for (segment in mergedTimestamps) {
            // 5. Scale the VAD's sample-based timestamps to match the original audio's timeline.
            val startSampleOriginal = floor(segment.start * scaleFactor).toInt()
            val endSampleOriginal = ceil(segment.end * scaleFactor).toInt()

            val startByte = startSampleOriginal * bytesPerSample
            val endByte = endSampleOriginal * bytesPerSample

            if (startByte < 0 || endByte > originalArray.size || startByte >= endByte) continue

            val segmentBytes = originalArray.copyOfRange(startByte, endByte)
            if (segmentBytes.isEmpty()) continue

            val segmentEmbedding = speakerIdentifier.generateEmbedding(segmentBytes)

            val knownMatch = allKnownSpeakers.maxByOrNull { speaker ->
                speakerIdentifier.calculateCosineSimilarity(segmentEmbedding, speaker.embedding)
            }

            val bestKnownSimilarity = if (knownMatch != null) {
                speakerIdentifier.calculateCosineSimilarity(segmentEmbedding, knownMatch.embedding)
            } else 0f

            if (bestKnownSimilarity <= SpeakerIdentifier.SIMILARITY_THRESHOLD) {
                unidentified.add(
                    UnidentifiedSegment(
                        fileUriString = fileUriString,
                        startOffsetBytes = startByte,
                        endOffsetBytes = endByte,
                        embedding = segmentEmbedding
                    )
                )
            }
        }

        Timber.d("Diarization for $fileUriString complete. Found ${unidentified.size} unidentified segments.")
        return@withContext unidentified
    }
}
