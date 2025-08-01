package com.mfc.recentaudiobuffer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

// Create a new class for your readFromBuffer tests
class ReadFromBufferUnitTest {

    @Test
    fun `readFromBuffer - reads data correctly when not wrapping around`() {
        val buffer = ByteArray(10) { it.toByte() } // [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]
        var writeIndex = 5
        var readIndex = 0

        val dest = ByteArray(3)
        val bytesRead = readFromBuffer(dest, 0, 3, buffer, writeIndex, readIndex)

        assertEquals(3, bytesRead)
        assertArrayEquals(byteArrayOf(0, 1, 2), dest)
        assertEquals(3, readIndex) // readIndex should be updated
    }

    @Test
    fun `readFromBuffer - reads data correctly when wrapping around`() {
        val buffer = ByteArray(10) { it.toByte() } // [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]
        var writeIndex = 2
        var readIndex = 8

        val dest = ByteArray(3)
        val bytesRead = readFromBuffer(dest, 0, 3, buffer, writeIndex, readIndex)

        assertEquals(3, bytesRead)
        assertArrayEquals(byteArrayOf(8, 9, 0), dest) // Wraps around
        assertEquals(1, readIndex) // readIndex should wrap around
    }

    @Test
    fun `readFromBuffer - reads all available data when requested bytes exceed available bytes`() {
        val buffer = ByteArray(10) { it.toByte() } // [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]
        var writeIndex = 5
        var readIndex = 0

        val dest = ByteArray(7)
        val bytesRead = readFromBuffer(dest, 0, 7, buffer, writeIndex, readIndex)

        assertEquals(5, bytesRead) // Should read only 5 available bytes
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), dest.copyOf(bytesRead))
        assertEquals(5, readIndex)
    }

    // Helper function to simulate readFromBuffer behavior
    private fun readFromBuffer(
        dest: ByteArray, offset: Int, bytesToRead: Int,
        buffer: ByteArray, writeIndex: Int, readIndex: Int
    ): Int {
        val bytesAvailable = if (writeIndex >= readIndex) {
            writeIndex - readIndex
        } else {
            buffer.size - readIndex + writeIndex
        }

        val bytesToCopy = minOf(bytesToRead, bytesAvailable)

        System.arraycopy(buffer, readIndex, dest, offset, bytesToCopy)

        return bytesToCopy
    }
}
