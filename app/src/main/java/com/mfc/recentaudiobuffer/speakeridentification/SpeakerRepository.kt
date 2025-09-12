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
import androidx.core.net.toUri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.WriteBatch
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class FirestoreClusteringConfig(
    val highConfidenceMinPts: Int = DEFAULTS.highConfidenceMinPts,
    val dbscanEps: Float = DEFAULTS.dbscanEps,
    val discoveryMinPts: Int = DEFAULTS.discoveryMinPts,
    val discoveryEps: Float = DEFAULTS.discoveryEps,
    val finalMergeThreshold: Float = DEFAULTS.finalMergeThreshold,
    val minClusterSize: Int = DEFAULTS.minClusterSize,
    val clusterPurityThreshold: Float = DEFAULTS.clusterPurityThreshold,
    val smallClusterPurityBoost: Float = DEFAULTS.smallClusterPurityBoost,
    val maxClusterVariance: Float = DEFAULTS.maxClusterVariance,
    // Sample Generation
    val sampleMinDurationSec: Int = DEFAULTS.sampleMinDurationSec,
    val sampleMaxDurationSec: Int = DEFAULTS.sampleMaxDurationSec,
    val sampleTargetSegments: Int = DEFAULTS.sampleTargetSegments,
    val minChunkDurationSec: Float = DEFAULTS.minChunkDurationSec,
    val sampleSilenceDurationMs: Int = DEFAULTS.sampleSilenceDurationMs,
    // Diarization
    val minSegmentDurationSec: Float = DEFAULTS.minSegmentDurationSec,
    val maxSegmentDurationSec: Float = DEFAULTS.maxSegmentDurationSec,
    val minSpeechEnergyRms: Float = DEFAULTS.minSpeechEnergyRms,
    // VAD
    val vadMergeGapMs: Int = DEFAULTS.vadMergeGapMs,
    val vadPaddingMs: Int = DEFAULTS.vadPaddingMs,
    val vadSpeechThreshold: Float = DEFAULTS.vadSpeechThreshold
) {
    // No-arg constructor for Firestore
    constructor() : this(DEFAULTS.highConfidenceMinPts)

    fun toParameters(): SpeakerClusteringConfig.Parameters {
        // Find a way to handle missing fields for backward compatibility
        val purityBoost =
            this.smallClusterPurityBoost ?: SpeakerClusteringConfig.Parameters().smallClusterPurityBoost

        return SpeakerClusteringConfig.Parameters(
            highConfidenceMinPts,
            dbscanEps,
            discoveryMinPts,
            discoveryEps,
            finalMergeThreshold,
            minClusterSize,
            clusterPurityThreshold,
            purityBoost, // Use the safe value
            maxClusterVariance,
            sampleMinDurationSec,
            sampleMaxDurationSec,
            sampleTargetSegments,
            minChunkDurationSec,
            sampleSilenceDurationMs,
            minSegmentDurationSec,
            maxSegmentDurationSec,
            minSpeechEnergyRms,
            vadMergeGapMs,
            vadPaddingMs,
            vadSpeechThreshold
        )
    }

    companion object {
        private val DEFAULTS = SpeakerClusteringConfig.Parameters()

        fun fromParameters(params: SpeakerClusteringConfig.Parameters): FirestoreClusteringConfig {
            return FirestoreClusteringConfig(
                params.highConfidenceMinPts,
                params.dbscanEps,
                params.discoveryMinPts,
                params.discoveryEps,
                params.finalMergeThreshold,
                params.minClusterSize,
                params.clusterPurityThreshold,
                params.maxClusterVariance,
                params.smallClusterPurityBoost, // Updated
                params.sampleMinDurationSec,
                params.sampleMaxDurationSec,
                params.sampleTargetSegments,
                params.minChunkDurationSec,
                params.sampleSilenceDurationMs,
                params.minSegmentDurationSec,
                params.maxSegmentDurationSec,
                params.minSpeechEnergyRms,
                params.vadMergeGapMs,
                params.vadPaddingMs,
                params.vadSpeechThreshold
            )
        }
    }
}

