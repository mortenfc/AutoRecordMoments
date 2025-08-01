/*
 * # Auto Record Moments
 * # Copyright (C) 2025 Morten Fjord Christensen
 * #
 * # This program is free software: you can redistribute it and/or modify
 * # it under the terms of the GNU Affero General Public License as published by
 * # the Free Software Foundation, either version 3 of the License, or
 * # (at your option) any later version.
 * #
 * # This program is distributed in the hope that it will be useful,
 * # but WITHOUT ANY WARRANTY; without even the implied warranty of
 * # MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * # GNU Affero General Public License for more details.
 * #
 * # You should have received a copy of the GNU Affero General Public License
 * # along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.mfc.recentaudiobuffer

import android.content.Context
import android.media.AudioFormat
import android.os.Parcelable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.parcelize.Parcelize
import timber.log.Timber
import javax.inject.Inject

@Parcelize
data class BitDepth(val bits: Int, val encodingEnum: Int) : Parcelable {
    override fun toString(): String {
        return "$bits,$encodingEnum"
    }

    companion object {
        fun fromString(value: String): BitDepth? {
            return try {
                val parts = value.split(",")
                when (parts.size) {
                    2 -> {
                        val bits = parts[0].toInt()
                        val encoding = parts[1].toInt()
                        BitDepth(bits, encoding)
                    }

                    1 -> {
                        // Handle the case where there's no comma
                        val bits = parts[0].toInt()
                        val encoding = when (bits) {
                            8 -> AudioFormat.ENCODING_PCM_8BIT
                            16 -> AudioFormat.ENCODING_PCM_16BIT
                            else -> throw IllegalArgumentException("Invalid bit depth: $bits")
                        }
                        BitDepth(bits, encoding)
                    }

                    else -> throw IllegalArgumentException("Invalid BitDepth format: $value")
                }
            } catch (e: Exception) {
                // Log the error for debugging
                Timber.e("Error parsing BitDepth from string: $value, $e")
                // Return null to indicate parsing failure
                null
            }
        }
    }
}

const val DEFAULT_BIT_DEPTH_KEY = "16"
const val DEFAULT_SAMPLE_RATE = 16000
const val DEFAULT_BUFFER_TIME_LENGTH_S = 300

val bitDepths = mapOf(
    "8" to BitDepth(8, AudioFormat.ENCODING_PCM_8BIT),
    "16" to BitDepth(16, AudioFormat.ENCODING_PCM_16BIT),
)

val sampleRates = mapOf(
    "8000" to 8000,
    "11025" to 11025,
    "16000" to 16000,
    "22050" to 22050,
    "44100" to 44100,
    "48000" to 48000,
    "88200" to 88200,
    "96000" to 96000,
    "192000" to 192000
)

@Parcelize
data class AudioConfig(
    var sampleRateHz: Int = DEFAULT_SAMPLE_RATE,
    var bufferTimeLengthS: Int = DEFAULT_BUFFER_TIME_LENGTH_S,
    var bitDepth: BitDepth = bitDepths[DEFAULT_BIT_DEPTH_KEY]!!,
) : Parcelable

data class SettingsConfig(
    var sampleRateHz: Int = DEFAULT_SAMPLE_RATE,
    var bufferTimeLengthS: Int = DEFAULT_BUFFER_TIME_LENGTH_S,
    var bitDepth: BitDepth = bitDepths[DEFAULT_BIT_DEPTH_KEY]!!,
    var areAdsEnabled: Boolean = true,
    var isAiAutoClipEnabled: Boolean = true,
)

fun SettingsConfig.toAudioConfig(): AudioConfig {
    return AudioConfig(this.sampleRateHz, this.bufferTimeLengthS, this.bitDepth)
}

const val MAX_BUFFER_SIZE_B: Int = 150_000_000 // Bytes

const val LOW_MEMORY_MAX_BUFFER_SIZE_B: Int = 25_000_000

const val AI_ENABLED_EXTRA_MEMORY_USAGE_FRACTION = 1.5f

// DataStore setup
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) {
    private val dataStore = context.dataStore

    // DataStore keys
    private object PreferencesKeys {
        val SAMPLE_RATE_HZ = intPreferencesKey("sample_rate_hz")
        val BUFFER_TIME_LENGTH_S = intPreferencesKey("buffer_time_length_s")
        val BIT_DEPTH = stringPreferencesKey("bit_depth")
        val ARE_ADS_ENABLED = booleanPreferencesKey("are_ads_enabled")
        val IS_AI_AUTO_CLIP_ENABLED = booleanPreferencesKey("is_ai_auto_clip_enabled")
    }

    // Helper function to get the current user ID or null if not logged in
    private fun getUserId(): String? {
        return auth.currentUser?.uid
    }

    // Helper function to determine if the user is logged in
    private fun isLoggedIn(): Boolean {
        return getUserId() != null
    }

    private suspend fun <T> updateSetting(
        key: Preferences.Key<T>, value: T, firestoreFieldName: String
    ) {
        // 1. Update local DataStore
        dataStore.edit { preferences ->
            preferences[key] = value
        }

        // 2. Update Firestore if logged in
        auth.currentUser?.uid?.let { userId ->
            try {
                firestore.collection("users").document(userId).update(firestoreFieldName, value)
                    .await()
            } catch (e: Exception) {
                Timber.e(e, "Failed to update '$firestoreFieldName' in Firestore.")
            }
        }
    }

    suspend fun updateAreAdsEnabled(areAdsEnabled: Boolean) {
        updateSetting(PreferencesKeys.ARE_ADS_ENABLED, areAdsEnabled, "areAdsEnabled")

    }

    suspend fun updateBufferTimeLengthS(bufferTimeLengthS: Int) {
        updateSetting(PreferencesKeys.BUFFER_TIME_LENGTH_S, bufferTimeLengthS, "bufferTimeLengthS")
    }

    suspend fun updateSampleRate(sampleRate: Int) {
        updateSetting(PreferencesKeys.SAMPLE_RATE_HZ, sampleRate, "sampleRateHz")
    }

    suspend fun updateBitDepth(bitDepth: BitDepth) {
        updateSetting(PreferencesKeys.BIT_DEPTH, bitDepth.toString(), "bitDepth")
    }

    suspend fun updateIsAiAutoClipEnabled(isAiAutoClipEnabled: Boolean) {
        updateSetting(
            PreferencesKeys.IS_AI_AUTO_CLIP_ENABLED, isAiAutoClipEnabled, "isAiAutoClipEnabled"
        )

    }

    private fun getBitDepth(key: String): BitDepth {
        return BitDepth.fromString(key) ?: BitDepth(8, AudioFormat.ENCODING_PCM_8BIT)
    }

    suspend fun getAudioConfig(): AudioConfig = loadSettingsFromDatastore().toAudioConfig()

    suspend fun getSettingsConfig(): SettingsConfig = loadSettingsFromDatastore()

    private fun DocumentSnapshot.toAudioConfig(): AudioConfig {
        val sampleRate = getLong("sampleRateHz")?.toInt() ?: DEFAULT_SAMPLE_RATE
        val bufferTimeLength = getLong("bufferTimeLengthS")?.toInt() ?: DEFAULT_BUFFER_TIME_LENGTH_S
        val bitDepthString = getString("bitDepth") ?: DEFAULT_BIT_DEPTH_KEY
        val bitDepth = getBitDepth(bitDepthString)
        return AudioConfig(sampleRate, bufferTimeLength, bitDepth)
    }

    private fun DocumentSnapshot.toSettingsConfig(): SettingsConfig {
        val audioConfig = this.toAudioConfig() // Reuse the other helper!
        val areAdsEnabled = getBoolean("areAdsEnabled") ?: true
        val isAiAutoClipEnabled = getBoolean("isAiAutoClipEnabled") ?: true
        return SettingsConfig(
            sampleRateHz = audioConfig.sampleRateHz,
            bufferTimeLengthS = audioConfig.bufferTimeLengthS,
            bitDepth = audioConfig.bitDepth,
            areAdsEnabled = areAdsEnabled,
            isAiAutoClipEnabled = isAiAutoClipEnabled
        )
    }

    private suspend fun loadSettingsFromDatastore(): SettingsConfig {
        val prefs = dataStore.data.first()
        return SettingsConfig(
            sampleRateHz = prefs[PreferencesKeys.SAMPLE_RATE_HZ] ?: DEFAULT_SAMPLE_RATE,
            bufferTimeLengthS = prefs[PreferencesKeys.BUFFER_TIME_LENGTH_S]
                ?: DEFAULT_BUFFER_TIME_LENGTH_S,
            bitDepth = BitDepth.fromString(
                prefs[PreferencesKeys.BIT_DEPTH] ?: DEFAULT_BIT_DEPTH_KEY
            ) ?: bitDepths[DEFAULT_BIT_DEPTH_KEY]!!,
            areAdsEnabled = prefs[PreferencesKeys.ARE_ADS_ENABLED] ?: true,
            isAiAutoClipEnabled = prefs[PreferencesKeys.IS_AI_AUTO_CLIP_ENABLED] ?: true
        )
    }

    suspend fun pullFromFirestore() {
        Timber.d("pullFromFirestore() called. auth.currentUser: ${auth.currentUser}")
        if (isLoggedIn()) {
            val userId = getUserId()!!
            val userDocRef = firestore.collection("users").document(userId)

            // Only update dataStore if firestore gave data
            getDocumentSync(userDocRef)?.let { document ->
                val settingsToCache: SettingsConfig

                if (!document.exists()) {
                    // If doc doesn't exist, create it and use the local default object.
                    Timber.d("No Firestore doc found, creating default.")
                    settingsToCache = SettingsConfig()
                    userDocRef.set(settingsToCache).await()
                } else {
                    // If doc exists, parse it.
                    Timber.d("Firestore doc found, parsing it.")
                    settingsToCache = document.toSettingsConfig()
                }

                // Now, cache the determined settings in DataStore
                dataStore.edit { preferences ->
                    preferences[PreferencesKeys.SAMPLE_RATE_HZ] = settingsToCache.sampleRateHz
                    preferences[PreferencesKeys.BUFFER_TIME_LENGTH_S] =
                        settingsToCache.bufferTimeLengthS
                    preferences[PreferencesKeys.BIT_DEPTH] = settingsToCache.bitDepth.toString()
                    preferences[PreferencesKeys.ARE_ADS_ENABLED] = settingsToCache.areAdsEnabled
                    preferences[PreferencesKeys.IS_AI_AUTO_CLIP_ENABLED] =
                        settingsToCache.isAiAutoClipEnabled
                }
            }
        }
    }

    private suspend fun getDocumentSync(userDocRef: DocumentReference): DocumentSnapshot? {
        try {
            return userDocRef.get().await()
        } catch (e: FirebaseFirestoreException) {
            Timber.e("ERROR in getDocumentSync $e")
            return null
        }
    }
}

class SettingsScreenState(initialConfig: SettingsConfig) {
    var isBufferTimeLengthNull = mutableStateOf(false)
        private set
    var isMaxExceeded = mutableStateOf(false)
        private set
    var isSubmitEnabled = mutableStateOf(true)
        private set
    var errorMessage = mutableStateOf<String?>(null)
        private set
    var bufferTimeLengthTemp = mutableIntStateOf(initialConfig.bufferTimeLengthS)
        private set
    var sampleRateTemp = mutableIntStateOf(initialConfig.sampleRateHz)
        private set
    var bitDepthTemp = mutableStateOf(initialConfig.bitDepth)
        private set
    var isAiAutoClipEnabled = mutableStateOf(initialConfig.isAiAutoClipEnabled)
        private set

    fun updateBufferTimeLengthTemp(newBufferTimeLength: Int) {
        Timber.d("updateBufferTimeLengthTemp to $newBufferTimeLength")
        bufferTimeLengthTemp.intValue = newBufferTimeLength
    }

    fun updateSampleRateTemp(newSampleRateTemp: Int) {
        Timber.d("updateSampleRateTemp to $newSampleRateTemp")
        sampleRateTemp.intValue = newSampleRateTemp
    }

    fun updateBitDepthTemp(newBitDepthTemp: BitDepth) {
        Timber.d("updateBitDepthTemp to $newBitDepthTemp")
        bitDepthTemp.value = newBitDepthTemp
    }

    fun updateIsAiAutoClipEnabled(newIsAiAutoClipEnabled: Boolean) {
        Timber.d("updateIsAiAutoClipEnabled to $newIsAiAutoClipEnabled")
        isAiAutoClipEnabled.value = newIsAiAutoClipEnabled
    }

    fun uploadSettingsToAppView(settingsViewModel: SettingsViewModel): List<Job> {
        val job1 = settingsViewModel.updateBufferTimeLengthS(bufferTimeLengthTemp.intValue)
        val job2 = settingsViewModel.updateSampleRate(sampleRateTemp.intValue)
        val job3 = settingsViewModel.updateBitDepth(bitDepthTemp.value)
        val job4 = settingsViewModel.updateIsAiAutoClipEnabled(isAiAutoClipEnabled.value)
        return listOf(job1, job2, job3, job4)
    }

    fun validateSettings() {
        val calculatedBytes: Long =
            sampleRateTemp.intValue.toLong() * (bitDepthTemp.value.bits / 8).toLong() * bufferTimeLengthTemp.intValue.toLong()

        var maxAdjustedBufferSizeB = MAX_BUFFER_SIZE_B

        if (isAiAutoClipEnabled.value) {
            maxAdjustedBufferSizeB =
                (maxAdjustedBufferSizeB / AI_ENABLED_EXTRA_MEMORY_USAGE_FRACTION).toInt()
        }

        val calculatedMB = calculatedBytes / (1_000_000)
        val maxMB = maxAdjustedBufferSizeB / (1_000_000)

        Timber.d("Calculated buffer size: $calculatedMB MB")
        isMaxExceeded.value = calculatedBytes > maxAdjustedBufferSizeB
        isBufferTimeLengthNull.value = bufferTimeLengthTemp.intValue == 0
        Timber.d(
            "isBufferTimeLengthNull, isMaxExceeded: $isBufferTimeLengthNull, $isMaxExceeded"
        )
        isSubmitEnabled.value = !isMaxExceeded.value && !isBufferTimeLengthNull.value

        errorMessage.value = when {
            isMaxExceeded.value -> "Buffer size is too large ($calculatedMB MB). The maximum is $maxMB MB. Please lower settings."
            isBufferTimeLengthNull.value -> "Buffer length must be greater than 0."
            else -> null
        }

        Timber.d("validateSettings errorMessage: ${errorMessage.value}")
    }
}