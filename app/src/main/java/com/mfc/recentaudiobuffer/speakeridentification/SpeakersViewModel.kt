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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mfc.recentaudiobuffer.AudioConfig
import com.mfc.recentaudiobuffer.FileSavingUtils
import com.mfc.recentaudiobuffer.R
import com.mfc.recentaudiobuffer.VADProcessor
import com.mfc.recentaudiobuffer.WavUtils
import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

@Module
@InstallIn(SingletonComponent::class)
object WorkManagerModule {
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }
}

// Key Change: Helper class for returning results from the parallel cluster search.
private data class SearchResult(val distance: Float, val pair: Pair<Int, Int>)

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
        if (sampleUri != other.sampleUri) return false
        if (debugInfo != other.debugInfo) return false
        if (averageEmbedding != null) {
            if (other.averageEmbedding == null) return false
            if (!averageEmbedding.contentEquals(other.averageEmbedding)) return false
        } else if (other.averageEmbedding != null) return false

        if (audioSegments.size != other.audioSegments.size) return false
        for (i in audioSegments.indices) {
            if (!audioSegments[i].contentEquals(other.audioSegments[i])) return false
        }

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + audioSegments.sumOf { it.contentHashCode() }
        result = 31 * result + (sampleUri?.hashCode() ?: 0)
        result = 31 * result + (averageEmbedding?.contentHashCode() ?: 0)
        result = 31 * result + debugInfo.hashCode()
        return result
    }
}


@Serializable
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

@Serializable
private data class SerializableUnknownSpeaker(
    val id: String,
    val audioSegmentsBase64: List<String>,
    val sampleUriString: String? = null,
    val averageEmbedding: List<Float>? = null,
    val debugInfo: SpeakerDebugInfo = SpeakerDebugInfo()
)

