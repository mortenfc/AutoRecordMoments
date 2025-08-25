package com.mfc.recentaudiobuffer.speakeridentification

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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

    /**
     * Identifies a speaker from an audio chunk by comparing it against a list of known speakers.
     *
     * @param audioChunk The raw audio data of the speech segment.
     * @param knownSpeakers The list of speakers to compare against.
     * @return The ID of the best matching speaker if similarity is above the threshold, otherwise null.
     */
    suspend fun identifySpeaker(audioChunk: ByteArray, knownSpeakers: List<Speaker>): String?

    suspend fun createEmbedding(audioChunks: List<ByteArray>): SpeakerEmbedding
    suspend fun generateEmbedding(audioChunk: ByteArray): SpeakerEmbedding
    fun calculateCosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float

    /**
     * A utility function to average a list of pre-computed embeddings.
     */
    fun averageEmbeddings(embeddings: List<SpeakerEmbedding>): SpeakerEmbedding
}

@Singleton
class OnnxSpeakerIdentifier @Inject constructor(
    @ApplicationContext private val context: Context
) : SpeakerIdentifier, AutoCloseable {

    private val ortEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession by lazy { createONNXSession() }

    private fun createONNXSession(): OrtSession {
        val modelBytes = context.assets.open("speaker_embedding.onnx").readBytes()
        return ortEnvironment.createSession(modelBytes)
    }

    override suspend fun createEmbedding(audioChunks: List<ByteArray>): SpeakerEmbedding =
        withContext(Dispatchers.Default) {
            if (audioChunks.isEmpty()) return@withContext floatArrayOf()
            val embeddings = audioChunks.map { generateEmbedding(it) }
            return@withContext averageEmbeddings(embeddings)
        }

    /**
     * Averages a list of existing embeddings into a single, representative embedding.
     * This is useful for creating a robust representation of a speaker cluster.
     */
    override fun averageEmbeddings(embeddings: List<SpeakerEmbedding>): SpeakerEmbedding {
        if (embeddings.isEmpty()) return floatArrayOf()
        val first = embeddings.first()
        if (first.isEmpty()) return floatArrayOf()

        val averagedEmbedding = FloatArray(first.size)
        var validEmbeddingsCount = 0
        for (embedding in embeddings) {
            // Ensure embeddings are of the same dimension before averaging
            if (embedding.size == first.size) {
                for (i in averagedEmbedding.indices) {
                    averagedEmbedding[i] += embedding[i]
                }
                validEmbeddingsCount++
            }
        }

        if (validEmbeddingsCount > 0) {
            for (i in averagedEmbedding.indices) {
                averagedEmbedding[i] /= validEmbeddingsCount
            }
        }
        return averagedEmbedding
    }


    override suspend fun identifySpeaker(
        audioChunk: ByteArray,
        knownSpeakers: List<Speaker>
    ): String? = withContext(Dispatchers.Default) {
        if (knownSpeakers.isEmpty() || audioChunk.isEmpty()) return@withContext null

        val newEmbedding = generateEmbedding(audioChunk)

        return@withContext knownSpeakers
            .map { speaker ->
                val similarity = calculateCosineSimilarity(newEmbedding, speaker.embedding)
                speaker.id to similarity
            }
            .maxByOrNull { it.second }
            ?.let { (id, similarity) ->
                Timber.d("Top speaker match: ID $id with similarity $similarity")
                if (similarity > SpeakerIdentifier.SIMILARITY_THRESHOLD) id else null
            }
    }


    override suspend fun generateEmbedding(audioChunk: ByteArray): SpeakerEmbedding =
        withContext(Dispatchers.Default) {
            val floatBuffer = ByteBuffer.wrap(audioChunk)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            val floatArray = FloatArray(floatBuffer.remaining()) {
                floatBuffer.get().toFloat() / 32768.0f
            }

            val inputTensorBuffer = FloatBuffer.wrap(floatArray)
            val shape = longArrayOf(1, floatArray.size.toLong())
            val inputTensor = OnnxTensor.createTensor(ortEnvironment, inputTensorBuffer, shape)

            val inputs = mapOf("input_values" to inputTensor)

            inputTensor.use {
                session.run(inputs).use { result ->
                    val outputValue = result[0].value as Array<FloatArray>
                    return@withContext outputValue[0]
                }
            }
        }

    override fun calculateCosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.isEmpty() || vecB.isEmpty() || vecA.size != vecB.size) return 0.0f
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in vecA.indices) {
            dotProduct += vecA[i] * vecB[i]
            normA += vecA[i] * vecA[i]
            normB += vecB[i] * vecB[i]
        }
        val normProduct = sqrt(normA) * sqrt(normB)
        return if (normProduct == 0.0f) 0.0f else dotProduct / normProduct
    }

    override fun close() {
        session.close()
    }
}
