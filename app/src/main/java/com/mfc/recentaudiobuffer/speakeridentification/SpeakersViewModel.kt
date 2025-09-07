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

    // Remove companion object constants - now using config

    private var lastScannedFileUris: Set<Uri> = emptySet()

    val speakers: StateFlow<List<Speaker>> = speakerRepository.getAllSpeakers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow<SpeakerDiscoveryUiState>(SpeakerDiscoveryUiState.Idle)
    val uiState = _uiState.asStateFlow()

    // Expose config for UI
    val config = clusteringConfig

    private var scanningJob: Job? = null
    private val processedFiles = mutableSetOf<String>()

    fun rescanWithCurrentFiles() {
        viewModelScope.launch {
            when (val currentState = _uiState.value) {
                is SpeakerDiscoveryUiState.FileSelection -> {
                    startScan(currentState.selectedFileUris)
                }

                is SpeakerDiscoveryUiState.Success -> {
                    // Re-scan the same files that produced current results
                    if (lastScannedFileUris.isNotEmpty()) {
                        // Clear processed files to force re-scan
                        processedFiles.removeAll(lastScannedFileUris.map { it.toString() })
                        startScan(lastScannedFileUris)
                    } else {
                        prepareFileSelection() // Fallback
                    }
                }

                else -> prepareFileSelection()
            }
        }
    }

    // Updated improvedClusterSegments to use config
    private suspend fun improvedClusterSegments(
        segments: List<UnidentifiedSegment>
    ): Map<String, List<UnidentifiedSegment>> = withContext(Dispatchers.Default) {
        val params = clusteringConfig.parameters.value

        // Log current configuration
        Timber.d("\n${clusteringConfig.exportCurrentConfig()}")

        val validSegments = segments.filter { it.embedding.isNotEmpty() }

        Timber.d("=== CLUSTERING START ===")
        Timber.d("Total segments: ${segments.size}, Valid segments: ${validSegments.size}")

        if (validSegments.size < params.minClusterSize) {
            Timber.d("❌ Not enough valid segments (${validSegments.size} < ${params.minClusterSize}). Aborting clustering.")
            return@withContext emptyMap()
        }

        // Stage 1: Primary Clustering with config params
        Timber.d("\n📊 STAGE 1: PRIMARY CLUSTERING")
        Timber.d("Parameters: eps=${params.dbscanEps}, minPts=${params.dbscanMinPts}")
        val (initialClusters, noisePoints) = dbscanClusteringPass(
            validSegments,
            eps = params.dbscanEps,
            minPts = params.dbscanMinPts,
            passName = "PRIMARY"
        )

        // Continue with rest of clustering using params...
        // (Same logic but replace constants with params.xxx)

        Timber.d("✅ Primary Results: ${initialClusters.size} clusters, ${noisePoints.size} noise points")

        // Stage 2: Re-cluster noise with config params
        val finalClusters = initialClusters.toMutableMap()
        val noiseToTotalRatio =
            if (validSegments.isNotEmpty()) noisePoints.size.toFloat() / validSegments.size else 0f

        val shouldReclusterNoise =
            noisePoints.size >= params.minNoiseForReclustering && (noiseToTotalRatio > params.noiseRatioThreshold || initialClusters.size <= 5)

        if (shouldReclusterNoise) {
            Timber.d("🔄 Re-clustering noise with stricter params: eps=${params.noiseEps}, minPts=${params.noiseMinPts}")
            val (noiseClusters, remainingNoise) = dbscanClusteringPass(
                noisePoints, eps = params.noiseEps, minPts = params.noiseMinPts, passName = "NOISE"
            )

            noiseClusters.values.forEach { newCluster ->
                if (newCluster.size >= params.minClusterSize) {
                    val newId = "cluster_noise_${UUID.randomUUID().toString().take(8)}"
                    finalClusters[newId] = newCluster
                    Timber.d("  ✅ Added noise cluster $newId with ${newCluster.size} segments")
                } else {
                    Timber.d("  ❌ Rejected noise cluster with only ${newCluster.size} segments (min: ${params.minClusterSize})")
                }
            }
        }

        // Stage 3: Merge similar clusters with config threshold
        val mergedClusters = if (finalClusters.size > 1) {
            mergeSimilarClusters(finalClusters, params.finalMergeThreshold)
        } else {
            finalClusters
        }

        // Stage 4: Filter and format
        val result = mergedClusters.filter { (id, segments) ->
            val keep = segments.size >= params.minClusterSize
            if (!keep) {
                Timber.d("  ❌ Filtered out $id: only ${segments.size} segments (min: ${params.minClusterSize})")
            }
            keep
        }.entries.sortedByDescending { it.value.size }.mapIndexed { index, entry ->
            "speaker_${index + 1}" to entry.value
        }.toMap()

        Timber.d("\n=== CLUSTERING COMPLETE ===")
        return@withContext result
    }

    // Updated createUnknownSpeakerObjects with detailed debug info
    private suspend fun createUnknownSpeakerObjects(
        clusteredSegments: Map<String, List<UnidentifiedSegment>>
    ): List<UnknownSpeaker> {
        if (clusteredSegments.isEmpty()) return emptyList()

        val params = clusteringConfig.parameters.value
        val finalSpeakers = mutableListOf<UnknownSpeaker>()
        val audioDataCache = mutableMapOf<String, Pair<ByteArray, AudioConfig>>()

        // Pre-load audio data
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
                clusteringMethod = if (id.contains("noise")) "NOISE_PASS" else "PRIMARY_PASS",
                filterReasons = mutableListOf(),
                mergeHistory = mutableListOf() // Track merges if available
            )

            if (segments.size < params.minClusterSize) {
                continue
            }

            // Calculate cluster centroid
            val clusterCentroid = speakerIdentifier.averageEmbeddings(segments.map { it.embedding })

            // Purity check with config threshold
            Timber.d("  🧹 Purity check (threshold: ${params.clusterPurityThreshold})")
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
                Timber.d("  ❌ REJECTED: $reason")
                continue
            }

            // Calculate variance with config threshold
            val variance = calculateClusterVariance(
                pureSegments.map { it.embedding }, clusterCentroid
            )

            if (variance > params.maxClusterVariance) {
                val reason =
                    "High variance: %.5f > %.5f".format(variance, params.maxClusterVariance)
                (debugInfo.filterReasons as MutableList).add(reason)
                Timber.d("  ❌ REJECTED: $reason")
                continue
            }

            // Extract audio chunks
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
                Timber.d("    - Avg similarity: ${(avgSimilarity * 100).toInt()}%")
                Timber.d("    - Variance: %.5f".format(variance))
            }
        }

        return finalSpeakers
    }

    // Add method to export debug report
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
                report.appendLine("  Confidence: ${(speaker.debugInfo.averageSimilarityToCentroid * 100).toInt()}%")
                report.appendLine("  Cluster Size: ${speaker.debugInfo.clusterSize}")
                report.appendLine("  Original Size: ${speaker.debugInfo.originalClusterSize}")
                report.appendLine("  Discarded: ${speaker.debugInfo.discardedSegments}")
                report.appendLine("  Purity: %.3f".format(speaker.debugInfo.purityScore))
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
        lastScannedFileUris = selectedFileUris
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

    // Enhanced dbscanClusteringPass with better logging
    private suspend fun dbscanClusteringPass(
        segments: List<UnidentifiedSegment>, eps: Float, minPts: Int, passName: String = "UNNAMED"
    ): Pair<Map<String, MutableList<UnidentifiedSegment>>, List<UnidentifiedSegment>> =
        withContext(Dispatchers.Default) {
            Timber.d("  🔍 DBSCAN Pass '$passName' starting with ${segments.size} segments")

            val labels = mutableMapOf<UnidentifiedSegment, Int>()
            var clusterId = 0
            var noiseCount = 0

            for ((index, segment) in segments.withIndex()) {
                if (labels.containsKey(segment)) continue

                val neighbors = findNeighbors(segment, segments, eps)

                if (neighbors.size < minPts) {
                    labels[segment] = -1
                    noiseCount++
                    if (index % 10 == 0) { // Log every 10th noise point to avoid spam
                        Timber.v("    Segment $index marked as noise (${neighbors.size} neighbors < $minPts minPts)")
                    }
                    continue
                }

                clusterId++
                labels[segment] = clusterId
                Timber.d("    🎯 New cluster $clusterId started with ${neighbors.size} neighbors")

                val seedSet = neighbors.toMutableSet()
                seedSet.remove(segment)
                var expansionCount = 0

                while (seedSet.isNotEmpty()) {
                    val current = seedSet.first()
                    seedSet.remove(current)

                    val currentLabel = labels.getOrDefault(current, 0)
                    if (currentLabel == -1) {
                        labels[current] = clusterId
                        expansionCount++
                    }
                    if (currentLabel != 0) continue

                    labels[current] = clusterId
                    val currentNeighbors = findNeighbors(current, segments, eps)
                    if (currentNeighbors.size >= minPts) {
                        seedSet.addAll(currentNeighbors)
                    }
                }

                Timber.d("    Cluster $clusterId expanded by $expansionCount points")
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

            Timber.d("  ✅ DBSCAN Pass '$passName' complete: ${clusters.size} clusters, $noiseCount noise")

            return@withContext Pair(clusters, noise)
        }

    // Enhanced mergeSimilarClusters with detailed logging
    private fun mergeSimilarClusters(
        clusters: Map<String, MutableList<UnidentifiedSegment>>, threshold: Float
    ): Map<String, List<UnidentifiedSegment>> {
        if (clusters.size <= 1) return clusters

        Timber.d("  🔀 Starting merge with threshold $threshold")

        val centroids = clusters.mapValues { (id, segments) ->
            val centroid = speakerIdentifier.averageEmbeddings(segments.map { it.embedding })
            Timber.v("    Centroid for $id computed from ${segments.size} segments")
            centroid
        }.toMutableMap()

        val clusterData = clusters.toMutableMap()
        val mergeHistory = mutableListOf<String>()
        var mergeCount = 0

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
                mergeCount++
                val mergeMsg =
                    "Merge #$mergeCount: $c2 -> $c1 (similarity: ${(bestSimilarity * 100).toInt()}%)"
                mergeHistory.add(mergeMsg)
                Timber.d("    🔗 $mergeMsg")
                Timber.d("       Size change: ${clusterData[c1]!!.size} + ${clusterData[c2]!!.size} = ${clusterData[c1]!!.size + clusterData[c2]!!.size}")

                clusterData.getValue(c1).addAll(clusterData.getValue(c2))
                centroids[c1] = speakerIdentifier.averageEmbeddings(
                    clusterData.getValue(c1).map { it.embedding })
                clusterData.remove(c2)
                centroids.remove(c2)
                performedMerge = true
            } else if (c1 != null && c2 != null) {
                Timber.v("    Best unmerged similarity: $c1 <-> $c2 = ${(bestSimilarity * 100).toInt()}% (below threshold)")
            }
        }

        Timber.d("  ✅ Merging complete: $mergeCount merges performed")
        return clusterData
    }


    private fun findNeighbors(
        segment: UnidentifiedSegment, allSegments: List<UnidentifiedSegment>, eps: Float
    ): List<UnidentifiedSegment> {
        return allSegments.filter { other ->
            val distance = 1.0f - speakerIdentifier.calculateCosineSimilarity(
                segment.embedding, other.embedding
            )
            distance <= eps
        }
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

            // Filter out very short chunks

            val filteredChunks = audioChunksWithConfig.filter { (chunk, config, _) ->
                val durationSec =
                    chunk.size.toFloat() / (config.sampleRateHz * (config.bitDepth.bits / 8))
                durationSec >= params.minChunkDurationSec  // Use constant
            }

            val chunksToUse = filteredChunks.ifEmpty { audioChunksWithConfig }
            val selectedChunks = selectBestSegments(chunksToUse, params.sampleTargetSegments)

            val sampleStream = ByteArrayOutputStream()
            val bytesPerSample = targetConfig.bitDepth.bits / 8
            val minBytes = targetConfig.sampleRateHz * bytesPerSample * params.sampleMinDurationSec
            val maxBytes = targetConfig.sampleRateHz * bytesPerSample * params.sampleMaxDurationSec

            val silenceBytes =
                ByteArray((targetSampleRate * params.sampleSilenceDurationMs / 1000) * bytesPerSample)  // Use constant

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

