package com.mfc.recentaudiobuffer.speakeridentification

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
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
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

data class UnknownSpeaker(
    val id: String,
    val audioSegments: List<ByteArray>,
    val sampleUri: Uri? = null,
    val averageEmbedding: SpeakerEmbedding? = null
)

data class RecordingFile(
    val name: String, val uri: Uri, val sizeMb: Float, val lastModified: Long
)

sealed class SpeakerDiscoveryUiState {
    object Idle : SpeakerDiscoveryUiState()
    object LoadingFiles : SpeakerDiscoveryUiState()
    data class FileSelection(
        val allFiles: List<RecordingFile>,
        val selectedFileUris: Set<Uri>,
        val processedFileUris: Set<String>,
        val isLoading: Boolean = false
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
    private val vadProcessor: VADProcessor
) : ViewModel() {

    companion object {
        // DBSCAN parameters
        const val DBSCAN_EPS = 0.65f
        const val DBSCAN_MIN_PTS = 3

        // Post-processing
        const val FINAL_MERGE_THRESHOLD = 0.35f         // Slightly more lenient merge to combine duplicates
        const val MIN_CLUSTER_SIZE = 2
        const val CLUSTER_PURITY_THRESHOLD = 0.50f      // Segments must be at least 50% similar to the cluster average

        // Sample generation
        const val SAMPLE_MIN_DURATION_SEC = 7
        const val SAMPLE_MAX_DURATION_SEC = 20
        const val SAMPLE_TARGET_SEGMENTS = 15
        const val MIN_CHUNK_DURATION_SEC = 1.0f
        const val SAMPLE_SILENCE_DURATION_MS = 500
    }

    val speakers: StateFlow<List<Speaker>> = speakerRepository.getAllSpeakers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow<SpeakerDiscoveryUiState>(SpeakerDiscoveryUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private var scanningJob: Job? = null
    private val processedFiles = mutableSetOf<String>()

    fun prepareFileSelection() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = SpeakerDiscoveryUiState.LoadingFiles
            try {
                val recordingsDirUri = FileSavingUtils.getCachedGrantedUri(context)
                    ?: throw IOException("Please set a recordings directory first.")

                val allWavFiles = DocumentFile.fromTreeUri(context, recordingsDirUri)?.listFiles()
                    ?.filter { it.name?.endsWith(".wav") == true && it.name?.startsWith(".trashed") == false }
                    ?.map {
                        RecordingFile(
                            name = it.name ?: "Unknown File",
                            uri = it.uri,
                            sizeMb = it.length() / (1024f * 1024f),
                            lastModified = it.lastModified()
                        )
                    }?.sortedByDescending { it.lastModified } ?: emptyList()

                val unprocessedFiles =
                    allWavFiles.filter { !processedFiles.contains(it.uri.toString()) }

                _uiState.value = SpeakerDiscoveryUiState.FileSelection(
                    allFiles = allWavFiles,
                    selectedFileUris = unprocessedFiles.map { it.uri }.toSet(),
                    processedFileUris = processedFiles
                )
            } catch (e: Exception) {
                Timber.e(e, "Error preparing file list")
                _uiState.value = SpeakerDiscoveryUiState.Error("Could not load recording files.")
            }
        }
    }

    fun toggleFileSelection(uri: Uri) {
        _uiState.update { currentState ->
            if (currentState is SpeakerDiscoveryUiState.FileSelection) {
                val newSelection = currentState.selectedFileUris.toMutableSet()
                if (newSelection.contains(uri)) {
                    newSelection.remove(uri)
                } else {
                    newSelection.add(uri)
                }
                currentState.copy(selectedFileUris = newSelection)
            } else {
                currentState
            }
        }
    }

