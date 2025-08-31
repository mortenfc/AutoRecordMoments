package com.mfc.recentaudiobuffer.speakeridentification

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Module
@InstallIn(SingletonComponent::class)
abstract class IdentifierModule {
    @Binds
    @Singleton
    abstract fun bindSpeakerIdentifier(
        onnxIdentifier: OnnxSpeakerIdentifier
    ): SpeakerIdentifier
}

typealias SpeakerEmbedding = FloatArray

interface SpeakerIdentifier {
    companion object {
        const val SIMILARITY_THRESHOLD = 0.75f
    }

    suspend fun identifySpeaker(audioChunk: ByteArray, knownSpeakers: List<Speaker>): String?
    suspend fun createEmbedding(audioChunks: List<ByteArray>): SpeakerEmbedding
    suspend fun generateEmbedding(audioChunk: ByteArray): SpeakerEmbedding
    fun calculateCosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float
    fun averageEmbeddings(embeddings: List<SpeakerEmbedding>): SpeakerEmbedding
}

@Singleton
class OnnxSpeakerIdentifier @Inject constructor(
    @ApplicationContext private val context: Context
) : SpeakerIdentifier, AutoCloseable {

    companion object {
        // CRITICAL: Must match the ONNX model's exported input size.
        private const val SAMPLE_RATE = 16000
        private const val DURATION_SECONDS = 1.5f
        private const val REQUIRED_SAMPLES = (SAMPLE_RATE * DURATION_SECONDS).toInt() // 24000

        // Minimum audio quality thresholds
        private const val MIN_RMS_ENERGY = 0.002f

        // Reject any chunk that is less than X% of the required duration
        // (67% of 1.5s is 1.0 seconds)
        private const val MIN_REQUIRED_SAMPLES_RATIO = 0.67f
    }

    private val ortEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession by lazy { createONNXSession() }

    private fun createONNXSession(): OrtSession {
        val modelBytes = context.assets.open("speaker_embedding.onnx").readBytes()
        return ortEnvironment.createSession(modelBytes)
    }

    override suspend fun createEmbedding(audioChunks: List<ByteArray>): SpeakerEmbedding =
        withContext(Dispatchers.Default) {
            if (audioChunks.isEmpty()) return@withContext floatArrayOf()
            val embeddings = audioChunks.map { generateEmbedding(it) }.filter { it.isNotEmpty() }
            return@withContext averageEmbeddings(embeddings)
        }

    override fun averageEmbeddings(embeddings: List<SpeakerEmbedding>): SpeakerEmbedding {
        val nonEmptyEmbeddings = embeddings.filter { it.isNotEmpty() }
        if (nonEmptyEmbeddings.isEmpty()) return floatArrayOf()

        val first = nonEmptyEmbeddings.first()
        val averagedEmbedding = FloatArray(first.size)

        for (embedding in nonEmptyEmbeddings) {
            if (embedding.size == first.size) {
                for (i in averagedEmbedding.indices) {
                    averagedEmbedding[i] += embedding[i]
                }
            }
        }

        for (i in averagedEmbedding.indices) {
            averagedEmbedding[i] /= nonEmptyEmbeddings.size
        }

        val norm = sqrt(averagedEmbedding.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) {
            for (i in averagedEmbedding.indices) {
                averagedEmbedding[i] /= norm
            }
        }

        return averagedEmbedding
    }

    override suspend fun identifySpeaker(
        audioChunk: ByteArray, knownSpeakers: List<Speaker>
    ): String? = withContext(Dispatchers.Default) {
        if (knownSpeakers.isEmpty() || audioChunk.isEmpty()) return@withContext null

        val newEmbedding = generateEmbedding(audioChunk)
        if (newEmbedding.isEmpty()) return@withContext null

        return@withContext knownSpeakers.asSequence().map { speaker ->
            val similarity = calculateCosineSimilarity(newEmbedding, speaker.embedding)
            speaker.id to similarity
        }.maxByOrNull { it.second }?.let { (id, similarity) ->
            Timber.d("Top speaker match: ID $id with similarity $similarity")
            if (similarity > SpeakerIdentifier.SIMILARITY_THRESHOLD) id else null
        }
    }

    override suspend fun generateEmbedding(audioChunk: ByteArray): SpeakerEmbedding =
        withContext(Dispatchers.Default) {
            // 1. Convert byte array to float array
            val floatBuffer =
                ByteBuffer.wrap(audioChunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val floatArray = FloatArray(floatBuffer.remaining()) {
                floatBuffer.get().toFloat() / 32768.0f
            }

            // 2. Hard reject chunks that are way too short
            if (floatArray.size < REQUIRED_SAMPLES * MIN_REQUIRED_SAMPLES_RATIO) {
                Timber.w("Audio chunk too short to process: ${floatArray.size} samples.")
                return@withContext floatArrayOf()
            }

            // 3. Check for sufficient energy (i.e., not silence)
            val rms = sqrt(floatArray.sumOf { (it * it).toDouble() } / floatArray.size).toFloat()
            if (rms < MIN_RMS_ENERGY) {
                return@withContext floatArrayOf() // Not enough energy
            }

            // 4. Pad or trim to the exact required sample size
            val standardizedArray = FloatArray(REQUIRED_SAMPLES)
            if (floatArray.size >= REQUIRED_SAMPLES) {
                // If longer, take the center part
                val start = (floatArray.size - REQUIRED_SAMPLES) / 2
                System.arraycopy(floatArray, start, standardizedArray, 0, REQUIRED_SAMPLES)
            } else {
                // If shorter, pad with silence in the center
                val start = (REQUIRED_SAMPLES - floatArray.size) / 2
                System.arraycopy(floatArray, 0, standardizedArray, start, floatArray.size)
            }

            // 5. Normalize audio (mean and variance) as expected by the pyannote model
            val mean = standardizedArray.average().toFloat()
            val std = sqrt(standardizedArray.map { (it - mean) * (it - mean) }.average()).toFloat()
            if (std > 1e-8) { // Avoid division by zero
                for (i in standardizedArray.indices) {
                    standardizedArray[i] = (standardizedArray[i] - mean) / std
                }
            }

            // 6. Run inference through the ONNX model
            val inputTensor = OnnxTensor.createTensor(
                ortEnvironment,
                FloatBuffer.wrap(standardizedArray),
                longArrayOf(1, standardizedArray.size.toLong())
            )

            inputTensor.use {
                session.run(mapOf("input_values" to inputTensor)).use { results ->
                    val embedding = (results[0].value as Array<FloatArray>)[0]

                    // 7. L2 normalize the output embedding vector
                    val norm = sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
                    if (norm > 0) {
                        for (i in embedding.indices) {
                            embedding[i] /= norm
                        }
                    }
                    return@withContext embedding
                }
            }
        }

    override fun calculateCosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.isEmpty() || vecB.isEmpty() || vecA.size != vecB.size) return 0.0f
        var dotProduct = 0.0f
        for (i in vecA.indices) {
            dotProduct += vecA[i] * vecB[i]
        }
        return dotProduct.coerceIn(-1.0f, 1.0f)
    }

    override fun close() {
        session.close()
    }
}
