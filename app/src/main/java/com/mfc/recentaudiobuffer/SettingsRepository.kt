package com.mfc.recentaudiobuffer

import android.content.Context
import android.media.AudioFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class BitDepth(val bytes: Int, val encodingEnum: Int) {
    override fun toString(): String {
        return "$bytes,$encodingEnum"
    }

    companion object {
        fun fromString(value: String): BitDepth? {
            return try {
                val parts = value.split(",")
                when (parts.size) {
                    2 -> {
                        val bytes = parts[0].toInt()
                        val encoding = parts[1].toInt()
                        BitDepth(bytes, encoding)
                    }

                    1 -> {
                        // Handle the case where there's no comma
                        val bytes = parts[0].toInt()
                        val encoding = when (bytes) {
                            8 -> AudioFormat.ENCODING_PCM_8BIT
                            16 -> AudioFormat.ENCODING_PCM_16BIT
                            24, 32 -> AudioFormat.ENCODING_PCM_FLOAT
                            else -> throw IllegalArgumentException("Invalid bit depth: $bytes")
                        }
                        BitDepth(bytes, encoding)
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

public val bitDepths = mapOf(
    "8" to BitDepth(8, AudioFormat.ENCODING_PCM_8BIT),
    "16" to BitDepth(16, AudioFormat.ENCODING_PCM_16BIT),
    "24" to BitDepth(24, AudioFormat.ENCODING_PCM_FLOAT), // Use FLOAT for 24-bit
    "32" to BitDepth(32, AudioFormat.ENCODING_PCM_FLOAT)  // Use FLOAT for 32-bit
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
    var SAMPLE_RATE_HZ: Int, var BUFFER_TIME_LENGTH_S: Int, var BIT_DEPTH: BitDepth
)

data class Settings(
    val sampleRate: Int,
    val bitDepth: BitDepth,
    val bufferTimeLengthS: Int,
    val areAdsEnabled: Boolean
)

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = SettingsRepository.SETTINGS_COLLECTION)

class SettingsRepository @Inject constructor(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) {

    companion object {
        val SAMPLE_RATE = intPreferencesKey("sample_rate")
        val BIT_DEPTH = stringPreferencesKey("bit_depth")
        val BUFFER_TIME_LENGTH_S = intPreferencesKey("buffer_time_length")
        val ADS_ENABLED = booleanPreferencesKey("ads_enabled")
        const val SETTINGS_COLLECTION = "settings"
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MILLIS = 2000L
    }

    private val logTag = "SettingsRepository"

    private val settings: Flow<Settings> =
        dataStore.data.catch { exception ->  // Catch exceptions in the flow
            // Log the error for debugging
            Log.e(logTag, "Error reading settings", exception)
            // Emit default settings to avoid crashes
            emit(emptyPreferences())
        }.map { preferences ->
            val bitDepthString =
                preferences[BIT_DEPTH] ?: "8" // Default to 16-bit if missing or invalid
            var bitDepth = BitDepth.fromString(bitDepthString)
            bitDepth = if (bitDepth == null) {
                Log.e(logTag, "Invalid bit depth: $bitDepthString")
                // Handle invalid bit depth (e.g., reset to default, show a message)
                bitDepths["8"]!! // Use a valid default BitDepth here
            } else {
                bitDepth
            }
            val areAdsEnabled = preferences[ADS_ENABLED] ?: true
            Settings(
                sampleRate = preferences[SAMPLE_RATE] ?: 22050,
                bitDepth = bitDepth,
                bufferTimeLengthS = preferences[BUFFER_TIME_LENGTH_S] ?: 120,
                areAdsEnabled = areAdsEnabled
            )
        }

    val config: Flow<AudioConfig> = settings.map { settings ->
        AudioConfig(
            SAMPLE_RATE_HZ = settings.sampleRate,
            BUFFER_TIME_LENGTH_S = settings.bufferTimeLengthS,
            BIT_DEPTH = settings.bitDepth
        )
    }

    val areAdsEnabled: Flow<Boolean> = settings.map { it.areAdsEnabled }

    suspend fun syncSettings(userId: String, retryCount: Int = 0) {
        if (!isOnline(context)) {
            Log.e(logTag, "No internet connection. Cannot sync settings from Firestore.")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "No internet connection", Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            val document = firestore.collection(SETTINGS_COLLECTION).document(userId).get().await()
            if (document.exists()) {
                val firestoreSettings = document.data ?: return
                dataStore.edit { preferences ->
                    preferences[SAMPLE_RATE] = firestoreSettings["sampleRate"] as? Int ?: 22050
                    preferences[BIT_DEPTH] = firestoreSettings["bitDepth"] as? String ?: "8"
                    preferences[BUFFER_TIME_LENGTH_S] =
                        firestoreSettings["bufferTimeLengthS"] as? Int ?: 120
                    preferences[ADS_ENABLED] =
                        firestoreSettings["areAdsEnabled"] as? Boolean ?: true
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error syncing settings from Firestore", e)
            retrySyncSettingsFromFirestore(userId, retryCount)
        }
    }

    private suspend fun retrySyncSettingsFromFirestore(userId: String, retryCount: Int) {
        if (retryCount >= MAX_RETRIES) {
            Log.e(logTag, "Max retries reached. Failed to sync settings from Firestore.")
            return
        }

        if (!isOnline(context)) {
            Log.e(logTag, "No internet connection. Cannot retry syncing settings from Firestore.")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "No internet connection", Toast.LENGTH_SHORT).show()
            }
            return
        }

        Log.w(logTag, "Retrying syncSettingsFromFirestore... (Attempt ${retryCount + 1})")
        withContext(Dispatchers.IO) {
            delay(RETRY_DELAY_MILLIS)
        }
        syncSettings(userId, retryCount + 1)
    }

    private fun isOnline(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities =
            connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        if (capabilities != null) {
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    return true
                }

                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    return true
                }

                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                    return true
                }
            }
        }
        return false
    }

    private suspend fun saveSettingsToFirestore(settings: Settings) {
        try {
            val settingsMap = mapOf(
                "sampleRate" to settings.sampleRate,
                "bitDepth" to settings.bitDepth.toString(),
                "bufferTimeLengthS" to settings.bufferTimeLengthS,
                "areAdsEnabled" to settings.areAdsEnabled
            )

            val userId = auth.currentUser?.uid
            if (userId == null) {
                Log.d(logTag, "saveSettingsToFirestore: User not logged in")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Sign in with Google to save settings to the cloud",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }
            firestore.collection(SETTINGS_COLLECTION).document(userId).set(settingsMap).await()
        } catch (e: Exception) {
            Log.e(logTag, "Error saving settings to Firestore", e)
        }
    }

    suspend fun updateSampleRate(sampleRate: Int) {
        Log.d(logTag, "updateSampleRate to $sampleRate")
        dataStore.edit { preferences ->
            preferences[SAMPLE_RATE] = sampleRate
        }
        saveSettingsToFirestore(settings.first())
    }

    suspend fun updateBitDepth(bitDepth: BitDepth) {
        Log.d(logTag, "updateBitDepth to $bitDepth")
        dataStore.edit { preferences ->
            preferences[BIT_DEPTH] = bitDepth.toString()
        }
        saveSettingsToFirestore(settings.first())
    }

    suspend fun updateBufferTimeLength(bufferTimeLength: Int) {
        Log.d(logTag, "updateBufferTimeLength to $bufferTimeLength")
        dataStore.edit { preferences ->
            preferences[BUFFER_TIME_LENGTH_S] = bufferTimeLength
        }
        saveSettingsToFirestore(settings.first())
    }

    suspend fun updateAreAdsEnabled(areAdsEnabled: Boolean) {
        val userId = auth.currentUser?.uid ?: ""
        Log.d(logTag, "updateAreAdsEnabled to $areAdsEnabled for user $userId")
        dataStore.edit { preferences ->
            preferences[ADS_ENABLED] = areAdsEnabled
        }
        saveSettingsToFirestore(settings.first())
    }
}

class SettingsScreenState(initialConfig: AudioConfig) {
    var isBufferTimeLengthNull by mutableStateOf(false)
        private set
    var isMaxExceeded by mutableStateOf(false)
        private set
    var isSubmitEnabled by mutableStateOf(true)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var bufferTimeLengthTemp: MutableIntState =
        mutableIntStateOf(initialConfig.BUFFER_TIME_LENGTH_S)
        private set

    init {
        validateSettings(initialConfig)
    }

    fun updateBufferTimeLengthTemp(newBufferTimeLength: Int) {
        Log.d("SettingsScreenState", "updateBufferTimeLengthTemp to $newBufferTimeLength")
        bufferTimeLengthTemp.intValue = newBufferTimeLength
    }

    fun validateSettings(config: AudioConfig) {
        val calculatedValue: Long =
            config.SAMPLE_RATE_HZ.toLong() * (config.BIT_DEPTH.bytes / 8).toLong() * bufferTimeLengthTemp.intValue.toLong()
        Log.d("SettingsScreenState", "calculatedValue:  $calculatedValue")
        isMaxExceeded = calculatedValue > 100_000_000L
        isBufferTimeLengthNull = bufferTimeLengthTemp.intValue == 0
        Log.d(
            "SettingsScreenState",
            "isBufferTimeLengthNull, isMaxExceeded: $isBufferTimeLengthNull, $isMaxExceeded"
        )
        isSubmitEnabled = !isMaxExceeded && !isBufferTimeLengthNull

        // Only update errorMessage if input is invalid
        errorMessage = when {
            isMaxExceeded -> "Value(s) too high: Multiplication of settings exceeds 100 MB"
            isBufferTimeLengthNull -> "Invalid buffer length. Must be a number greater than 0"
            else -> null
        }

        Log.d("SettingsScreenState", "validateSettings errorMessage:  $errorMessage")
    }
}

//class SettingsScreenState(config: AudioConfig) {
//    var bufferTimeLength: String by mutableStateOf(config.BUFFER_TIME_LENGTH_S.toString())
//    var bufferTimeLengthTemp = mutableIntStateOf(config.BUFFER_TIME_LENGTH_S)
//    var isMaxExceeded by mutableStateOf(false)
//    var isBufferTimeLengthNull by mutableStateOf(false)
//    var errorMessage by mutableStateOf<String?>(null)
//    var isSubmitEnabled by mutableStateOf(false)
//    var expandedSampleRate by mutableStateOf(false)
//    var expandedBitDepth by mutableStateOf(false)
//    var selectedSampleRate by mutableStateOf(config.SAMPLE_RATE_HZ.toString())
//    var selectedBitDepth by mutableStateOf(config.BIT_DEPTH.toString())
//    var areAdsEnabled by mutableStateOf(true)
//
//    fun updateBufferTimeLengthTemp(newBufferTimeLength: Int) {
//        Log.d("SettingsScreenState", "updateBufferTimeLengthTemp to $newBufferTimeLength")
//        bufferTimeLengthTemp.intValue = newBufferTimeLength
//    }
//
//    fun validateSettings(config: AudioConfig) {
//        val calculatedValue: Long =
//            config.SAMPLE_RATE_HZ.toLong() * (config.BIT_DEPTH.bytes / 8).toLong() * bufferTimeLengthTemp.intValue.toLong()
//        Log.d("SettingsScreenState", "calculatedValue:  $calculatedValue")
//        isMaxExceeded = calculatedValue > 100_000_000L
//        isBufferTimeLengthNull = bufferTimeLengthTemp.intValue == 0
//        Log.d(
//            "SettingsScreenState",
//            "isBufferTimeLengthNull, isMaxExceeded: $isBufferTimeLengthNull, $isMaxExceeded"
//        )
//        isSubmitEnabled = !isMaxExceeded && !isBufferTimeLengthNull
//
//        // Only update errorMessage if input is invalid
//        errorMessage = when {
//            isMaxExceeded -> "Value(s) too high: Multiplication of settings exceeds 100 MB"
//            isBufferTimeLengthNull -> "Invalid buffer length. Must be a number greater than 0"
//            else -> null
//        }
//
//        Log.d("SettingsScreenState", "validateSettings errorMessage:  $errorMessage")
//    }
//}