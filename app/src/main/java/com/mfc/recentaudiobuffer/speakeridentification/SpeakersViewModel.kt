package com.mfc.recentaudiobuffer.speakeridentification

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mfc.recentaudiobuffer.AudioConfig
import com.mfc.recentaudiobuffer.FileSavingUtils
import com.mfc.recentaudiobuffer.VADProcessor
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
import kotlinx.coroutines.flow.update
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
    val sampleUri: Uri? = null,
    val averageEmbedding: SpeakerEmbedding? = null  // Add average embedding for better clustering
)

data class RecordingFile(
    val name: String,
    val uri: Uri,
    val sizeMb: Float,
    val lastModified: Long
)

sealed class SpeakerDiscoveryUiState {
    object Idle : SpeakerDiscoveryUiState()
    object LoadingFiles : SpeakerDiscoveryUiState() // State for initial file loading
    data class FileSelection(
        val allFiles: List<RecordingFile>,
        val selectedFileUris: Set<Uri>,
        val processedFileUris: Set<String>,
        val isLoading: Boolean = false // State for reloading/resetting within the dialog
    ) : SpeakerDiscoveryUiState()

    data class Scanning(val progress: Float, val currentFile: Int, val totalFiles: Int) :
        SpeakerDiscoveryUiState()

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
    private val diarizationProcessor: DiarizationProcessor,
    private val vadProcessor: VADProcessor  // Add VAD processor for sample cleaning
) : ViewModel() {

    companion object {

        /**
         * ## Tuning Variable: Unknown Speaker Clustering Threshold
         *
         * This threshold controls the grouping of *unknown* voice segments into potential new speakers.
         * It answers the question: "How similar do two unidentified voice samples need to be before we
         * consider them to be from the same new person?"
         *
         * - **A HIGHER VALUE (e.g., 0.65f):**
         * - **Stricter.** Creates fewer, but more accurate, speaker clusters.
         * - **Pro:** Low risk of grouping two different people together.
         * - **Con:** May fail to group segments from the same person if their voice varies,
         * resulting in fragmented clusters that get discarded by `MIN_CLUSTER_SIZE`. This can
         * lead to the "no new speakers found" message.
         *
         * - **A LOWER VALUE (e.g., 0.55f):**
         * - **More Lenient.** "Casts a wider net" to find potential speakers.
         * - **Pro:** Better at finding new speakers, even with voice variations.
         * - **Con:** Higher risk of incorrectly grouping two different but similar-sounding
         * speakers into the same cluster.
         *
         * For a "discovery" feature like this, it's better to start with a more lenient value
         * to find more potential matches for the user to review.
         */
        const val CLUSTERING_THRESHOLD = 0.48f

        const val MIN_CLUSTER_SIZE = 2

        // Sample creation parameters
        const val SAMPLE_MIN_DURATION_SEC = 5
        const val SAMPLE_MAX_DURATION_SEC = 15
        const val SAMPLE_TARGET_SEGMENTS = 3  // Try to include this many different segments
    }

    val speakers: StateFlow<List<Speaker>> = speakerRepository.getAllSpeakers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow<SpeakerDiscoveryUiState>(SpeakerDiscoveryUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private var scanningJob: Job? = null

    // Track which files have already been processed
    private val processedFiles = mutableSetOf<String>()

    fun prepareFileSelection() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = SpeakerDiscoveryUiState.LoadingFiles
            try {
                val recordingsDirUri = FileSavingUtils.getCachedGrantedUri(context)
                    ?: throw IOException("Please set a recordings directory first.")

                val allWavFiles = DocumentFile.fromTreeUri(context, recordingsDirUri)
                    ?.listFiles()
                    ?.filter { it.name?.endsWith(".wav") == true && it.name?.startsWith(".trashed") == false }
                    ?.map {
                        RecordingFile(
                            name = it.name ?: "Unknown File",
                            uri = it.uri,
                            sizeMb = it.length() / (1024f * 1024f),
                            lastModified = it.lastModified()
                        )
                    }
                    ?.sortedByDescending { it.lastModified } // Show newest files first
                    ?: emptyList()

                val unprocessedFiles =
                    allWavFiles.filter { !processedFiles.contains(it.uri.toString()) }

                _uiState.value = SpeakerDiscoveryUiState.FileSelection(
                    allFiles = allWavFiles,
                    selectedFileUris = unprocessedFiles.map { it.uri }
                        .toSet(), // Pre-select unprocessed files
                    processedFileUris = processedFiles
                )
            } catch (e: Exception) {
                Timber.e(e, "Error preparing file list")
                _uiState.value = SpeakerDiscoveryUiState.Error("Could not load recording files.")
            }
        }
    }

    fun toggleFileSelection(uri: Uri) {
        val currentState = _uiState.value
        if (currentState is SpeakerDiscoveryUiState.FileSelection) {
            val newSelection = currentState.selectedFileUris.toMutableSet()
            if (newSelection.contains(uri)) {
                newSelection.remove(uri)
            } else {
                newSelection.add(uri)
            }
            _uiState.value = currentState.copy(selectedFileUris = newSelection)
        }
    }


    fun startScan(selectedFileUris: Set<Uri>) {
        scanningJob?.cancel()

        scanningJob = viewModelScope.launch(Dispatchers.IO) {
            val allUnidentifiedSegments = mutableListOf<UnidentifiedSegment>()
            var wasCancelled = false

            try {
                _uiState.value = SpeakerDiscoveryUiState.Scanning(0f, 0, selectedFileUris.size)
                collectAllUnidentifiedSegments(allUnidentifiedSegments, selectedFileUris)
            } catch (e: CancellationException) {
                Timber.i("Scanning was cancelled by the user. Proceeding with partial results.")
                wasCancelled = true
            } catch (e: Exception) {
                Timber.e(e, "Error during speaker scan")
                _uiState.value = SpeakerDiscoveryUiState.Error("An error occurred during scanning.")
                return@launch
            }

            // Process collected segments even if cancelled
            if (allUnidentifiedSegments.isNotEmpty() || wasCancelled) {
                withContext(NonCancellable) {
                    Timber.d("Proceeding to cluster ${allUnidentifiedSegments.size} segments.")
                    _uiState.value = SpeakerDiscoveryUiState.Clustering

                    val clusteredSpeakers = if (allUnidentifiedSegments.isNotEmpty()) {
                        improvedClusterSegments(allUnidentifiedSegments)
                    } else {
                        emptyMap()
                    }

                    val finalUnknownSpeakers = createUnknownSpeakerObjects(clusteredSpeakers)

                    _uiState.value = SpeakerDiscoveryUiState.Success(finalUnknownSpeakers)
                }
            } else {
                // If no segments were found and it wasn't cancelled, it means scan finished with no results.
                _uiState.value = SpeakerDiscoveryUiState.Success(emptyList())
            }
        }
    }

    private suspend fun collectAllUnidentifiedSegments(
        targetList: MutableList<UnidentifiedSegment>,
        filesToProcessUris: Set<Uri>
    ) {
        if (filesToProcessUris.isEmpty()) {
            Timber.d("No files selected for processing.")
            return
        }

        val knownSpeakers = speakers.value
        val filesToProcess = filesToProcessUris.mapNotNull { uri ->
            try {
                DocumentFile.fromSingleUri(context, uri)
            } catch (e: Exception) {
                null
            }
        }


        for ((index, file) in filesToProcess.withIndex()) {
            coroutineContext.ensureActive()  // Check for cancellation

            val fileUri = file.uri.toString()

            try {
                val audioBytes =
                    context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                if (audioBytes != null) {
                    val audioBuffer = ByteBuffer.wrap(audioBytes)
                    val audioConfig = WavUtils.readWavHeader(audioBytes) ?: continue
                    val unidentifiedFromFile = diarizationProcessor.process(
                        audioBuffer,
                        fileUri,
                        knownSpeakers,
                        audioConfig
                    )
                    targetList.addAll(unidentifiedFromFile)

                    // Mark file as processed only after successful processing
                    processedFiles.add(fileUri)
                }
            } catch (e: CancellationException) {
                // Don't mark as processed if cancelled during processing
                Timber.d("Cancelled while processing file: ${file.name}")
                throw e  // Re-throw to propagate cancellation
            } catch (e: Exception) {
                Timber.e(e, "Error processing file: ${file.name}")
                // Mark as processed anyway to avoid getting stuck on problematic files
                processedFiles.add(fileUri)
            }

            withContext(Dispatchers.Main) {
                _uiState.update {
                    if (it is SpeakerDiscoveryUiState.Scanning) {
                        it.copy(
                            progress = (index + 1).toFloat() / filesToProcess.size,
                            currentFile = index + 1,
                            totalFiles = filesToProcess.size
                        )
                    } else {
                        it // Don't change state if it's not Scanning (e.g., if it was stopped)
                    }
                }
            }
        }
    }

    private suspend fun improvedClusterSegments(
        segments: List<UnidentifiedSegment>
    ): Map<String, List<UnidentifiedSegment>> = withContext(Dispatchers.Default) {
        if (segments.isEmpty()) return@withContext emptyMap()

        // Use hierarchical clustering for better results
        val clusters = mutableMapOf<String, MutableList<UnidentifiedSegment>>()
        val clusterCentroids = mutableMapOf<String, SpeakerEmbedding>()

        for (segment in segments) {
            coroutineContext.ensureActive()

            if (clusterCentroids.isEmpty()) {
                val newId = "unknown_${UUID.randomUUID()}"
                clusters[newId] = mutableListOf(segment)
                clusterCentroids[newId] = segment.embedding
                continue
            }

            // Find best matching cluster
            val similarities = clusterCentroids.mapValues { (_, centroid) ->
                speakerIdentifier.calculateCosineSimilarity(segment.embedding, centroid)
            }

            val (bestClusterId, bestSimilarity) = similarities.maxByOrNull { it.value } ?: continue

            if (bestSimilarity > CLUSTERING_THRESHOLD) {
                // Add to existing cluster and update centroid
                clusters.getValue(bestClusterId).add(segment)

                // Update centroid as running average
                val clusterSegments = clusters.getValue(bestClusterId)
                val embeddings = clusterSegments.map { it.embedding }
                clusterCentroids[bestClusterId] = speakerIdentifier.averageEmbeddings(embeddings)
            } else {
                // Create new cluster
                val newId = "unknown_${UUID.randomUUID()}"
                clusters[newId] = mutableListOf(segment)
                clusterCentroids[newId] = segment.embedding
            }
        }

        // DEBUG: Log the size of all found clusters before filtering
        Timber.d("Clustering found ${clusters.size} initial groups with sizes: ${clusters.map { it.value.size }}")

        // Filter out small clusters (likely noise)
        return@withContext clusters.filter { it.value.size >= MIN_CLUSTER_SIZE }
    }

    private suspend fun createUnknownSpeakerObjects(
        clusteredSegments: Map<String, List<UnidentifiedSegment>>
    ): List<UnknownSpeaker> {
        if (clusteredSegments.isEmpty()) return emptyList()

        val finalSpeakers = mutableListOf<UnknownSpeaker>()
        val audioDataCache = mutableMapOf<String, Pair<ByteArray, AudioConfig>>()

        // Cache audio files to avoid re-reading
        for (segments in clusteredSegments.values) {
            for (segment in segments) {
                if (!audioDataCache.containsKey(segment.fileUriString)) {
                    coroutineContext.ensureActive()
                    context.contentResolver.openInputStream(Uri.parse(segment.fileUriString))?.use {
                        val audioBytes = it.readBytes()
                        val config = WavUtils.readWavHeader(audioBytes)
                        if (config != null) {
                            audioDataCache[segment.fileUriString] = Pair(audioBytes, config)
                        }
                    }
                }
            }
        }

        for ((id, segments) in clusteredSegments) {
            coroutineContext.ensureActive()

            // Extract audio chunks with proper sample rate tracking
            val audioChunksWithConfig = segments.mapNotNull { segment ->
                audioDataCache[segment.fileUriString]?.let { (audioBytes, config) ->
                    val chunk =
                        audioBytes.copyOfRange(segment.startOffsetBytes, segment.endOffsetBytes)
                    Triple(chunk, config, segment.originalSampleRate)
                }
            }

            if (audioChunksWithConfig.isNotEmpty()) {
                // Create a proper sample from diverse segments
                val sampleUri = createImprovedSpeakerSample(audioChunksWithConfig)

                // Calculate average embedding for the cluster
                val avgEmbedding =
                    speakerIdentifier.averageEmbeddings(segments.map { it.embedding })

                finalSpeakers.add(
                    UnknownSpeaker(
                        id = id,
                        audioSegments = audioChunksWithConfig.map { it.first },
                        sampleUri = sampleUri,
                        averageEmbedding = avgEmbedding
                    )
                )
            }
        }
        return finalSpeakers
    }

    private suspend fun createImprovedSpeakerSample(
        audioChunksWithConfig: List<Triple<ByteArray, AudioConfig, Int>>
    ): Uri? = withContext(Dispatchers.IO) {
        if (audioChunksWithConfig.isEmpty()) return@withContext null

        try {
            // Use the most common sample rate
            val targetSampleRate = audioChunksWithConfig
                .groupingBy { it.third }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key ?: return@withContext null

            val targetConfig = audioChunksWithConfig
                .first { it.third == targetSampleRate }
                .second

            // Select diverse segments (not just the first few)
            val selectedChunks =
                selectDiverseSegments(audioChunksWithConfig, SAMPLE_TARGET_SEGMENTS)

            val sampleStream = ByteArrayOutputStream()
            val bytesPerSample = targetConfig.bitDepth.bits / 8
            val minBytes = targetConfig.sampleRateHz * bytesPerSample * SAMPLE_MIN_DURATION_SEC
            val maxBytes = targetConfig.sampleRateHz * bytesPerSample * SAMPLE_MAX_DURATION_SEC

            for ((chunk, config, originalRate) in selectedChunks) {
                // Resample if necessary
                val processedChunk = if (originalRate != targetSampleRate) {
                    vadProcessor.resampleAudioChunk(chunk, originalRate, targetSampleRate)
                } else {
                    chunk
                }

                // Add silence between segments for clarity
                if (sampleStream.size() > 0) {
                    val silenceSamples = targetSampleRate / 4  // 0.25 seconds
                    val silenceBytes = ByteArray(silenceSamples * bytesPerSample)
                    sampleStream.write(silenceBytes)
                }

                sampleStream.write(processedChunk)

                // Stop if we've reached maximum duration
                if (sampleStream.size() >= maxBytes) break
            }

            var audioData = sampleStream.toByteArray()

            // Ensure minimum duration by repeating if necessary
            if (audioData.size < minBytes && audioData.isNotEmpty()) {
                val repeatCount = (minBytes / audioData.size) + 1
                val repeatedStream = ByteArrayOutputStream()
                repeat(repeatCount) {
                    repeatedStream.write(audioData)
                    // Add silence between repetitions
                    if (it < repeatCount - 1) {
                        val silenceSamples = targetSampleRate / 2  // 0.5 seconds
                        val silenceBytes = ByteArray(silenceSamples * bytesPerSample)
                        repeatedStream.write(silenceBytes)
                    }
                }
                audioData = repeatedStream.toByteArray().copyOf(minBytes)
            }

            // Truncate if too long
            if (audioData.size > maxBytes) {
                audioData = audioData.copyOf(maxBytes)
            }

            // Write to temporary WAV file
            val tempFile = File.createTempFile("speaker_sample", ".wav", context.cacheDir)
            FileOutputStream(tempFile).use { fos ->
                WavUtils.writeWavHeader(fos, audioData.size, targetConfig)
                fos.write(audioData)
            }
            Uri.fromFile(tempFile)
        } catch (e: Exception) {
            Timber.e(e, "Failed to create speaker sample")
            null
        }
    }

    private fun selectDiverseSegments(
        chunks: List<Triple<ByteArray, AudioConfig, Int>>,
        targetCount: Int
    ): List<Triple<ByteArray, AudioConfig, Int>> {
        if (chunks.size <= targetCount) return chunks

        // Select segments evenly distributed across the collection
        val step = (chunks.size.toFloat() / targetCount).coerceAtLeast(1.0f)
        return chunks.filterIndexed { index, _ ->
            (index.toFloat() / step).toInt() != ((index - 1).toFloat() / step).toInt()
        }.take(targetCount)
    }

    fun addSpeaker(name: String, unknownSpeaker: UnknownSpeaker) {
        viewModelScope.launch {
            // Use the pre-calculated average embedding if available
            val embedding = unknownSpeaker.averageEmbedding
                ?: speakerIdentifier.createEmbedding(unknownSpeaker.audioSegments)

            // Copy the sample to a permanent location
            val permanentSampleUri = unknownSpeaker.sampleUri?.let { tempUri ->
                try {
                    val tempFile = File(tempUri.path!!)
                    val permanentFile =
                        File(context.filesDir, "speaker_samples/${name}_${UUID.randomUUID()}.wav")
                    permanentFile.parentFile?.mkdirs()
                    tempFile.copyTo(permanentFile, overwrite = true)
                    Uri.fromFile(permanentFile)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to copy speaker sample")
                    tempUri  // Fall back to temp URI
                }
            }

            val newSpeaker =
                Speaker(name = name, embedding = embedding, sampleUri = permanentSampleUri)
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
            // After deleting a speaker, reset the processed files list.
            // This allows the user to re-scan the same recordings to find
            // the deleted speaker again as an "Unknown Speaker".
            resetProcessedFiles()
        }
    }

    fun deleteAllSpeakers() {
        viewModelScope.launch {
            speakerRepository.deleteAllSpeakers()
            resetProcessedFiles()
        }
    }

    fun clearScanState() {
        _uiState.value = SpeakerDiscoveryUiState.Idle
    }

    fun stopScanning() {
        if (_uiState.value is SpeakerDiscoveryUiState.Scanning) {
            _uiState.value = SpeakerDiscoveryUiState.Stopping
        }
        scanningJob?.cancel()
    }

    fun resetProcessedFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentState = _uiState.value
            if (currentState is SpeakerDiscoveryUiState.FileSelection) {
                // Update current state to show loading IN the dialog
                _uiState.value = currentState.copy(isLoading = true)
            } else {
                // If not in file selection, show a global loading indicator
                _uiState.value = SpeakerDiscoveryUiState.LoadingFiles
            }

            processedFiles.clear()
            Timber.d("Cleared processed files list. Next scan will process all files.")

            // Now, reload the files and create the new state
            try {
                val recordingsDirUri = FileSavingUtils.getCachedGrantedUri(context)
                    ?: throw IOException("Please set a recordings directory first.")

                val allWavFiles = DocumentFile.fromTreeUri(context, recordingsDirUri)
                    ?.listFiles()
                    ?.filter { it.name?.endsWith(".wav") == true && it.name?.startsWith(".trashed") == false }
                    ?.map {
                        RecordingFile(
                            name = it.name ?: "Unknown File",
                            uri = it.uri,
                            sizeMb = it.length() / (1024f * 1024f),
                            lastModified = it.lastModified()
                        )
                    }
                    ?.sortedByDescending { it.lastModified }
                    ?: emptyList()

                // The new state has all files selected and loading is false
                _uiState.value = SpeakerDiscoveryUiState.FileSelection(
                    allFiles = allWavFiles,
                    selectedFileUris = allWavFiles.map { it.uri }.toSet(),
                    processedFileUris = emptySet(),
                    isLoading = false
                )
            } catch (e: Exception) {
                Timber.e(e, "Error during reset")
                _uiState.value = SpeakerDiscoveryUiState.Error("Failed to reload files after reset.")
            }
        }
    }


    override fun onCleared() {
        super.onCleared()
        scanningJob?.cancel()
    }
}