    fun startScan(selectedFileUris: Set<Uri>) {
        scanningJob?.cancel()
        scanningJob = viewModelScope.launch(Dispatchers.IO) {
            val allUnidentifiedSegments = mutableListOf<UnidentifiedSegment>()

            try {
                _uiState.value = SpeakerDiscoveryUiState.Scanning(0f, 0, selectedFileUris.size)
                collectAllUnidentifiedSegments(allUnidentifiedSegments, selectedFileUris)
            } catch (e: CancellationException) {
                Timber.i("Scanning was cancelled by the user. Proceeding with partial results.")
            } catch (e: Exception) {
                Timber.e(e, "Error during speaker scan")
                _uiState.value = SpeakerDiscoveryUiState.Error("An error occurred during scanning.")
                return@launch
            } finally {
                if (allUnidentifiedSegments.isNotEmpty()) {
                    withContext(NonCancellable) {
                        _uiState.value = SpeakerDiscoveryUiState.Clustering
                        val clusteredSpeakers = improvedClusterSegments(allUnidentifiedSegments)
                        val finalUnknownSpeakers = createUnknownSpeakerObjects(clusteredSpeakers)
                        _uiState.value = SpeakerDiscoveryUiState.Success(finalUnknownSpeakers)
                    }
                } else {
                    _uiState.value = SpeakerDiscoveryUiState.Success(emptyList())
                }
            }
        }
    }

