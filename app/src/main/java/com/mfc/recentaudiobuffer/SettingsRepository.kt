package com.mfc.recentaudiobuffer

import android.content.Context
import android.media.AudioFormat
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

data class BitDepth(val bytes: Int, val encodingEnum: Int) {
    override fun toString(): String {
        return "$bytes,$encodingEnum"
    }

    companion object {
        fun fromString(value: String): BitDepth? {
            return try {
                val (bytes, encoding) = value.split(",").map { it.toInt() }
                BitDepth(bytes, encoding)
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
    var SAMPLE_RATE_HZ: Int, var BUFFER_TIME_LENGTH_S: Int, var BIT_DEPTH: BitDepth, var SAVE_LOCATION_ON_SAVE_AUDIO: Boolean
)

data class Settings(val sampleRate: Int, val bitDepth: BitDepth, val bufferTimeLengthS: Int, val saveLocationOnSaveAudio: Boolean)

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    companion object {
        val SAMPLE_RATE = intPreferencesKey("sample_rate")
        val BIT_DEPTH = stringPreferencesKey("bit_depth")
        val BUFFER_TIME_LENGTH_S = intPreferencesKey("buffer_time_length")
        val SAVE_LOCATION_ON_SAVE_AUDIO = booleanPreferencesKey("save_location_on_save_audio")
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
                    bufferTimeLengthS = preferences[BUFFER_TIME_LENGTH_S] ?: 120,
                    saveLocationOnSaveAudio = preferences[SAVE_LOCATION_ON_SAVE_AUDIO] ?: false
                )
            }

    val config: Flow<AudioConfig> = settings.map { settings ->
        AudioConfig(
            SAMPLE_RATE_HZ = settings.sampleRate,
            BUFFER_TIME_LENGTH_S = settings.bufferTimeLengthS,
            BIT_DEPTH = settings.bitDepth,
            SAVE_LOCATION_ON_SAVE_AUDIO = settings.saveLocationOnSaveAudio
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
    suspend fun updateSaveLocationOnSaveAudio(save_location_on_audio_save: Boolean){
        Log.i("SettingsRepository", "Saving location when saving audio to $save_location_on_audio_save")
        dataStore.edit { preferences -> preferences[SAVE_LOCATION_ON_SAVE_AUDIO] = save_location_on_audio_save }
    }
}