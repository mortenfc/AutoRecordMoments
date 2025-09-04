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
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await  // For coroutine support
import java.io.File

/**
 * Data class for Firestore serialization.
 * Firestore can't directly serialize FloatArray or Uri, so we use simpler types.
 */
data class FirestoreSpeaker(
    val id: String = "",
    val name: String = "",
    val embedding: List<Float> = emptyList(),  // List instead of FloatArray
    val sampleUri: String? = null,  // String instead of Uri
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
) {
    // Convert to Room Speaker entity
    fun toSpeaker(): Speaker {
        return Speaker(
            id = id,
            name = name,
            embedding = embedding.toFloatArray(),
            sampleUri = sampleUri?.let { android.net.Uri.parse(it) },
            createdAt = createdAt,
            lastUsedAt = lastUsedAt
        )
    }

    companion object {
        // Convert from Room Speaker entity
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

    init {
        // Listen for authentication changes to trigger data sync
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                // User logged in, pull data from Firestore
                repositoryScope.launch {
                    pullFromFirestore(user.uid)
                }
            } else {
                // User logged out, clear local data to prevent mixing profiles
                repositoryScope.launch {
                    speakerDao.clearAll()
                }
            }
        }
    }

    /**
     * The single source of truth for the UI. Always reads from the local Room database.
     */
    fun getAllSpeakers(): Flow<List<Speaker>> = speakerDao.getAllSpeakers()

    suspend fun getSpeakersByIds(ids: List<String>): List<Speaker> =
        speakerDao.getSpeakersByIds(ids)

    suspend fun addSpeaker(speaker: Speaker) {
        // 1. Always write to the local database first for immediate UI update
        speakerDao.insertSpeaker(speaker)

        // 2. If logged in, also write to Firestore
        auth.currentUser?.uid?.let { userId ->
            try {
                // Upload audio sample to Cloud Storage if it exists
                val cloudSampleUrl = uploadSampleToStorage(speaker)

                // Create Firestore speaker with cloud URL (or keep local if upload failed)
                val firestoreSpeaker = if (cloudSampleUrl != null) {
                    FirestoreSpeaker.fromSpeaker(
                        speaker.copy(
                            sampleUri = cloudSampleUrl.toUri()
                        )
                    )
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
        // 1. Update local database
        speakerDao.updateSpeaker(speaker)

        // 2. Update Firestore if logged in
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
        // 1. Delete from local database
        speakerDao.deleteSpeaker(speaker)

        // 2. Delete local sample file if exists
        speaker.sampleUri?.path?.let { path ->
            try {
                File(path).delete()
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete local sample file")
            }
        }

        // 3. Delete from Firestore and Cloud Storage if logged in
        auth.currentUser?.uid?.let { userId ->
            try {
                // Delete from Firestore
                firestore.collection("users").document(userId).collection("speakers")
                    .document(speaker.id).delete().await()

                // Delete from Cloud Storage
                try {
                    val storage = FirebaseStorage.getInstance()
                    val storageRef =
                        storage.reference.child("users/$userId/samples/${speaker.id}.wav")
                    storageRef.delete().await()
                    Timber.d("Deleted speaker sample from cloud storage")
                } catch (e: Exception) {
                    // Sample might not exist in cloud storage
                    Timber.w("No cloud sample to delete for speaker ${speaker.name}")
                }

                Timber.d("Successfully deleted speaker ${speaker.name} from Firestore")
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete speaker from Firestore")
            }
        }
    }

    suspend fun deleteAllSpeakers() {
        // 1. Get all speakers before deleting from the local DB
        val speakers = getAllSpeakers().first()

        // 2. Delete all from local database
        speakerDao.clearAll()

        // 3. Delete local sample files
        speakers.forEach { speaker ->
            speaker.sampleUri?.path?.let { path ->
                try {
                    File(path).delete()
                } catch (e: Exception) {
                    Timber.w("Failed to delete local sample file for ${speaker.name}")
                }
            }
        }

        // 4. Delete from Firestore and Cloud Storage if logged in
        auth.currentUser?.uid?.let { userId ->
            if (speakers.isNotEmpty()) {
                try {
                    // Batch delete from Firestore
                    val batch: WriteBatch = firestore.batch()
                    val collectionRef =
                        firestore.collection("users").document(userId).collection("speakers")
                    speakers.forEach { speaker ->
                        batch.delete(collectionRef.document(speaker.id))
                    }
                    batch.commit().await()
                    Timber.d("Successfully deleted all speakers from Firestore")

                    // Delete from Cloud Storage in parallel (can't batch these)
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

    /**
     * Pulls all speaker profiles from Firestore and overwrites the local Room database.
     * This is the main sync mechanism when a user logs in.
     */
    private suspend fun pullFromFirestore(userId: String) {
        try {
            val snapshot =
                firestore.collection("users").document(userId).collection("speakers").get().await()

            val firestoreSpeakers = snapshot.documents.mapNotNull { doc ->
                try {
                    doc.toObject(FirestoreSpeaker::class.java)?.let { firestoreSpeaker ->
                        var speaker = firestoreSpeaker.toSpeaker()

                        // If sample URL is a cloud storage URL, download it
                        firestoreSpeaker.sampleUri?.let { url ->
                            if (url.startsWith("https://firebasestorage.googleapis.com")) {
                                val localFile =
                                    File(context.filesDir, "speaker_samples/${speaker.id}.wav")
                                localFile.parentFile?.mkdirs()

                                if (downloadSampleFromStorage(url, localFile)) {
                                    // Update speaker with local file URI
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
            // Don't crash - just use local data
        }
    }

    private suspend fun uploadSampleToStorage(speaker: Speaker): String? {
        return speaker.sampleUri?.let { localUri ->
            try {
                // Get storage instance (main module API)
                val storage = FirebaseStorage.getInstance()
                val storageRef =
                    storage.reference.child("users/${auth.currentUser?.uid}/samples/${speaker.id}.wav")

                // Upload file
                val uploadTask = storageRef.putFile(localUri)
                uploadTask.await()

                // Get download URL
                return storageRef.downloadUrl.await().toString()
            } catch (e: Exception) {
                Timber.e(e, "Failed to upload sample")
                null
            }
        }
    }

    // For downloading
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
