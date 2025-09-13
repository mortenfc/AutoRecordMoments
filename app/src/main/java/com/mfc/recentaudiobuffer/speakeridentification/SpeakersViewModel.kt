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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

data class UnknownSpeaker(
    val id: String,
    val audioSegments: List<ByteArray>,
    val sampleUri: Uri? = null,
    val averageEmbedding: SpeakerEmbedding? = null,
    val debugInfo: SpeakerDebugInfo = SpeakerDebugInfo()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UnknownSpeaker

        if (id != other.id) return false
        if (audioSegments != other.audioSegments) return false
        if (sampleUri != other.sampleUri) return false
        if (!averageEmbedding.contentEquals(other.averageEmbedding)) return false
        if (debugInfo != other.debugInfo) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + audioSegments.hashCode()
        result = 31 * result + (sampleUri?.hashCode() ?: 0)
        result = 31 * result + (averageEmbedding?.contentHashCode() ?: 0)
        result = 31 * result + debugInfo.hashCode()
        return result
    }
}

data class SpeakerDebugInfo(
    val clusterSize: Int = 0,
    val originalClusterSize: Int = 0,
    val purityScore: Float = 0f,
    val variance: Float = 0f,
    val averageSimilarityToCentroid: Float = 0f,
    val discardedSegments: Int = 0,
    val clusteringMethod: String = "",
    val mergeHistory: List<String> = emptyList(),
    val filterReasons: List<String> = emptyList()
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
    private val vadProcessor: VADProcessor,
    private val clusteringConfig: SpeakerClusteringConfig
) : ViewModel() {

    private var lastScannedFileUris: Set<Uri> = emptySet()

    val speakers: StateFlow<List<Speaker>> = speakerRepository.getAllSpeakers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow<SpeakerDiscoveryUiState>(SpeakerDiscoveryUiState.Idle)
    val uiState = _uiState.asStateFlow()

    val config = clusteringConfig

    private var scanningJob: Job? = null
    private val processedFiles = java.util.Collections.synchronizedSet(mutableSetOf<String>())


    fun rescanWithCurrentFiles() {
        viewModelScope.launch {
            when (val currentState = _uiState.value) {
                is SpeakerDiscoveryUiState.FileSelection -> {
                    startScan(currentState.selectedFileUris)
                }

                is SpeakerDiscoveryUiState.Success -> {
                    if (lastScannedFileUris.isNotEmpty()) {
                        processedFiles.removeAll(lastScannedFileUris.map { it.toString() }.toSet())
                        startScan(lastScannedFileUris)
                    } else {
                        prepareFileSelection()
                    }
                }

                else -> prepareFileSelection()
            }
        }
    }

    private suspend fun improvedClusterSegments(
        segments: List<UnidentifiedSegment>
    ): Map<String, List<UnidentifiedSegment>> = withContext(Dispatchers.Default) {
        val params = clusteringConfig.parameters.value
        Timber.d("\n${clusteringConfig.exportCurrentConfig()}")

        val validSegments = segments.filter { it.embedding.isNotEmpty() }
        Timber.d("=== CLUSTERING START (Hybrid) ===")
        Timber.d("Total segments: ${segments.size}, Valid segments: ${validSegments.size}")

        if (validSegments.size < params.minClusterSize) {
            Timber.d("❌ Not enough valid segments (${validSegments.size} < ${params.minClusterSize}). Aborting clustering.")
            return@withContext emptyMap()
        }

        Timber.d("\n📊 STAGE 1: PRIMARY CLUSTERING (DBSCAN)")
        val (dbscanClusters, leftovers) = dbscanClusteringPass(
            validSegments,
            eps = params.dbscanEps,
            minPts = params.highConfidenceMinPts,
            passName = "DBSCAN"
        )
        Timber.d("✅ DBSCAN Pass Results: ${dbscanClusters.size} clusters, ${leftovers.size} segments leftover.")

        var ahcClusters: Map<String, List<UnidentifiedSegment>> = emptyMap()
        if (leftovers.size >= params.minClusterSize) {
            Timber.d("\n📊 STAGE 2: LEFTOVER CLUSTERING (AHC) on ${leftovers.size} segments")
            ahcClusters = agglomerativeClustering(
                leftovers, params.leftoverAhcThreshold
            )
            Timber.d("✅ AHC Pass Results: ${ahcClusters.size} new clusters found.")
        }

        val allClusters = dbscanClusters + ahcClusters

        val result = allClusters.filter { (_, segments) ->
            val keep = segments.size >= params.minClusterSize
            if (!keep) {
                Timber.d("  ❌ Filtered out small cluster: only ${segments.size} segments (min: ${params.minClusterSize})")
            }
            keep
        }.entries.sortedByDescending { it.value.size }.mapIndexed { index, entry ->
            "speaker_${index + 1}" to entry.value
        }.toMap()

        Timber.d("\n=== CLUSTERING COMPLETE: ${result.size} final speakers before quality checks ===")
        return@withContext result
    }

    private suspend fun agglomerativeClustering(
        segments: List<UnidentifiedSegment>, distanceThreshold: Float
    ): Map<String, List<UnidentifiedSegment>> = withContext(Dispatchers.Default) {

        if (segments.isEmpty()) return@withContext emptyMap()

        val clusters = segments.map { mutableListOf(it) }.toMutableList()
        val centroids = clusters.map {
            speakerIdentifier.averageEmbeddings(it.map { s -> s.embedding })
        }.toMutableList()

        if (clusters.size <= 1) {
            val singleClusterContent: List<UnidentifiedSegment> =
                clusters.firstOrNull() ?: emptyList()
            return@withContext mapOf("cluster_1" to singleClusterContent)
        }
        var mergeCount = 1

        while (true) {
            coroutineContext.ensureActive()
            if (clusters.size <= 1) break

            var minDistance = Float.MAX_VALUE
            var bestPair = Pair(-1, -1)

            for (i in 0 until centroids.size) {
                for (j in (i + 1) until centroids.size) {
                    val dist = 1.0f - speakerIdentifier.calculateCosineSimilarity(
                        centroids[i], centroids[j]
                    )
                    if (dist < minDistance) {
                        minDistance = dist
                        bestPair = Pair(i, j)
                    }
                }
            }

            if (minDistance > distanceThreshold || bestPair.first == -1) {
                Timber.d(
                    "✅ Halting merge: Min distance %.3f > threshold %.3f".format(
                        minDistance, distanceThreshold
                    )
                )
                break
            }

            val (i, j) = bestPair
            Timber.d(
                "    🔗 Merge #$mergeCount: cluster $j -> cluster $i (distance: %.3f)".format(
                    minDistance
                )
            )

            clusters[i].addAll(clusters[j])
            centroids[i] = speakerIdentifier.averageEmbeddings(
                clusters[i].map { it.embedding })

            clusters.removeAt(j)
            centroids.removeAt(j)
            mergeCount++
        }

        return@withContext clusters.mapIndexed { index, segmentList ->
            "cluster_ahc_${index + 1}" to segmentList
        }.toMap()
    }


    private suspend fun createUnknownSpeakerObjects(
        clusteredSegments: Map<String, List<UnidentifiedSegment>>
    ): List<UnknownSpeaker> {
        if (clusteredSegments.isEmpty()) return emptyList()

        val params = clusteringConfig.parameters.value
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

            val debugInfo = SpeakerDebugInfo(
                originalClusterSize = segments.size,
                clusteringMethod = if (id.contains("DBSCAN")) "DBSCAN" else "AHC",
                filterReasons = mutableListOf(),
                mergeHistory = emptyList()
            )

            if (segments.size < params.minClusterSize) {
                continue
            }

            val clusterCentroid = speakerIdentifier.averageEmbeddings(segments.map { it.embedding })

            Timber.d("  🧹 Purity check for $id (threshold: ${params.clusterPurityThreshold})")
            val segmentSimilarities = segments.map { segment ->
                val similarity = speakerIdentifier.calculateCosineSimilarity(
                    segment.embedding, clusterCentroid
                )
                segment to similarity
            }

            val pureSegmentSimilarities =
                segmentSimilarities.filter { it.second >= params.clusterPurityThreshold }
            val pureSegments = pureSegmentSimilarities.map { it.first }

            val avgSimilarity =
                if (pureSegmentSimilarities.isNotEmpty()) pureSegmentSimilarities.map { it.second }
                    .average().toFloat() else 0f
            val discardedCount = segments.size - pureSegments.size

            if (pureSegments.size < params.minClusterSize) {
                val reason =
                    "Too few pure segments after filtering (${pureSegments.size} < ${params.minClusterSize})"
                (debugInfo.filterReasons as MutableList).add(reason)
                Timber.d("  ❌ REJECTED $id: $reason")
                continue
            }

            if (pureSegments.size <= params.smallClusterSizeThreshold && avgSimilarity < params.minPurityForSmallCluster) {
                val reason =
                    "Small cluster failed purity check: size=${pureSegments.size}, purity=%.2f < %.2f".format(
                        avgSimilarity, params.minPurityForSmallCluster
                    )
                (debugInfo.filterReasons as MutableList).add(reason)
                Timber.d("  ❌ REJECTED $id: $reason")
                continue
            }

            val variance = calculateClusterVariance(
                pureSegments.map { it.embedding }, clusterCentroid
            )

            val dynamicMaxVariance =
                params.baseMaxClusterVariance * (1.0f + params.varianceGrowthFactor * log10(
                    pureSegments.size.toFloat()
                ))


            if (variance > dynamicMaxVariance) {
                val reason = "High variance: %.5f > %.5f (dynamic threshold)".format(
                    variance, dynamicMaxVariance
                )
                (debugInfo.filterReasons as MutableList).add(reason)
                Timber.d("  ❌ REJECTED $id: $reason")
                continue
            }

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
                val avgEmbedding = speakerIdentifier.averageEmbeddings(
                    pureSegments.map { it.embedding })

                finalSpeakers.add(
                    UnknownSpeaker(
                        id = id,
                        audioSegments = audioChunksWithConfig.map { it.first },
                        sampleUri = sampleUri,
                        averageEmbedding = avgEmbedding,
                        debugInfo = debugInfo.copy(
                            clusterSize = pureSegments.size,
                            purityScore = avgSimilarity,
                            variance = variance,
                            averageSimilarityToCentroid = avgSimilarity,
                            discardedSegments = discardedCount
                        )
                    )
                )
                Timber.d("  ✅ ACCEPTED: Speaker $id")
                Timber.d("    - Final segments: ${pureSegments.size}")
                Timber.d("    - Avg purity: ${(avgSimilarity * 100).toInt()}%")
                Timber.d("    - Variance: %.5f".format(variance))
            }
        }

        return finalSpeakers
    }

    private suspend fun dbscanClusteringPass(
        segments: List<UnidentifiedSegment>, eps: Float, minPts: Int, passName: String = "UNNAMED"
    ): Pair<Map<String, MutableList<UnidentifiedSegment>>, List<UnidentifiedSegment>> =
        withContext(Dispatchers.Default) {
            Timber.d("  🔍 DBSCAN Pass '$passName' starting with ${segments.size} segments")

            val labels = mutableMapOf<UnidentifiedSegment, Int>()
            var clusterId = 0

            for ((index, segment) in segments.withIndex()) {
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
                    if (currentLabel == -1) {
                        labels[current] = clusterId
                    }
                    if (currentLabel != 0) continue

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
                    clusters["cluster_${passName}_$id"] = segmentList
                }
            }

            return@withContext Pair(clusters, noise)
        }

    private suspend fun findNeighbors(
        segment: UnidentifiedSegment, allSegments: List<UnidentifiedSegment>, eps: Float
    ): List<UnidentifiedSegment> {
        val neighbors = mutableListOf<UnidentifiedSegment>()
        for (other in allSegments) {
            coroutineContext.ensureActive()
            val distance = 1.0f - speakerIdentifier.calculateCosineSimilarity(
                segment.embedding, other.embedding
            )
            if (distance <= eps) {
                neighbors.add(other)
            }
        }
        return neighbors
    }

    fun exportDebugReport(): String {
        val state = _uiState.value
        val report = StringBuilder()

        report.appendLine("=== SPEAKER DISCOVERY DEBUG REPORT ===")
        report.appendLine("Generated: ${java.util.Date()}")
        report.appendLine()
        report.append(clusteringConfig.exportCurrentConfig())
        report.appendLine()

        if (state is SpeakerDiscoveryUiState.Success) {
            report.appendLine("=== DISCOVERED SPEAKERS ===")
            state.unknownSpeakers.forEach { speaker ->
                report.appendLine("\nSpeaker: ${speaker.id}")
                report.appendLine("  Purity Score: ${(speaker.debugInfo.averageSimilarityToCentroid * 100).toInt()}%")
                report.appendLine("  Cluster Size: ${speaker.debugInfo.clusterSize}")
                report.appendLine("  Original Size: ${speaker.debugInfo.originalClusterSize}")
                report.appendLine("  Discarded: ${speaker.debugInfo.discardedSegments}")
                report.appendLine("  Variance: %.5f".format(speaker.debugInfo.variance))
                report.appendLine("  Method: ${speaker.debugInfo.clusteringMethod}")

                if (speaker.debugInfo.filterReasons.isNotEmpty()) {
                    report.appendLine("  Filters:")
                    speaker.debugInfo.filterReasons.forEach { reason ->
                        report.appendLine("    - $reason")
                    }
                }
            }
        }

        report.appendLine("\n=== END REPORT ===")
        return report.toString()
    }

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
                    processedFileUris = processedFiles.toSet()
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
        lastScannedFileUris = selectedFileUris
        scanningJob?.cancel()
        scanningJob = viewModelScope.launch {
            var allUnidentifiedSegments: List<UnidentifiedSegment> = emptyList()

            try {
                _uiState.value = SpeakerDiscoveryUiState.Scanning(0f, 0, selectedFileUris.size)
                allUnidentifiedSegments = collectAllUnidentifiedSegmentsParallel(selectedFileUris)
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
                } else if (coroutineContext.isActive) {
                    _uiState.value = SpeakerDiscoveryUiState.Success(emptyList())
                }
            }
        }
    }

    private suspend fun collectAllUnidentifiedSegmentsParallel(
        filesToProcessUris: Set<Uri>
    ): List<UnidentifiedSegment> = coroutineScope {
        if (filesToProcessUris.isEmpty()) return@coroutineScope emptyList()

        val knownSpeakers = speakers.value
        val totalFiles = filesToProcessUris.size
        val filesCompleted = AtomicInteger(0)

        val deferredResults = filesToProcessUris.map { uri ->
            async(Dispatchers.IO) {
                val fileUri = uri.toString()
                try {
                    val audioBytes =
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (audioBytes != null) {
                        val audioBuffer = ByteBuffer.wrap(audioBytes)
                        val audioConfig = WavUtils.readWavHeader(audioBytes)
                        if (audioConfig != null) {
                            val segments = diarizationProcessor.process(
                                audioBuffer, fileUri, knownSpeakers, audioConfig
                            )
                            processedFiles.add(fileUri)
                            return@async segments
                        }
                    }
                } catch (e: CancellationException) {
                    Timber.d("Cancelled while processing file: $fileUri")
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Error processing file: $fileUri")
                    processedFiles.add(fileUri)
                } finally {
                    val completedCount = filesCompleted.incrementAndGet()
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            if (it is SpeakerDiscoveryUiState.Scanning) {
                                it.copy(
                                    progress = completedCount.toFloat() / totalFiles,
                                    currentFile = completedCount,
                                    totalFiles = totalFiles
                                )
                            } else it
                        }
                    }
                }
                emptyList<UnidentifiedSegment>()
            }
        }

        deferredResults.awaitAll().flatten()
    }

    private fun calculateClusterVariance(
        embeddings: List<SpeakerEmbedding>, centroid: SpeakerEmbedding
    ): Float {
        if (embeddings.isEmpty()) return 0f

        val distances = embeddings.map { embedding ->
            1.0f - speakerIdentifier.calculateCosineSimilarity(embedding, centroid)
        }

        val mean = distances.average().toFloat()
        val variance = distances.map { d ->
            val diff = d - mean
            diff * diff
        }.average().toFloat()

        return variance
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

            val params = clusteringConfig.parameters.value

            val filteredChunks = audioChunksWithConfig.filter { (chunk, config, _) ->
                val durationSec =
                    chunk.size.toFloat() / (config.sampleRateHz * (config.bitDepth.bits / 8))
                durationSec >= params.minChunkDurationSec
            }

            val chunksToUse = filteredChunks.ifEmpty { audioChunksWithConfig }
            val selectedChunks = selectBestSegments(chunksToUse, params.sampleTargetSegments)

            val sampleStream = ByteArrayOutputStream()
            val bytesPerSample = targetConfig.bitDepth.bits / 8
            val minBytes =
                (targetConfig.sampleRateHz * bytesPerSample * params.sampleMinDurationSec).toInt()
            val maxBytes =
                (targetConfig.sampleRateHz * bytesPerSample * params.sampleMaxDurationSec).toInt()

            val silenceBytes =
                ByteArray(((targetSampleRate * params.sampleSilenceDurationMs / 1000.0).toInt()) * bytesPerSample)

            for ((chunk, _, originalRate) in selectedChunks) {
                val processedChunk = if (originalRate != targetSampleRate) {
                    vadProcessor.resampleAudioChunk(chunk, originalRate, targetSampleRate)
                } else {
                    chunk
                }

                if (sampleStream.size() > 0) {
                    sampleStream.write(silenceBytes)
                }
                sampleStream.write(processedChunk)
                if (sampleStream.size() >= maxBytes) break
            }

            var audioData = sampleStream.toByteArray()

            if (audioData.size < minBytes && audioData.isNotEmpty()) {
                val repeatCount = (minBytes.toFloat() / audioData.size).toInt() + 1
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

