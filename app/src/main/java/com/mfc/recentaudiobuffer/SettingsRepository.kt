package com.mfc.recentaudiobuffer

import android.content.Context
import android.media.AudioFormat
import android.util.Log
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

data class BitDepth(val bits: Int, val encodingEnum: Int) {
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
                Log.e("BitDepth", "Error parsing BitDepth from string: $value", e)
                // Return null to indicate parsing failure
                null
            }
        }
    }
}

const val DEFAULT_BIT_DEPTH_KEY = "16"
const val DEFAULT_SAMPLE_RATE = 22050
const val DEFAULT_BUFFER_TIME_LENGTH_S = 300

public val bitDepths = mapOf(
    "8" to BitDepth(8, AudioFormat.ENCODING_PCM_8BIT),
    "16" to BitDepth(16, AudioFormat.ENCODING_PCM_16BIT),
)

public val sampleRates = mapOf(
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

public data class AudioConfig(
    var sampleRateHz: Int = DEFAULT_SAMPLE_RATE,
    var bufferTimeLengthS: Int = DEFAULT_BUFFER_TIME_LENGTH_S,
    var bitDepth: BitDepth = bitDepths[DEFAULT_BIT_DEPTH_KEY]!!,
)

data class SettingsConfig(
    var sampleRateHz: Int = DEFAULT_SAMPLE_RATE,
    var bufferTimeLengthS: Int = DEFAULT_BUFFER_TIME_LENGTH_S,
    var bitDepth: BitDepth = bitDepths[DEFAULT_BIT_DEPTH_KEY]!!,
    var areAdsEnabled: Boolean = true,
)

public const val MAX_BUFFER_SIZE: Int = 200 * 1024 * 1024 // 200 MB

// DataStore setup
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) {
    private val logTag = "SettingsRepository"
    private val dataStore = context.dataStore

    // DataStore keys
    private object PreferencesKeys {
        val SAMPLE_RATE_HZ = intPreferencesKey("sample_rate_hz")
        val BUFFER_TIME_LENGTH_S = intPreferencesKey("buffer_time_length_s")
        val BIT_DEPTH = stringPreferencesKey("bit_depth")
        val ARE_ADS_ENABLED = booleanPreferencesKey("are_ads_enabled")
    }

    // Helper function to get the current user ID or null if not logged in
    private fun getUserId(): String? {
        return auth.currentUser?.uid
    }

    // Helper function to determine if the user is logged in
    private fun isLoggedIn(): Boolean {
        return getUserId() != null
    }

    suspend fun updateAreAdsEnabled(areAdsEnabled: Boolean) {
        if (isLoggedIn()) {
            val userId = getUserId()!!
            val userDocRef = firestore.collection("users").document(userId)
            userDocRef.update("ARE_ADS_ENABLED", areAdsEnabled).await()
        } else {
            dataStore.edit { preferences ->
                preferences[PreferencesKeys.ARE_ADS_ENABLED] = areAdsEnabled
            }
        }
    }

    suspend fun updateSampleRate(sampleRate: Int) {
        if (isLoggedIn()) {
            val userId = getUserId()!!
            val userDocRef = firestore.collection("users").document(userId)
            userDocRef.update("SAMPLE_RATE_HZ", sampleRate).await()
        } else {
            dataStore.edit { preferences ->
                preferences[PreferencesKeys.SAMPLE_RATE_HZ] = sampleRate
            }
        }
    }

    suspend fun updateBufferTimeLength(bufferTimeLength: Int) {
        if (isLoggedIn()) {
            val userId = getUserId()!!
            val userDocRef = firestore.collection("users").document(userId)
            userDocRef.update("BUFFER_TIME_LENGTH_S", bufferTimeLength).await()
        } else {
            dataStore.edit { preferences ->
                preferences[PreferencesKeys.BUFFER_TIME_LENGTH_S] = bufferTimeLength
            }
        }
    }

    suspend fun updateBitDepth(bitDepth: BitDepth) {
        if (isLoggedIn()) {
            val userId = getUserId()!!
            val userDocRef = firestore.collection("users").document(userId)
            userDocRef.update("BIT_DEPTH", bitDepth.toString()).await()
        } else {
            dataStore.edit { preferences ->
                preferences[PreferencesKeys.BIT_DEPTH] = bitDepth.toString()
            }
        }
    }

    private fun getBitDepth(key: String): BitDepth {
        return BitDepth.fromString(key) ?: BitDepth(8, AudioFormat.ENCODING_PCM_8BIT)
    }

    // ✅ PUBLIC FUNCTION
    suspend fun getAudioConfig(): AudioConfig {
        Log.d(logTag, "getAudioConfig() called")
        return if (isLoggedIn()) {
            val userDocRef = firestore.collection("users").document(getUserId()!!)
            val document = getDocumentSync(userDocRef)
            // Use the helper to parse the document, or fall back to DataStore
            document?.toAudioConfig() ?: loadAudioFromDatastore()
        } else {
            loadAudioFromDatastore()
        }
    }

    // ✅ PUBLIC FUNCTION
    suspend fun getSettingsConfig(): SettingsConfig {
        Log.d(logTag, "getSettingsConfig() called")
        return if (isLoggedIn()) {
            val userDocRef = firestore.collection("users").document(getUserId()!!)
            val document = getDocumentSync(userDocRef)
            // Use the helper to parse, or fall back to DataStore
            document?.toSettingsConfig() ?: loadSettingsFromDatastore()
        } else {
            loadSettingsFromDatastore()
        }
    }

    // ✅ PRIVATE HELPER for Firestore
    private fun DocumentSnapshot.toAudioConfig(): AudioConfig {
        val sampleRate = getLong("SAMPLE_RATE_HZ")?.toInt() ?: DEFAULT_SAMPLE_RATE
        val bufferTimeLength =
            getLong("BUFFER_TIME_LENGTH_S")?.toInt() ?: DEFAULT_BUFFER_TIME_LENGTH_S
        val bitDepthString = getString("BIT_DEPTH") ?: DEFAULT_BIT_DEPTH_KEY
        val bitDepth = getBitDepth(bitDepthString)
        return AudioConfig(sampleRate, bufferTimeLength, bitDepth)
    }

    // ✅ PRIVATE HELPER for Firestore
    private fun DocumentSnapshot.toSettingsConfig(): SettingsConfig {
        val audioConfig = this.toAudioConfig() // Reuse the other helper!
        val areAdsEnabled = getBoolean("ARE_ADS_ENABLED") ?: true
        return SettingsConfig(
            sampleRateHz = audioConfig.sampleRateHz,
            bufferTimeLengthS = audioConfig.bufferTimeLengthS,
            bitDepth = audioConfig.bitDepth,
            areAdsEnabled = areAdsEnabled
        )
    }

    // ✅ PRIVATE HELPER for DataStore
    private suspend fun loadAudioFromDatastore(): AudioConfig {
        val preferences = dataStore.data.first()
        val sampleRate = preferences[PreferencesKeys.SAMPLE_RATE_HZ] ?: DEFAULT_SAMPLE_RATE
        val bufferTimeLength =
            preferences[PreferencesKeys.BUFFER_TIME_LENGTH_S] ?: DEFAULT_BUFFER_TIME_LENGTH_S
        val bitDepthString = preferences[PreferencesKeys.BIT_DEPTH] ?: DEFAULT_BIT_DEPTH_KEY
        val bitDepth = getBitDepth(bitDepthString)
        return AudioConfig(sampleRate, bufferTimeLength, bitDepth)
    }

    // ✅ PRIVATE HELPER for DataStore
    private suspend fun loadSettingsFromDatastore(): SettingsConfig {
        val audioConfig = loadAudioFromDatastore() // Reuse the other helper!
        val preferences = dataStore.data.first()
        val areAdsEnabled = preferences[PreferencesKeys.ARE_ADS_ENABLED] ?: true
        return SettingsConfig(
            sampleRateHz = audioConfig.sampleRateHz,
            bufferTimeLengthS = audioConfig.bufferTimeLengthS,
            bitDepth = audioConfig.bitDepth,
            areAdsEnabled = areAdsEnabled
        )
    }

    suspend fun pullFromFirestore() {
        Log.d(logTag, "pullFromFirestore() called. auth.currentUser: ${auth.currentUser}")
        if (isLoggedIn()) {
            val userId = getUserId()!!
            val userDocRef = firestore.collection("users").document(userId)

            // Only update dataStore if firestore gave data
            getDocumentSync(userDocRef)?.let { document ->
                val settingsToCache: SettingsConfig

                if (!document.exists()) {
                    // If doc doesn't exist, create it and use the local default object.
                    Log.d(logTag, "No Firestore doc found, creating default.")
                    settingsToCache = SettingsConfig()
                    userDocRef.set(settingsToCache).await()
                } else {
                    // If doc exists, parse it.
                    Log.d(logTag, "Firestore doc found, parsing it.")
                    settingsToCache = document.toSettingsConfig()
                }

                // Now, cache the determined settings in DataStore
                dataStore.edit { preferences ->
                    preferences[PreferencesKeys.SAMPLE_RATE_HZ] = settingsToCache.sampleRateHz
                    preferences[PreferencesKeys.BUFFER_TIME_LENGTH_S] =
                        settingsToCache.bufferTimeLengthS
                    preferences[PreferencesKeys.BIT_DEPTH] = settingsToCache.bitDepth.toString()
                    preferences[PreferencesKeys.ARE_ADS_ENABLED] = settingsToCache.areAdsEnabled
                }
            }
        }
    }

    private suspend fun getDocumentSync(userDocRef: DocumentReference): DocumentSnapshot? {
        try {
            return userDocRef.get().await()
        } catch (e: FirebaseFirestoreException) {
            Log.e(logTag, "ERROR in getDocumentSync", e)
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

    fun updateBufferTimeLengthTemp(newBufferTimeLength: Int) {
        Log.d("SettingsScreenState", "updateBufferTimeLengthTemp to $newBufferTimeLength")
        bufferTimeLengthTemp.intValue = newBufferTimeLength
    }

    fun updateSampleRateTemp(newSampleRateTemp: Int) {
        Log.d("SettingsScreenState", "updateSampleRateTemp to $newSampleRateTemp")
        sampleRateTemp.intValue = newSampleRateTemp
    }

    fun updateBitDepthTemp(newBitDepthTemp: BitDepth) {
        Log.d("SettingsScreenState", "updateBitDepthTemp to $newBitDepthTemp")
        bitDepthTemp.value = newBitDepthTemp
    }

    fun updateSettings(settingsViewModel: SettingsViewModel) {
        settingsViewModel.updateBufferTimeLength(bufferTimeLengthTemp.intValue)
        settingsViewModel.updateSampleRate(sampleRateTemp.intValue)
        settingsViewModel.updateBitDepth(bitDepthTemp.value)
    }

    fun validateSettings() {
        val calculatedValue: Long =
            sampleRateTemp.intValue.toLong() * (bitDepthTemp.value.bits / 8).toLong() * bufferTimeLengthTemp.intValue.toLong()

        Log.d("SettingsScreenState", "calculatedValue:  $calculatedValue")
        isMaxExceeded.value = calculatedValue > MAX_BUFFER_SIZE
        isBufferTimeLengthNull.value = bufferTimeLengthTemp.intValue == 0
        Log.d(
            "SettingsScreenState",
            "isBufferTimeLengthNull, isMaxExceeded: $isBufferTimeLengthNull, $isMaxExceeded"
        )
        isSubmitEnabled.value = !isMaxExceeded.value && !isBufferTimeLengthNull.value

        // Only update errorMessage if input is invalid
        errorMessage.value = when {
            isMaxExceeded.value -> "Value(s) too high: Multiplication of settings exceeds 200 MB"
            isBufferTimeLengthNull.value -> "Invalid buffer length. Must be a number greater than 0"
            else -> null
        }

        Log.d("SettingsScreenState", "validateSettings errorMessage: ${errorMessage.value}")
    }
}