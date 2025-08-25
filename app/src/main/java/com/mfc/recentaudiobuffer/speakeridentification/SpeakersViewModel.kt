package com.mfc.recentaudiobuffer.speakeridentification

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mfc.recentaudiobuffer.AudioConfig
import com.mfc.recentaudiobuffer.FileSavingUtils
import com.mfc.recentaudiobuffer.WavUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

data class UnknownSpeaker(
    val id: String,
    val audioSegments: List<ByteArray>,
    val sampleUri: Uri? = null // URI for the playable audio sample
)

sealed class SpeakerDiscoveryUiState {
    object Idle : SpeakerDiscoveryUiState()
    data class Scanning(val progress: Float, val currentFile: Int, val totalFiles: Int) : SpeakerDiscoveryUiState()
    object Stopping : SpeakerDiscoveryUiState()
    object Clustering : SpeakerDiscoveryUiState()
    data class Success(val unknownSpeakers: List<UnknownSpeaker>) : SpeakerDiscoveryUiState()
    data class Error(val message: String) : SpeakerDiscoveryUiState()
}

@HiltViewModel
class SpeakersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val speakerRepository: SpeakerRepository,
    private val speakerIdentifier: SpeakerIdentifier,
    private val diarizationProcessor: DiarizationProcessor
) : ViewModel() {

    val speakers: StateFlow<List<Speaker>> = speakerRepository.getAllSpeakers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow<SpeakerDiscoveryUiState>(SpeakerDiscoveryUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private var scanningJob: Job? = null

    fun scanRecordingsForUnknownSpeakers() {
        scanningJob?.cancel()
        val allUnidentifiedSegments = mutableListOf<UnidentifiedSegment>()

        scanningJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    _uiState.value = SpeakerDiscoveryUiState.Scanning(0f, 0, 0)
                }
                collectAllUnidentifiedSegments(allUnidentifiedSegments)
            } catch (e: CancellationException) {
                Timber.i("Scanning was cancelled by the user. Proceeding with partial results.")
            } catch (e: Exception) {
                Timber.e(e, "Error during speaker scan")
                withContext(Dispatchers.Main) {
                    _uiState.value = SpeakerDiscoveryUiState.Error("An error occurred during scanning.")
                }
            } finally {
                withContext(NonCancellable) {
                    Timber.d("Proceeding to cluster ${allUnidentifiedSegments.size} segments.")
                    withContext(Dispatchers.Main) {
                        _uiState.value = SpeakerDiscoveryUiState.Clustering
                    }

                    val clusteredSpeakers = clusterUnidentifiedSegments(allUnidentifiedSegments)
                    val finalUnknownSpeakers = createUnknownSpeakerObjects(clusteredSpeakers)

                    withContext(Dispatchers.Main) {
                        _uiState.value = SpeakerDiscoveryUiState.Success(finalUnknownSpeakers)
                    }
                }
            }
        }
    }

    private suspend fun collectAllUnidentifiedSegments(targetList: MutableList<UnidentifiedSegment>) {
        val recordingsDirUri = FileSavingUtils.getCachedGrantedUri(context)
            ?: throw IOException("Please set a recordings directory first.")

        val wavFiles = DocumentFile.fromTreeUri(context, recordingsDirUri)
            ?.listFiles()
            ?.filter { it.name?.endsWith(".wav") == true } ?: emptyList()

        if (wavFiles.isEmpty()) return

        val knownSpeakers = speakers.value

        for ((index, file) in wavFiles.withIndex()) {
            coroutineContext.ensureActive()

            val audioBytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            if (audioBytes != null) {
                val audioBuffer = ByteBuffer.wrap(audioBytes)
                val audioConfig = WavUtils.readWavHeader(audioBytes) ?: continue
                val unidentifiedFromFile = diarizationProcessor.process(
                    audioBuffer,
                    file.uri.toString(),
                    knownSpeakers,
                    audioConfig
                )
                targetList.addAll(unidentifiedFromFile)
            }

            withContext(Dispatchers.Main) {
                _uiState.value = SpeakerDiscoveryUiState.Scanning(
                    progress = (index + 1).toFloat() / wavFiles.size,
                    currentFile = index + 1,
                    totalFiles = wavFiles.size
                )
            }
        }
    }

    private suspend fun clusterUnidentifiedSegments(segments: List<UnidentifiedSegment>): Map<String, List<UnidentifiedSegment>> = withContext(Dispatchers.Default) {
        if (segments.isEmpty()) return@withContext emptyMap()

        val clusters = mutableMapOf<String, MutableList<UnidentifiedSegment>>()
        val clusterRepresentativeEmbeddings = mutableMapOf<String, SpeakerEmbedding>()

        for (segment in segments) {
            coroutineContext.ensureActive()

            if (clusterRepresentativeEmbeddings.isEmpty()) {
                val newId = "unknown_${UUID.randomUUID()}"
                clusters[newId] = mutableListOf(segment)
                clusterRepresentativeEmbeddings[newId] = segment.embedding
                continue
            }

            val bestMatch = clusterRepresentativeEmbeddings.entries.maxByOrNull { (_, clusterEmbedding) ->
                speakerIdentifier.calculateCosineSimilarity(segment.embedding, clusterEmbedding)
            }!!

            val bestSimilarity = speakerIdentifier.calculateCosineSimilarity(segment.embedding, bestMatch.value)

            if (bestSimilarity > SpeakerIdentifier.SIMILARITY_THRESHOLD) {
                val matchedId = bestMatch.key
                clusters.getValue(matchedId).add(segment)
                val updatedEmbedding = speakerIdentifier.averageEmbeddings(listOf(bestMatch.value, segment.embedding))
                clusterRepresentativeEmbeddings[matchedId] = updatedEmbedding
            } else {
                val newId = "unknown_${UUID.randomUUID()}"
                clusters[newId] = mutableListOf(segment)
                clusterRepresentativeEmbeddings[newId] = segment.embedding
            }
        }
        return@withContext clusters
    }

    private suspend fun createUnknownSpeakerObjects(
        clusteredSegments: Map<String, List<UnidentifiedSegment>>
    ): List<UnknownSpeaker> {
        if (clusteredSegments.isEmpty()) return emptyList()

        val finalSpeakers = mutableListOf<UnknownSpeaker>()
        var representativeConfig: AudioConfig? = null

        val segmentsGroupedByFile = clusteredSegments.values.flatten().groupBy { it.fileUriString }
        val audioDataCache = mutableMapOf<String, ByteArray>()

        for (uriString in segmentsGroupedByFile.keys) {
            coroutineContext.ensureActive()
            context.contentResolver.openInputStream(Uri.parse(uriString))?.use {
                audioDataCache[uriString] = it.readBytes()
                if (representativeConfig == null) {
                    representativeConfig = WavUtils.readWavHeader(audioDataCache[uriString]!!)
                }
            }
        }

        val config = representativeConfig
        if (config == null) {
            Timber.e("Could not read WAV header from any file.")
            return emptyList()
        }

        for ((id, segments) in clusteredSegments) {
            coroutineContext.ensureActive()
            val audioChunks = segments.mapNotNull { segment ->
                audioDataCache[segment.fileUriString]?.copyOfRange(segment.startOffsetBytes, segment.endOffsetBytes)
            }

            if (audioChunks.isNotEmpty()) {
                val sampleUri = createSpeakerSample(audioChunks, config)
                finalSpeakers.add(UnknownSpeaker(id, audioChunks, sampleUri))
            }
        }
        return finalSpeakers
    }


    fun stopScanning() {
        if (_uiState.value is SpeakerDiscoveryUiState.Scanning) {
            _uiState.value = SpeakerDiscoveryUiState.Stopping
        }
        scanningJob?.cancel()
    }

    private fun createSpeakerSample(audioSegments: List<ByteArray>, config: AudioConfig): Uri? {
        if (audioSegments.isEmpty()) return null
        return try {
            // FIX: The original implementation could create unhelpfully short audio samples
            // if only small speech segments were found. This version ensures a minimum
            // playback duration by looping the available audio.

            // 1. Concatenate all available segments into a single byte array.
            val singlePassStream = ByteArrayOutputStream()
            for (segment in audioSegments) {
                singlePassStream.write(segment)
            }
            val singlePassAudio = singlePassStream.toByteArray()

            if (singlePassAudio.isEmpty()) return null

            // 2. Define minimum and maximum desired duration for the sample.
            val minDurationSec = 3
            val maxDurationSec = 10
            val minBytes = config.sampleRateHz * (config.bitDepth.bits / 8) * minDurationSec
            val maxBytes = config.sampleRateHz * (config.bitDepth.bits / 8) * maxDurationSec

            // 3. Loop the concatenated audio until it reaches the minimum duration.
            val finalAudioStream = ByteArrayOutputStream()
            finalAudioStream.write(singlePassAudio)
            while (finalAudioStream.size() < minBytes) {
                finalAudioStream.write(singlePassAudio)
            }

            var audioData = finalAudioStream.toByteArray()

            // 4. Truncate the audio if looping made it exceed the maximum duration.
            if (audioData.size > maxBytes) {
                audioData = audioData.copyOfRange(0, maxBytes)
            }

            // 5. Write the final, properly-sized audio data to a temporary WAV file.
            val tempFile = File.createTempFile("speaker_sample", ".wav", context.cacheDir)
            FileOutputStream(tempFile).use { fos ->
                WavUtils.writeWavHeader(fos, audioData.size, config)
                fos.write(audioData)
            }
            Uri.fromFile(tempFile)
        } catch (e: IOException) {
            Timber.e(e, "Failed to create speaker sample file")
            null
        }
    }

    fun addSpeaker(name: String, unknownSpeaker: UnknownSpeaker) {
        viewModelScope.launch {
            val embedding = speakerIdentifier.createEmbedding(unknownSpeaker.audioSegments)
            val newSpeaker = Speaker(name = name, embedding = embedding)
            speakerRepository.addSpeaker(newSpeaker)
        }
    }

    fun renameSpeaker(speaker: Speaker, newName: String) {
        viewModelScope.launch {
            speakerRepository.updateSpeaker(speaker.copy(name = newName))
        }
    }

    fun deleteSpeaker(speaker: Speaker) {
        viewModelScope.launch {
            speakerRepository.deleteSpeaker(speaker)
        }
    }

    fun clearScanState() {
        _uiState.value = SpeakerDiscoveryUiState.Idle
    }
}
