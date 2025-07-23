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
}
