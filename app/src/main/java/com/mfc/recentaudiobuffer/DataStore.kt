package com.mfc.recentaudiobuffer

import android.content.Context
import android.media.AudioFormat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class BitDepth(val bytes: Int, val encodingEnum: Int) {
    override fun toString(): String {
        return "$bytes,$encodingEnum"
    }

//    companion object {
//        fun fromString(value: String): BitDepth {
//            val (bytes, encoding) = value.split(",").map { it.toInt() }
//            return BitDepth(bytes, encoding)
//        }
//    }
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

public data class AudioConfig(var SAMPLE_RATE_HZ: Int, var BUFFER_TIME_LENGTH_S: Int, var BIT_DEPTH: BitDepth)

data class Settings(val sampleRate: Int, val bitDepth: BitDepth, val bufferTimeLengthS: Int)

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    companion object {
        val SAMPLE_RATE = intPreferencesKey("sample_rate")
        val BIT_DEPTH = stringPreferencesKey("bit_depth")
        val BUFFER_TIME_LENGTH_S = intPreferencesKey("buffer_time_length")
    }

    private val settings: Flow<Settings> = dataStore.data.map { preferences ->
        Settings(
            sampleRate = preferences[SAMPLE_RATE] ?: 22050,
            bitDepth = bitDepths[preferences[BIT_DEPTH] ?: "8"] ?: error("Invalid bit depth"),
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
        dataStore.edit { preferences ->
            preferences[SAMPLE_RATE] = sampleRate
        }
    }

    suspend fun updateBitDepth(bitDepth: BitDepth) {
        dataStore.edit { preferences ->
            preferences[BIT_DEPTH] = bitDepth.toString()
        }
    }

    suspend fun updateBufferTimeLength(bufferTimeLength: Int) {
        dataStore.edit { preferences ->
            preferences[BUFFER_TIME_LENGTH_S] = bufferTimeLength
        }
    }
}