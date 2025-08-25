package com.mfc.recentaudiobuffer

import timber.log.Timber
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavUtils {
    const val WAV_HEADER_SIZE = 44

    /**
     * Writes a standard 44-byte WAV header to the given output stream.
     *
     * @param out The OutputStream to write to.
     * @param audioDataSize The size of the raw audio data (PCM) in bytes.
     * @param config The AudioConfig containing sample rate, bit depth, etc.
     */
    fun writeWavHeader(out: OutputStream, audioDataSize: Int, config: AudioConfig) {
        val totalDataLen = audioDataSize + 36
        val sampleRate = config.sampleRateHz.toLong()
        val channels = 1 // Mono
        val bitDepth = config.bitDepth.bits
        val byteRate = (sampleRate * channels * bitDepth) / 8

        val header = ByteArray(WAV_HEADER_SIZE)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk descriptor
        buffer.put('R'.code.toByte())
        buffer.put('I'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.putInt(totalDataLen)
        buffer.put('W'.code.toByte())
        buffer.put('A'.code.toByte())
        buffer.put('V'.code.toByte())
        buffer.put('E'.code.toByte())

        // "fmt " sub-chunk
        buffer.put('f'.code.toByte())
        buffer.put('m'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put(' '.code.toByte())
        buffer.putInt(16) // Sub-chunk size (16 for PCM)
        buffer.putShort(1.toShort()) // Audio format (1 for PCM)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate.toInt())
        buffer.putInt(byteRate.toInt())
        buffer.putShort((channels * bitDepth / 8).toShort()) // Block align
        buffer.putShort(bitDepth.toShort())

        // "data" sub-chunk
        buffer.put('d'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.putInt(audioDataSize)

        out.write(header)
    }

    /**
     * Reads the WAV header from a byte array to extract the audio configuration.
     *
     * @param wavBytes The complete byte array of the WAV file.
     * @return An AudioConfig object, or null if the header is invalid.
     */
    fun readWavHeader(wavBytes: ByteArray): AudioConfig? {
        // FIX: Add a size check to prevent crashing on small/empty files.
        if (wavBytes.size < WAV_HEADER_SIZE) {
            Timber.w("File is smaller than a WAV header, cannot process.")
            return null
        }

        try {
            val buffer = ByteBuffer.wrap(wavBytes, 0, WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

            // Check RIFF and WAVE identifiers
            if (buffer.get().toInt() != 'R'.code || buffer.get().toInt() != 'I'.code ||
                buffer.get().toInt() != 'F'.code || buffer.get().toInt() != 'F'.code) {
                return null
            }
            buffer.int // Skip chunk size
            if (buffer.get().toInt() != 'W'.code || buffer.get().toInt() != 'A'.code ||
                buffer.get().toInt() != 'V'.code || buffer.get().toInt() != 'E'.code) {
                return null
            }

            // Find "fmt " sub-chunk
            while (buffer.get().toInt() != 'f'.code || buffer.get().toInt() != 'm'.code ||
                   buffer.get().toInt() != 't'.code || buffer.get().toInt() != ' '.code) {
                if (!buffer.hasRemaining()) return null
            }

            buffer.int // Skip sub-chunk size
            buffer.short // Skip audio format
            val channels = buffer.short.toInt()
            val sampleRate = buffer.int
            buffer.int // Skip byte rate
            buffer.short // Skip block align
            val bitDepth = buffer.short.toInt()

            Timber.d("Read from WAV: sampleRate: $sampleRate, bitDepth: $bitDepth")

            // Find the BitDepth object that matches the read value
            val bitDepthObject = bitDepths.values.find { it.bits == bitDepth } ?: return null

            return AudioConfig(
                sampleRateHz = sampleRate,
                bitDepth = bitDepthObject
            )
        } catch (e: Exception) {
            Timber.e(e, "Error reading WAV header")
            return null
        }
    }
}