@HiltViewModel
class SpeakersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val speakerRepository: SpeakerRepository,
    private val speakerIdentifier: SpeakerIdentifier,
    private val workManager: WorkManager,
    private val clusteringConfig: SpeakerClusteringConfig
) : ViewModel() {

    private var lastScannedFileUris: Set<Uri> = emptySet()

    val speakers: StateFlow<List<Speaker>> = speakerRepository.getAllSpeakers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow<SpeakerDiscoveryUiState>(SpeakerDiscoveryUiState.Idle)
    val uiState = _uiState.asStateFlow()

    val config = clusteringConfig

    private val processedFiles = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var workObserverJob: Job? = null

    init {
        // Check for and reconnect to an ongoing scan when the ViewModel is created.
        reconnectToRunningScan()
    }

    private fun reconnectToRunningScan() {
        viewModelScope.launch {
            val workInfos = workManager.getWorkInfosForUniqueWork("SpeakerScan").get()
            val runningWork = workInfos.find { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            if (runningWork != null) {
                // A scan is already in progress, let's observe it.
                observeWork(runningWork.id)
                // Immediately handle the current state so the UI updates without delay.
                handleWorkInfo(runningWork)
            }
        }
    }

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
        if (selectedFileUris.isEmpty()) return
        lastScannedFileUris = selectedFileUris

        val inputData = workDataOf(
            "URIS" to selectedFileUris.map { it.toString() }.toTypedArray(),
            "PROCESSED_URIS" to processedFiles.toTypedArray()
        )

        val speakerScanRequest =
            OneTimeWorkRequestBuilder<SpeakerScanWorker>().setInputData(inputData).build()

        workManager.enqueueUniqueWork(
            "SpeakerScan", ExistingWorkPolicy.REPLACE, speakerScanRequest
        )

        observeWork(speakerScanRequest.id)
    }

    private fun observeWork(workId: UUID) {
        workObserverJob?.cancel()
        workObserverJob = viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { workInfo ->
                handleWorkInfo(workInfo)
            }
        }
    }

    private fun handleWorkInfo(workInfo: WorkInfo?) {
        if (workInfo == null) return

        when (workInfo.state) {
            WorkInfo.State.RUNNING -> {
                val stage = workInfo.progress.getString("STAGE")
                if (stage == "CLUSTERING") {
                    _uiState.value = SpeakerDiscoveryUiState.Clustering
                } else {
                    val progress = workInfo.progress.getFloat("PROGRESS", 0f)
                    val current = workInfo.progress.getInt("CURRENT", 0)
                    val total = workInfo.progress.getInt("TOTAL", 0)
                    if (total > 0) { // Ensure we don't show 0/0
                        _uiState.value = SpeakerDiscoveryUiState.Scanning(progress, current, total)
                    }
                }
            }

            WorkInfo.State.SUCCEEDED -> {
                val newProcessed = workInfo.outputData.getStringArray("PROCESSED_URIS")
                newProcessed?.let { processedFiles.addAll(it) }

                val resultPath = workInfo.outputData.getString("RESULT_FILE_PATH")
                if (resultPath != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val resultFile = File(resultPath)
                            val jsonString = resultFile.readText()
                            val serializedSpeakers =
                                Json.decodeFromString<List<SerializableUnknownSpeaker>>(jsonString)
                            resultFile.delete() // Clean up temp file

                            val unknownSpeakers = serializedSpeakers.map { s ->
                                UnknownSpeaker(
                                    id = s.id,
                                    audioSegments = s.audioSegmentsBase64.map {
                                        Base64.getDecoder().decode(it)
                                    },
                                    sampleUri = s.sampleUriString?.toUri(),
                                    averageEmbedding = s.averageEmbedding?.toFloatArray(),
                                    debugInfo = s.debugInfo
                                )
                            }
                            withContext(Dispatchers.Main) {
                                _uiState.value = SpeakerDiscoveryUiState.Success(unknownSpeakers)
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to process scan results")
                            _uiState.value = SpeakerDiscoveryUiState.Error("Failed to read results")
                        }
                    }
                } else {
                    _uiState.value = SpeakerDiscoveryUiState.Success(emptyList())
                }
            }

            WorkInfo.State.FAILED -> {
                val message = workInfo.outputData.getString("ERROR_MESSAGE")
                _uiState.value =
                    SpeakerDiscoveryUiState.Error(message ?: "An unknown error occurred.")
            }

            WorkInfo.State.CANCELLED -> {
                if (_uiState.value is SpeakerDiscoveryUiState.Stopping || _uiState.value is SpeakerDiscoveryUiState.Scanning) {
                    _uiState.value = SpeakerDiscoveryUiState.Idle
                }
            }

            else -> {}
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
        workManager.cancelUniqueWork("SpeakerScan")
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
        workObserverJob?.cancel()
    }
}


