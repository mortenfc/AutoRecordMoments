package com.mfc.recentaudiobuffer.speakeridentification

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
    private val firestore: FirebaseFirestore
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
                val firestoreSpeaker = FirestoreSpeaker.fromSpeaker(speaker)
                firestore.collection("users")
                    .document(userId)
                    .collection("speakers")
                    .document(speaker.id)
                    .set(firestoreSpeaker)
                    .await()
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
                firestore.collection("users")
                    .document(userId)
                    .collection("speakers")
                    .document(speaker.id)
                    .set(firestoreSpeaker)
                    .await()
                Timber.d("Successfully updated speaker ${speaker.name} in Firestore")
            } catch (e: Exception) {
                Timber.e(e, "Failed to update speaker in Firestore")
            }
        }
    }

    suspend fun deleteSpeaker(speaker: Speaker) {
        // 1. Delete from local database
        speakerDao.deleteSpeaker(speaker)

        // 2. Delete from Firestore if logged in
        auth.currentUser?.uid?.let { userId ->
            try {
                firestore.collection("users")
                    .document(userId)
                    .collection("speakers")
                    .document(speaker.id)
                    .delete()
                    .await()
                Timber.d("Successfully deleted speaker ${speaker.name} from Firestore")
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete speaker from Firestore")
            }
        }
    }

    suspend fun deleteAllSpeakers() {
        // 1. Get all speaker IDs before deleting from the local DB
        val speakerIds = getAllSpeakers().first().map { it.id }

        // 2. Delete all from local database
        speakerDao.clearAll()

        // 3. Delete all from Firestore if logged in
        auth.currentUser?.uid?.let { userId ->
            if (speakerIds.isNotEmpty()) {
                try {
                    val batch: WriteBatch = firestore.batch()
                    val collectionRef = firestore.collection("users").document(userId).collection("speakers")
                    speakerIds.forEach { speakerId ->
                        batch.delete(collectionRef.document(speakerId))
                    }
                    batch.commit().await()
                    Timber.d("Successfully deleted all speakers from Firestore")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to delete all speakers from Firestore")
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
            val snapshot = firestore.collection("users")
                .document(userId)
                .collection("speakers")
                .get()
                .await()

            val firestoreSpeakers = snapshot.documents.mapNotNull { doc ->
                try {
                    doc.toObject(FirestoreSpeaker::class.java)?.toSpeaker()
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
}
