package com.mfc.recentaudiobuffer

import android.content.Context
import android.media.AudioFormat
import android.util.Log
import androidx.compose.runtime.MutableIntState
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class BitDepth(val bytes: Int, val encodingEnum: Int) {
    override fun toString(): String {
        return "$bytes,$encodingEnum"
    }

    companion object {
        fun fromString(value: String): BitDepth? {
            return try {
                val parts = value.split(",")
                if (parts.size == 2) {
                    val bytes = parts[0].toInt()
                    val encoding = parts[1].toInt()
                    BitDepth(bytes, encoding)
                } else if (parts.size == 1) {
                    // Handle the case where there's no comma
                    val bytes = parts[0].toInt()
                    // You might need a default encoding here, or throw an exception
                    // if you can't determine the encoding without a comma.
                    // For now, let's assume 8-bit encoding if there's no comma.
                    val encoding = when (bytes) {
                        8 -> AudioFormat.ENCODING_PCM_8BIT
                        16 -> AudioFormat.ENCODING_PCM_16BIT
                        24, 32 -> AudioFormat.ENCODING_PCM_FLOAT
                        else -> throw IllegalArgumentException("Invalid bit depth: $bytes")
                    }
                    BitDepth(bytes, encoding)
                } else {
                    throw IllegalArgumentException("Invalid BitDepth format: $value")
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

data class Settings(val sampleRate: Int, val bitDepth: BitDepth, val bufferTimeLengthS: Int)

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    companion object {
        val SAMPLE_RATE = intPreferencesKey("sample_rate")
        val BIT_DEPTH = stringPreferencesKey("bit_depth")
        val BUFFER_TIME_LENGTH_S = intPreferencesKey("buffer_time_length")
    }

    private val settings: Flow<Settings> =
        dataStore.data.catch { exception ->  // Catch exceptions in the flow
            // Log the error for debugging
            Log.e("SettingsRepository", "Error reading settings", exception)
            // Emit default settings to avoid crashes
            emit(emptyPreferences())
        }.map { preferences ->
            val bitDepthString =
                preferences[BIT_DEPTH] ?: "8" // Default to 16-bit if missing or invalid
            var bitDepth = BitDepth.fromString(bitDepthString)
            bitDepth = if (bitDepth == null) {
                Log.e("SettingsRepository", "Invalid bit depth: $bitDepthString")
                // Handle invalid bit depth (e.g., reset to default, show a message)
                bitDepths["8"]!! // Use a valid default BitDepth here
            } else {
                bitDepth
            }
            Settings(
                sampleRate = preferences[SAMPLE_RATE] ?: 22050,
                bitDepth = bitDepth,
                bufferTimeLengthS = preferences[BUFFER_TIME_LENGTH_S] ?: 120
            )
        }

    val config: Flow<AudioConfig> = settings.map { settings ->
        AudioConfig(
            SAMPLE_RATE_HZ = settings.sampleRate,
            BUFFER_TIME_LENGTH_S = settings.bufferTimeLengthS,
            BIT_DEPTH = settings.bitDepth
        )
    }

    suspend fun updateSampleRate(sampleRate: Int) {
        Log.i("SettingsRepository", "updateSampleRate to $sampleRate")
        dataStore.edit { preferences ->
            preferences[SAMPLE_RATE] = sampleRate
        }
    }

    suspend fun updateBitDepth(bitDepth: BitDepth) {
        Log.i("SettingsRepository", "updateBitDepth to $bitDepth")
        dataStore.edit { preferences ->
            preferences[BIT_DEPTH] = bitDepth.toString()
        }
    }

    suspend fun updateBufferTimeLength(bufferTimeLength: Int) {
        Log.i("SettingsRepository", "updateBufferTimeLength to $bufferTimeLength")
        dataStore.edit { preferences ->
            preferences[BUFFER_TIME_LENGTH_S] = bufferTimeLength
        }
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
    var bufferTimeLengthTemp: MutableIntState = mutableIntStateOf(initialConfig.BUFFER_TIME_LENGTH_S)
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