@HiltWorker
class SpeakerScanWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val speakerRepository: SpeakerRepository,
    private val speakerIdentifier: SpeakerIdentifier,
    private val diarizationProcessor: DiarizationProcessor,
    private val vadProcessor: VADProcessor,
    private val clusteringConfig: SpeakerClusteringConfig
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "SpeakerScanChannel"
    }

    private val processedFiles = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    override suspend fun doWork(): Result {
        val uris = inputData.getStringArray("URIS")?.map { it.toUri() }?.toSet() ?: emptySet()
        val alreadyProcessed = inputData.getStringArray("PROCESSED_URIS")?.toSet() ?: emptySet()
        processedFiles.addAll(alreadyProcessed)

        if (uris.isEmpty()) {
            return Result.success()
        }

        setForeground(createForegroundInfo(uris.size))

        var allUnidentifiedSegments: List<UnidentifiedSegment> = emptyList()
        var result: Result = Result.success()

        try {
            allUnidentifiedSegments = collectAllUnidentifiedSegmentsParallel(uris)
        } catch (e: CancellationException) {
            Timber.i("Work was cancelled. Processing partial results.")
        } catch (e: Exception) {
            Timber.e(e, "Error during speaker scan worker")
            return Result.failure(workDataOf("ERROR_MESSAGE" to e.localizedMessage))
        } finally {
            withContext(NonCancellable) {
                if (allUnidentifiedSegments.isNotEmpty()) {
                    setProgress(workDataOf("STAGE" to "CLUSTERING"))
                    val clusteredSpeakers = improvedClusterSegments(allUnidentifiedSegments)
                    val finalUnknownSpeakers = createUnknownSpeakerObjects(clusteredSpeakers)

                    val resultFile = saveResultsToFile(finalUnknownSpeakers)

                    val outputData = workDataOf(
                        "RESULT_FILE_PATH" to resultFile.absolutePath,
                        "PROCESSED_URIS" to processedFiles.toTypedArray()
                    )
                    result = Result.success(outputData)
                } else {
                    val outputData =
                        workDataOf("PROCESSED_URIS" to processedFiles.toTypedArray())
                    result = Result.success(outputData)
                }
            }
        }
        return result
    }

    private suspend fun saveResultsToFile(speakers: List<UnknownSpeaker>): File =
        withContext(Dispatchers.IO) {
            val serializableSpeakers = speakers.map { u ->
                SerializableUnknownSpeaker(
                    id = u.id,
                    audioSegmentsBase64 = u.audioSegments.map {
                        Base64.getEncoder().encodeToString(it)
                    },
                    sampleUriString = u.sampleUri?.toString(),
                    averageEmbedding = u.averageEmbedding?.toList(),
                    debugInfo = u.debugInfo
                )
            }
            val jsonString = Json.encodeToString(serializableSpeakers)
            val file = File(context.cacheDir, "scan_result_${UUID.randomUUID()}.json")
            file.writeText(jsonString)
            return@withContext file
        }

    private fun createForegroundInfo(totalFiles: Int): ForegroundInfo {
        createNotificationChannel()
        val notification = createNotification("Preparing to scan $totalFiles files...")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Scanning Recordings...")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher_round).setOngoing(true)
            .setSilent(true) // Less intrusive
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Speaker Scanning"
            val descriptionText = "Notifications for ongoing speaker identification scans"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel =
                NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                    description = descriptionText
                }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }


    private suspend fun collectAllUnidentifiedSegmentsParallel(
        filesToProcessUris: Set<Uri>
    ): List<UnidentifiedSegment> = coroutineScope {
        if (filesToProcessUris.isEmpty()) return@coroutineScope emptyList()

        val knownSpeakers = speakerRepository.getAllSpeakers().first()
        val totalFiles = filesToProcessUris.size
        val filesCompleted = AtomicInteger(0)

        val deferredResults = filesToProcessUris.map { uri ->
            async(Dispatchers.IO) {
                kotlin.coroutines.coroutineContext.ensureActive()
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
                    throw e // Re-throw to be handled by the caller
                } catch (e: Exception) {
                    Timber.e(e, "Error processing file: $fileUri")
                    processedFiles.add(fileUri)
                } finally {
                    val completedCount = filesCompleted.incrementAndGet()
                    val progressData = workDataOf(
                        "PROGRESS" to completedCount.toFloat() / totalFiles,
                        "CURRENT" to completedCount,
                        "TOTAL" to totalFiles
                    )
                    setProgress(progressData)
                }
                emptyList<UnidentifiedSegment>()
            }
        }

        deferredResults.awaitAll().flatten()
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
            return@withContext clusters.firstOrNull()?.let { mapOf("cluster_ahc_1" to it) }
                ?: emptyMap()
        }
        var mergeCount = 1

        while (clusters.size > 1) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val bestResult = findBestPairParallel(centroids)

            if (bestResult.distance > distanceThreshold || bestResult.pair.first == -1) {
                Timber.d(
                    "✅ Halting merge: Min distance %.3f > threshold %.3f".format(
                        bestResult.distance, distanceThreshold
                    )
                )
                break
            }

            val (i, j) = if (bestResult.pair.first < bestResult.pair.second) bestResult.pair else bestResult.pair.second to bestResult.pair.first

            Timber.d(
                "    🔗 Merge #$mergeCount: cluster $j -> cluster $i (distance: %.3f)".format(
                    bestResult.distance
                )
            )

            clusters[i].addAll(clusters[j])
            centroids[i] = speakerIdentifier.averageEmbeddings(clusters[i].map { it.embedding })

            clusters.removeAt(j)
            centroids.removeAt(j)
            mergeCount++
        }

        return@withContext clusters.mapIndexed { index, segmentList ->
            "cluster_ahc_${index + 1}" to segmentList
        }.toMap()
    }

    private suspend fun findBestPairParallel(centroids: List<SpeakerEmbedding>): SearchResult =
        coroutineScope {
            val numCentroids = centroids.size
            if (numCentroids < 2) return@coroutineScope SearchResult(Float.MAX_VALUE, Pair(-1, -1))

            val pairs = mutableListOf<Pair<Int, Int>>()
            for (i in 0 until numCentroids) {
                for (j in (i + 1) until numCentroids) {
                    pairs.add(Pair(i, j))
                }
            }

            val availableCores = Runtime.getRuntime().availableProcessors()
            val chunkSize =
                if (pairs.isNotEmpty()) ceil(pairs.size.toDouble() / availableCores).toInt() else 1

            val chunks = pairs.chunked(chunkSize.coerceAtLeast(1))

            val deferredBests = chunks.map { chunk ->
                async(Dispatchers.Default) {
                    var localMinDistance = Float.MAX_VALUE
                    var localBestPair = Pair(-1, -1)

                    for (pair in chunk) {
                        val dist = 1.0f - speakerIdentifier.calculateCosineSimilarity(
                            centroids[pair.first], centroids[pair.second]
                        )
                        if (dist < localMinDistance) {
                            localMinDistance = dist
                            localBestPair = pair
                        }
                    }
                    SearchResult(localMinDistance, localBestPair)
                }
            }

            val bests = deferredBests.awaitAll()
            bests.minByOrNull { it.distance } ?: SearchResult(Float.MAX_VALUE, Pair(-1, -1))
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
                    kotlin.coroutines.coroutineContext.ensureActive()
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
            kotlin.coroutines.coroutineContext.ensureActive()

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

            val discardedCount = segments.size - pureSegments.size

            if (pureSegments.size < params.minClusterSize) {
                val reason =
                    "Too few pure segments after filtering (${pureSegments.size} < ${params.minClusterSize})"
                (debugInfo.filterReasons as MutableList).add(reason)
                Timber.d("  ❌ REJECTED $id: $reason")
                continue
            }

            val variance = calculateClusterVariance(
                pureSegments.map { it.embedding }, clusterCentroid
            )

            if (variance > params.maxClusterVariance) {
                val reason = "High variance: %.5f > %.5f".format(
                    variance, params.maxClusterVariance
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
                val avgSimilarity =
                    if (pureSegmentSimilarities.isNotEmpty()) pureSegmentSimilarities.map { it.second }
                        .average().toFloat() else 0f

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
            val sortedSegments = segments.sortedBy {
                "${it.fileUriString}_${it.startOffsetBytes}"
            }

            val labels = mutableMapOf<UnidentifiedSegment, Int>()
            var clusterId = 0

            for (segment in sortedSegments) {
                if (labels.containsKey(segment)) continue
                kotlin.coroutines.coroutineContext.ensureActive()
                val neighbors = findNeighbors(segment, sortedSegments, eps)

                if (neighbors.size < minPts) {
                    labels[segment] = -1 // Mark as noise
                    continue
                }

                clusterId++
                labels[segment] = clusterId

                val seedSet = neighbors.toMutableSet()
                seedSet.remove(segment)

                while (seedSet.isNotEmpty()) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val current = seedSet.first()
                    seedSet.remove(current)

                    val currentLabel = labels.getOrDefault(current, 0)
                    if (currentLabel == -1) {
                        labels[current] = clusterId
                    }
                    if (currentLabel != 0) continue

                    labels[current] = clusterId
                    val currentNeighbors = findNeighbors(current, sortedSegments, eps)
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
            kotlin.coroutines.coroutineContext.ensureActive()
            val distance = 1.0f - speakerIdentifier.calculateCosineSimilarity(
                segment.embedding, other.embedding
            )
            if (distance <= eps) {
                neighbors.add(other)
            }
        }
        return neighbors
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
                kotlin.coroutines.coroutineContext.ensureActive()
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
}

