package com.mfc.recentaudiobuffer.speakeridentification

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.toObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

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

    suspend fun getSpeakersByIds(ids: List<String>): List<Speaker> = speakerDao.getSpeakersByIds(ids)

    suspend fun addSpeaker(speaker: Speaker) {
        // 1. Always write to the local database first for immediate UI update
        speakerDao.insertSpeaker(speaker)

        // 2. If logged in, also write to Firestore
        auth.currentUser?.uid?.let { userId ->
            try {
                firestore.collection("users").document(userId).collection("speakers")
                    .document(speaker.id).set(speaker).await()
            } catch (e: Exception) {
                Timber.e(e, "Failed to add speaker to Firestore")
            }
        }
    }

    suspend fun updateSpeaker(speaker: Speaker) {
        speakerDao.updateSpeaker(speaker)
        auth.currentUser?.uid?.let { userId ->
            try {
                firestore.collection("users").document(userId).collection("speakers")
                    .document(speaker.id).set(speaker).await()
            } catch (e: Exception) {
                Timber.e(e, "Failed to update speaker in Firestore")
            }
        }
    }

    suspend fun deleteSpeaker(speaker: Speaker) {
        speakerDao.deleteSpeaker(speaker)
        auth.currentUser?.uid?.let { userId ->
            try {
                firestore.collection("users").document(userId).collection("speakers")
                    .document(speaker.id).delete().await()
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete speaker from Firestore")
            }
        }
    }

    /**
     * Pulls all speaker profiles from Firestore and overwrites the local Room database.
     * This is the main sync mechanism when a user logs in.
     */
    private suspend fun pullFromFirestore(userId: String) {
        try {
            val snapshot = firestore.collection("users").document(userId).collection("speakers").get().await()
            val firestoreSpeakers = snapshot.documents.mapNotNull { it.toObject<Speaker>() }

            if (firestoreSpeakers.isNotEmpty()) {
                Timber.d("Pulled ${firestoreSpeakers.size} speakers from Firestore. Syncing with local DB.")
                speakerDao.clearAll()
                speakerDao.insertAll(firestoreSpeakers)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error pulling speakers from Firestore")
        }
    }
}