data class FirestoreSpeaker(
    val id: String = "",
    val name: String = "",
    val embedding: List<Float> = emptyList(),
    val sampleUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
) {
    fun toSpeaker(): Speaker {
        return Speaker(
            id = id,
            name = name,
            embedding = embedding.toFloatArray(),
            sampleUri = sampleUri?.toUri(),
            createdAt = createdAt,
            lastUsedAt = lastUsedAt
        )
    }

    companion object {
        fun fromSpeaker(speaker: Speaker): FirestoreSpeaker {
            return FirestoreSpeaker(
                id = speaker.id,
                name = speaker.name,
                embedding = speaker.embedding.toList(),
                sampleUri = speaker.sampleUri?.toString(),
                createdAt = speaker.createdAt,
                lastUsedAt = speaker.lastUsedAt
            )
        }
    }
}

@Singleton
class SpeakerRepository @Inject constructor(
    private val speakerDao: SpeakerDao,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    @ApplicationContext private val context: Context
) {
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _clusteringConfig = MutableStateFlow<FirestoreClusteringConfig?>(null)
    val clusteringConfig = _clusteringConfig.asStateFlow()

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                repositoryScope.launch {
                    pullFromFirestore(user.uid)
                }
            } else {
                repositoryScope.launch {
                    speakerDao.clearAll()
                    _clusteringConfig.value = null
                }
            }
        }
    }

    fun getAllSpeakers(): Flow<List<Speaker>> = speakerDao.getAllSpeakers()

    suspend fun getSpeakersByIds(ids: List<String>): List<Speaker> =
        speakerDao.getSpeakersByIds(ids)

    suspend fun addSpeaker(speaker: Speaker) {
        speakerDao.insertSpeaker(speaker)

        auth.currentUser?.uid?.let { userId ->
            try {
                val cloudSampleUrl = uploadSampleToStorage(speaker)

                val firestoreSpeaker = if (cloudSampleUrl != null) {
                    FirestoreSpeaker.fromSpeaker(speaker.copy(sampleUri = cloudSampleUrl.toUri()))
                } else {
                    FirestoreSpeaker.fromSpeaker(speaker)
                }

                firestore.collection("users").document(userId).collection("speakers")
                    .document(speaker.id).set(firestoreSpeaker).await()
                Timber.d("Successfully added speaker ${speaker.name} to Firestore")
            } catch (e: Exception) {
                Timber.e(e, "Failed to add speaker to Firestore")
            }
        }
    }

    suspend fun updateSpeaker(speaker: Speaker) {
        speakerDao.updateSpeaker(speaker)
        auth.currentUser?.uid?.let { userId ->
            try {
                val firestoreSpeaker = FirestoreSpeaker.fromSpeaker(speaker)
                firestore.collection("users").document(userId).collection("speakers")
                    .document(speaker.id).set(firestoreSpeaker).await()
                Timber.d("Successfully updated speaker ${speaker.name} in Firestore")
            } catch (e: Exception) {
                Timber.e(e, "Failed to update speaker in Firestore")
            }
        }
    }

    suspend fun deleteSpeaker(speaker: Speaker) {
        speakerDao.deleteSpeaker(speaker)
        speaker.sampleUri?.path?.let { path ->
            try {
                File(path).delete()
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete local sample file")
            }
        }
        auth.currentUser?.uid?.let { userId ->
            try {
                firestore.collection("users").document(userId).collection("speakers")
                    .document(speaker.id).delete().await()
                try {
                    val storage = FirebaseStorage.getInstance()
                    val storageRef =
                        storage.reference.child("users/$userId/samples/${speaker.id}.wav")
                    storageRef.delete().await()
                    Timber.d("Deleted speaker sample from cloud storage")
                } catch (e: Exception) {
                    Timber.w("No cloud sample to delete for speaker ${speaker.name}")
                }
                Timber.d("Successfully deleted speaker ${speaker.name} from Firestore")
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete speaker from Firestore")
            }
        }
    }

    suspend fun deleteAllSpeakers() {
        val speakers = getAllSpeakers().first()
        speakerDao.clearAll()
        speakers.forEach { speaker ->
            speaker.sampleUri?.path?.let { path ->
                try {
                    File(path).delete()
                } catch (e: Exception) {
                    Timber.w("Failed to delete local sample file for ${speaker.name}")
                }
            }
        }

        auth.currentUser?.uid?.let { userId ->
            if (speakers.isNotEmpty()) {
                try {
                    val batch: WriteBatch = firestore.batch()
                    val collectionRef =
                        firestore.collection("users").document(userId).collection("speakers")
                    speakers.forEach { speaker ->
                        batch.delete(collectionRef.document(speaker.id))
                    }
                    batch.commit().await()
                    Timber.d("Successfully deleted all speakers from Firestore")

                    val storage = FirebaseStorage.getInstance()
                    coroutineScope {
                        speakers.map { speaker ->
                            async {
                                try {
                                    val storageRef =
                                        storage.reference.child("users/$userId/samples/${speaker.id}.wav")
                                    storageRef.delete().await()
                                } catch (e: Exception) {
                                    Timber.w("No cloud sample to delete for ${speaker.name}")
                                }
                            }
                        }.awaitAll()
                    }
                    Timber.d("Deleted all speaker samples from cloud storage")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to delete all speakers from Firestore/Storage")
                }
            }
        }
    }

    suspend fun updateClusteringConfig(config: SpeakerClusteringConfig.Parameters) {
        auth.currentUser?.uid?.let { userId ->
            try {
                val firestoreConfig = FirestoreClusteringConfig.fromParameters(config)
                firestore.collection("users").document(userId).collection("configs")
                    .document("clustering").set(firestoreConfig).await()
                Timber.d("Successfully synced clustering config to Firestore.")
            } catch (e: Exception) {
                Timber.e(e, "Failed to sync clustering config to Firestore.")
            }
        }
    }

    private suspend fun pullFromFirestore(userId: String) {
        try {
            val snapshot =
                firestore.collection("users").document(userId).collection("speakers").get().await()

            val firestoreSpeakers = snapshot.documents.mapNotNull { doc ->
                try {
                    doc.toObject(FirestoreSpeaker::class.java)?.let { firestoreSpeaker ->
                        var speaker = firestoreSpeaker.toSpeaker()
                        firestoreSpeaker.sampleUri?.let { url ->
                            if (url.startsWith("https://firebasestorage.googleapis.com")) {
                                val localFile =
                                    File(context.filesDir, "speaker_samples/${speaker.id}.wav")
                                localFile.parentFile?.mkdirs()

                                if (downloadSampleFromStorage(url, localFile)) {
                                    speaker =
                                        speaker.copy(sampleUri = android.net.Uri.fromFile(localFile))
                                    Timber.d("Downloaded sample for speaker ${speaker.name}")
                                }
                            }
                        }
                        speaker
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to deserialize speaker document ${doc.id}")
                    null
                }
            }

            if (firestoreSpeakers.isNotEmpty()) {
                Timber.d("Pulled ${firestoreSpeakers.size} speakers from Firestore. Syncing with local DB.")
                speakerDao.clearAll()
                speakerDao.insertAll(firestoreSpeakers)
            } else {
                Timber.d("No speakers found in Firestore for user $userId")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error pulling speakers from Firestore")
        }

        try {
            val doc = firestore.collection("users").document(userId).collection("configs")
                .document("clustering").get().await()

            if (doc.exists()) {
                val config = doc.toObject(FirestoreClusteringConfig::class.java)
                _clusteringConfig.value = config
                Timber.d("Pulled clustering config from Firestore.")
            } else {
                Timber.d("No clustering config found in Firestore for user. Will use local defaults.")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error pulling clustering config from Firestore.")
        }
    }

    private suspend fun uploadSampleToStorage(speaker: Speaker): String? {
        return speaker.sampleUri?.let { localUri ->
            try {
                val storage = FirebaseStorage.getInstance()
                val storageRef =
                    storage.reference.child("users/${auth.currentUser?.uid}/samples/${speaker.id}.wav")
                storageRef.putFile(localUri).await()
                return storageRef.downloadUrl.await().toString()
            } catch (e: Exception) {
                Timber.e(e, "Failed to upload sample")
                null
            }
        }
    }

    private suspend fun downloadSampleFromStorage(downloadUrl: String, localFile: File): Boolean {
        return try {
            val storage = FirebaseStorage.getInstance()
            val storageRef = storage.getReferenceFromUrl(downloadUrl)

            storageRef.getFile(localFile).await()
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to download sample")
            false
        }
    }
}