    private suspend fun collectAllUnidentifiedSegments(
        targetList: MutableList<UnidentifiedSegment>, filesToProcessUris: Set<Uri>
    ) {
        if (filesToProcessUris.isEmpty()) return

        val knownSpeakers = speakers.value
        val filesToProcess = filesToProcessUris.mapNotNull { uri ->
            try {
                DocumentFile.fromSingleUri(context, uri)
            } catch (e: Exception) {
                null
            }
        }

        for ((index, file) in filesToProcess.withIndex()) {
            coroutineContext.ensureActive()
            val fileUri = file.uri.toString()
            try {
                val audioBytes =
                    context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                if (audioBytes != null) {
                    val audioBuffer = ByteBuffer.wrap(audioBytes)
                    val audioConfig = WavUtils.readWavHeader(audioBytes) ?: continue
                    val unidentifiedFromFile = diarizationProcessor.process(
                        audioBuffer, fileUri, knownSpeakers, audioConfig
                    )
                    targetList.addAll(unidentifiedFromFile)
                    processedFiles.add(fileUri)
                }
            } catch (e: CancellationException) {
                Timber.d("Cancelled while processing file: ${file.name}")
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error processing file: ${file.name}")
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
                    } else it
                }
            }
        }
    }

    private suspend fun improvedClusterSegments(
        segments: List<UnidentifiedSegment>
    ): Map<String, List<UnidentifiedSegment>> = withContext(Dispatchers.Default) {
        val validSegments = segments.filter { it.embedding.isNotEmpty() }

        if (validSegments.size < MIN_CLUSTER_SIZE) {
            Timber.d("Not enough valid segments (${validSegments.size}) to cluster.")
            return@withContext emptyMap()
        }

        // Stage 1: Primary Clustering
        Timber.d("Starting primary clustering with eps=$DBSCAN_EPS, minPts=$DBSCAN_MIN_PTS")
        val (initialClusters, noisePoints) = dbscanClusteringPass(
            validSegments, eps = DBSCAN_EPS, minPts = DBSCAN_MIN_PTS
        )
        Timber.d("Primary pass found ${initialClusters.size} clusters and ${noisePoints.size} noise points.")

        // Stage 2: Re-cluster noise points with more lenient settings
        val finalClusters = initialClusters.toMutableMap()
        if (noisePoints.size >= MIN_CLUSTER_SIZE) {
            val noiseEps = 0.85f // Corresponds to 15% similarity, lenient for leftovers
            val noiseMinPts = 2
            Timber.d("Re-clustering ${noisePoints.size} noise points with eps=$noiseEps, minPts=$noiseMinPts")
            val (noiseClusters, _) = dbscanClusteringPass(
                noisePoints, eps = noiseEps, minPts = noiseMinPts
            )
            Timber.d("Second pass found ${noiseClusters.size} additional clusters from noise.")

            // Add new clusters found in noise, avoiding key collisions
            noiseClusters.values.forEach { newCluster ->
                val newId = "cluster_noise_${finalClusters.size}"
                finalClusters[newId] = newCluster
            }
        }

        // Stage 3: Merge similar clusters
        val mergedClusters = if (finalClusters.size > 1) {
            Timber.d("Merging ${finalClusters.size} clusters with threshold $FINAL_MERGE_THRESHOLD.")
            mergeSimilarClusters(finalClusters, FINAL_MERGE_THRESHOLD)
        } else {
            finalClusters
        }

        // Stage 4: Filter by size and format output
        val result = mergedClusters
            .filter { it.value.size >= MIN_CLUSTER_SIZE }
            .entries
            .sortedByDescending { it.value.size }
            .mapIndexed { index, entry -> "speaker_${index + 1}" to entry.value }
            .toMap()

        Timber.d("Clustering complete. Found ${result.size} final speakers.")
        return@withContext result
    }

    private fun findNeighbors(
        segment: UnidentifiedSegment,
        allSegments: List<UnidentifiedSegment>,
        eps: Float
    ): List<UnidentifiedSegment> {
        return allSegments.filter { other ->
            val distance = 1.0f - speakerIdentifier.calculateCosineSimilarity(segment.embedding, other.embedding)
            distance <= eps
        }
    }

    private suspend fun dbscanClusteringPass(
        segments: List<UnidentifiedSegment>, eps: Float, minPts: Int
    ): Pair<Map<String, MutableList<UnidentifiedSegment>>, List<UnidentifiedSegment>> = withContext(Dispatchers.Default) {
        val labels = mutableMapOf<UnidentifiedSegment, Int>() // 0=unvisited, -1=noise, >0=clusterId
        var clusterId = 0

        for (segment in segments) {
            if (labels.containsKey(segment)) continue

            val neighbors = findNeighbors(segment, segments, eps)

            if (neighbors.size < minPts) {
                labels[segment] = -1 // Mark as noise
                continue
            }

            clusterId++
            labels[segment] = clusterId
            val seedSet = neighbors.toMutableSet()
            seedSet.remove(segment)

            while (seedSet.isNotEmpty()) {
                val current = seedSet.first()
                seedSet.remove(current)

                val currentLabel = labels.getOrDefault(current, 0)
                if (currentLabel == -1) labels[current] = clusterId // Change from noise to border point
                if (currentLabel != 0) continue // Already processed

                labels[current] = clusterId
                val currentNeighbors = findNeighbors(current, segments, eps)
                if (currentNeighbors.size >= minPts) {
                    seedSet.addAll(currentNeighbors)
                }
            }
        }

        val clusters = mutableMapOf<String, MutableList<UnidentifiedSegment>>()
        val noise = mutableListOf<UnidentifiedSegment>()

        labels.entries.groupBy { it.value }.forEach { (id, segmentEntries) ->
            val segmentList = segmentEntries.map { it.key }.toMutableList()
            if (id == -1) {
                noise.addAll(segmentList)
            } else if (id > 0) {
                clusters["cluster_pass_$id"] = segmentList
            }
        }

        return@withContext Pair(clusters, noise)
    }

    private fun mergeSimilarClusters(
        clusters: Map<String, MutableList<UnidentifiedSegment>>, threshold: Float
    ): Map<String, List<UnidentifiedSegment>> {
        if (clusters.size <= 1) return clusters

        val centroids =
            clusters.mapValues { speakerIdentifier.averageEmbeddings(it.value.map { seg -> seg.embedding }) }
                .toMutableMap()
        val clusterData = clusters.toMutableMap()

        var performedMerge = true
        while (performedMerge) {
            performedMerge = false
            val ids = centroids.keys.toList()
            if (ids.size < 2) break

            var bestSimilarity = -1f
            var c1: String? = null
            var c2: String? = null

            for (i in ids.indices) {
                for (j in (i + 1) until ids.size) {
                    val id1 = ids[i]
                    val id2 = ids[j]
                    val sim = speakerIdentifier.calculateCosineSimilarity(
                        centroids[id1]!!, centroids[id2]!!
                    )
                    if (sim > bestSimilarity) {
                        bestSimilarity = sim
                        c1 = id1
                        c2 = id2
                    }
                }
            }

            if (bestSimilarity >= threshold && c1 != null && c2 != null) {
                Timber.d("Final Merge Pass: Merging $c2 into $c1 with similarity $bestSimilarity")
                clusterData.getValue(c1).addAll(clusterData.getValue(c2))
                centroids[c1] = speakerIdentifier.averageEmbeddings(
                    clusterData.getValue(c1).map { it.embedding })
                clusterData.remove(c2)
                centroids.remove(c2)
                performedMerge = true
            }
        }
        return clusterData
    }

    private suspend fun createUnknownSpeakerObjects(
        clusteredSegments: Map<String, List<UnidentifiedSegment>>
    ): List<UnknownSpeaker> {
        if (clusteredSegments.isEmpty()) return emptyList()

        val finalSpeakers = mutableListOf<UnknownSpeaker>()
        val audioDataCache = mutableMapOf<String, Pair<ByteArray, AudioConfig>>()

        for (segments in clusteredSegments.values) {
            for (segment in segments) {
                if (!audioDataCache.containsKey(segment.fileUriString)) {
                    coroutineContext.ensureActive()
                    context.contentResolver.openInputStream(segment.fileUriString.toUri())?.use {
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

            // ** NEW CLUSTER PURITY CHECK **
            if (segments.size < 2) continue // Can't check purity on a single segment

            val clusterCentroid = speakerIdentifier.averageEmbeddings(segments.map { it.embedding })
            val pureSegments = segments.filter {
                val similarityToCentroid = speakerIdentifier.calculateCosineSimilarity(it.embedding, clusterCentroid)
                similarityToCentroid >= CLUSTER_PURITY_THRESHOLD
            }

            val discardedCount = segments.size - pureSegments.size
            if (discardedCount > 0) {
                Timber.d("Cluster $id purity check: Discarded $discardedCount/${segments.size} outlier segments.")
            }

            if (pureSegments.size < MIN_CLUSTER_SIZE) {
                Timber.d("Cluster $id discarded as it has too few pure segments (${pureSegments.size}).")
                continue
            }
            // ** END PURITY CHECK **

            val audioChunksWithConfig = pureSegments.mapNotNull { segment ->
                audioDataCache[segment.fileUriString]?.let { (audioBytes, config) ->
                    val chunk = audioBytes.copyOfRange(
                        segment.startOffsetBytes, segment.endOffsetBytes
                    )
                    Triple(chunk, config, segment.originalSampleRate)
                }
            }

            if (audioChunksWithConfig.isNotEmpty()) {
                val sampleUri = createImprovedSpeakerSample(audioChunksWithConfig)
                val avgEmbedding =
                    speakerIdentifier.averageEmbeddings(pureSegments.map { it.embedding })
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

    private fun calculateQualityScore(audioChunk: ByteArray): Float {
        if (audioChunk.isEmpty() || audioChunk.size % 2 != 0) return 0f
        val shortBuffer = ByteBuffer.wrap(audioChunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        if (!shortBuffer.hasRemaining()) return 0f

        var sumOfSquares = 0.0
        var peak = 0.0f
        val count = shortBuffer.remaining()
        for (i in 0 until count) {
            val sample = shortBuffer.get(i).toFloat() / 32768.0f
            sumOfSquares += (sample * sample)
            val absSample = abs(sample)
            if (absSample > peak) {
                peak = absSample
            }
        }
        val rms = sqrt(sumOfSquares / count).toFloat()

        if (rms == 0f) return 0f
        return rms * (peak / rms)
    }

    private fun selectBestSegments(
        chunks: List<Triple<ByteArray, AudioConfig, Int>>, targetCount: Int
    ): List<Triple<ByteArray, AudioConfig, Int>> {
        return chunks.sortedByDescending { calculateQualityScore(it.first) }.take(targetCount)
    }

    private suspend fun createImprovedSpeakerSample(
        audioChunksWithConfig: List<Triple<ByteArray, AudioConfig, Int>>
    ): Uri? = withContext(Dispatchers.IO) {
        if (audioChunksWithConfig.isEmpty()) return@withContext null

        try {
            val targetSampleRate = audioChunksWithConfig.groupingBy { it.third }.eachCount()
                .maxByOrNull { it.value }?.key ?: return@withContext null

            val targetConfig = audioChunksWithConfig.first { it.third == targetSampleRate }.second

            // Filter out very short chunks

            val filteredChunks = audioChunksWithConfig.filter { (chunk, config, _) ->
                val durationSec =
                    chunk.size.toFloat() / (config.sampleRateHz * (config.bitDepth.bits / 8))
                durationSec >= MIN_CHUNK_DURATION_SEC  // Use constant
            }

            val chunksToUse = filteredChunks.ifEmpty { audioChunksWithConfig }
            val selectedChunks = selectBestSegments(chunksToUse, SAMPLE_TARGET_SEGMENTS)

            val sampleStream = ByteArrayOutputStream()
            val bytesPerSample = targetConfig.bitDepth.bits / 8
            val minBytes = targetConfig.sampleRateHz * bytesPerSample * SAMPLE_MIN_DURATION_SEC
            val maxBytes = targetConfig.sampleRateHz * bytesPerSample * SAMPLE_MAX_DURATION_SEC

            val silenceBytes =
                ByteArray((targetSampleRate * SAMPLE_SILENCE_DURATION_MS / 1000) * bytesPerSample)  // Use constant

            for ((chunk, _, originalRate) in selectedChunks) {
                val processedChunk = if (originalRate != targetSampleRate) {
                    vadProcessor.resampleAudioChunk(chunk, originalRate, targetSampleRate)
                } else {
                    chunk
                }

                if (sampleStream.size() > 0) {
                    sampleStream.write(silenceBytes)  // Using the variable here
                }
                sampleStream.write(processedChunk)
                if (sampleStream.size() >= maxBytes) break
            }

            var audioData = sampleStream.toByteArray()

            if (audioData.size < minBytes && audioData.isNotEmpty()) {
                val repeatCount = (minBytes / audioData.size) + 1
                val repeatedStream = ByteArrayOutputStream()
                repeat(repeatCount) {
                    repeatedStream.write(audioData)
                    if (it < repeatCount - 1) {
                        val silenceBytes = ByteArray((targetSampleRate / 2) * bytesPerSample)
                        repeatedStream.write(silenceBytes)
                    }
                }
                audioData = repeatedStream.toByteArray().copyOf(minBytes)
            }

            if (audioData.size > maxBytes) {
                audioData = audioData.copyOf(maxBytes)
            }

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

    fun addSpeaker(name: String, unknownSpeaker: UnknownSpeaker) {
        viewModelScope.launch {
            val embedding = unknownSpeaker.averageEmbedding ?: speakerIdentifier.createEmbedding(
                unknownSpeaker.audioSegments
            )

            val permanentSampleUri = unknownSpeaker.sampleUri?.let { tempUri ->
                try {
                    val tempFile = File(tempUri.path!!)
                    val permanentFile = File(
                        context.filesDir, "speaker_samples/${name}_${UUID.randomUUID()}.wav"
                    )
                    permanentFile.parentFile?.mkdirs()
                    tempFile.copyTo(permanentFile, overwrite = true)
                    Uri.fromFile(permanentFile)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to copy speaker sample")
                    tempUri
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
                _uiState.value = currentState.copy(isLoading = true)
            } else {
                _uiState.value = SpeakerDiscoveryUiState.LoadingFiles
            }

            processedFiles.clear()
            Timber.d("Cleared processed files list. Next scan will process all files.")

            try {
                val recordingsDirUri = FileSavingUtils.getCachedGrantedUri(context)
                    ?: throw IOException("Please set a recordings directory first.")

                val allWavFiles =
                    DocumentFile.fromTreeUri(context, recordingsDirUri)?.listFiles()?.filter {
                        it.name?.endsWith(".wav") == true && it.name?.startsWith(
                            ".trashed"
                        ) == false
                    }?.map {
                        RecordingFile(
                            name = it.name ?: "Unknown File",
                            uri = it.uri,
                            sizeMb = it.length() / (1024f * 1024f),
                            lastModified = it.lastModified()
                        )
                    }?.sortedByDescending { it.lastModified } ?: emptyList()

                _uiState.value = SpeakerDiscoveryUiState.FileSelection(
                    allFiles = allWavFiles,
                    selectedFileUris = allWavFiles.map { it.uri }.toSet(),
                    processedFileUris = emptySet(),
                    isLoading = false
                )
            } catch (e: Exception) {
                Timber.e(e, "Error during reset")
                _uiState.value =
                    SpeakerDiscoveryUiState.Error("Failed to reload files after reset.")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanningJob?.cancel()
    }
}

