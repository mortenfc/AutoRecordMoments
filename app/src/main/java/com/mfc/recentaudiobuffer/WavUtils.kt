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

package com.mfc.recentaudiobuffer

import timber.log.Timber
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A utility object for handling WAV file header operations.
 */
object WavUtils {
    const val WAV_HEADER_SIZE = 44

    /**
     * Writes a standard 44-byte WAV header to the given output stream.
     *
     * @param out The stream to write to.
     * @param audioDataLen The length of the raw audio data (PCM) in bytes.
     * @param config The audio configuration containing sample rate and bit depth.
     */
    fun writeWavHeader(out: OutputStream, audioDataLen: Int, config: AudioConfig) {
        val channels: Short = 1 // Mono
        val sampleRate = config.sampleRateHz
        val bitsPerSample = config.bitDepth.bits.toShort()
        val sampleSize = bitsPerSample / 8
        val chunkSize = audioDataLen + 36
        val byteRate = sampleRate * channels * sampleSize

        fun intToBytes(i: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array()

        fun shortToBytes(s: Short): ByteArray =
            ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(s).array()

        try {
            out.write("RIFF".toByteArray())
            out.write(intToBytes(chunkSize))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(intToBytes(16)) // Sub-chunk size for PCM
            out.write(shortToBytes(1.toShort())) // AudioFormat 1 for PCM
            out.write(shortToBytes(channels))
            out.write(intToBytes(sampleRate))
            out.write(intToBytes(byteRate))
            out.write(shortToBytes((channels * sampleSize).toShort())) // Block align
            out.write(shortToBytes(bitsPerSample))
            out.write("data".toByteArray())
            out.write(intToBytes(audioDataLen))
        } catch (e: IOException) {
            Timber.e(e, "Failed to write WAV header")
            // Re-throw to allow the caller to handle the failed write operation
            throw e
        }
    }

    /**
     * Reads the sample rate and bit depth from a WAV file's header.
     */
    fun readWavHeader(wavBytes: ByteArray): AudioConfig {
        if (wavBytes.size < WAV_HEADER_SIZE) {
            throw IllegalArgumentException("Invalid WAV header: file is too small.")
        }
        val buffer = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = buffer.getInt(24)
        val bitDepthValue = buffer.getShort(34).toInt()

        Timber.d("Read from WAV: sampleRate: $sampleRate, bitDepth: $bitDepthValue")

        val bitDepth = bitDepths["$bitDepthValue"]
            ?: throw IllegalArgumentException("Unsupported bit depth found in WAV header: $bitDepthValue")

        return AudioConfig(sampleRate, 0, bitDepth)
    }
}