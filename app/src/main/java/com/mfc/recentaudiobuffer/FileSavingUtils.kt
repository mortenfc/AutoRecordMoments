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

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.mfc.recentaudiobuffer.WavUtils.writeWavHeader
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A collection of stateless utility functions for file and Uri operations.
 * This object no longer holds mutable state.
 */
object FileSavingUtils {

    /**
     * Checks if a given SAF (Storage Access Framework) Uri is valid and points to an accessible directory.
     */
    fun isUriValidAndAccessible(context: Context, uri: Uri?): Boolean {
        if (uri == null) {
            Timber.w("isUriValidAndAccessible: URI is null")
            return false
        }

        // Check that we have persistable permissions for this URI
        val hasPermissions =
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }

        if (!hasPermissions) {
            Timber.w("No persistable write permissions for URI: $uri")
            return false
        }

        val documentFile = DocumentFile.fromTreeUri(context, uri)
        if (documentFile == null || !documentFile.exists() || !documentFile.isDirectory) {
            Timber.w("DocumentFile is null, does not exist, or is not a directory: $uri")
            return false
        }

        return true
    }

    /**
     * Caches the granted directory Uri in SharedPreferences for later use.
     */
    fun cacheGrantedUri(context: Context, uri: Uri) {
        val sharedPrefs = context.getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit { putString("grantedUri", uri.toString()) }
    }

    /**
     * Retrieves the cached directory Uri from SharedPreferences.
     */
    fun getCachedGrantedUri(context: Context): Uri? {
        val sharedPrefs = context.getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        val uriString = sharedPrefs.getString("grantedUri", null)
        return uriString?.toUri()
    }

    /**
     * Saves a bytebuffer of raw audio data to a temporary file in the app's cache directory.
     * This is the reliable way to pass data to the FileSavingService.
     *
     * @return The Uri of the created temporary file, or null on failure.
     */
    fun saveBufferToTempFile(context: Context, data: ByteBuffer): Uri? {
        return try {
            val tempFile = File.createTempFile("recording_buffer", ".raw", context.cacheDir)

            // 1. Use a FileOutputStream which gives access to a channel
            FileOutputStream(tempFile).use { fileOutputStream ->
                // 2. Get the channel from the stream
                fileOutputStream.channel.use { channel ->
                    // 3. Write the ByteBuffer directly to the channel
                    channel.write(data)
                }
            }
            Uri.fromFile(tempFile)
        } catch (e: IOException) {
            Timber.e(e, "Failed to create or write to temporary file.")
            null
        }
    }

    @VisibleForTesting
    fun saveDebugFile(context: Context, fileName: String, data: ByteBuffer, config: AudioConfig) {
        try {
            // Create debug directory in app's external files
            // Path: /storage/emulated/0/Android/data/com.mfc.recentaudiobuffer/files/debug/
            val debugDir = File(context.getExternalFilesDir(null), "debug")
            if (!debugDir.exists()) {
                debugDir.mkdirs()
            }

            val debugFile = File(debugDir, fileName)

            FileOutputStream(debugFile).use { fileOutputStream ->
                // Write WAV header
                writeWavHeader(fileOutputStream, data.remaining(), config)

                // Write audio data
                fileOutputStream.channel.use { channel ->
                    data.rewind()
                    channel.write(data)
                }
            }

            Timber.d("DEBUG file saved to: ${debugFile.absolutePath}")
            Timber.d("Access via: Android/data/${context.packageName}/files/debug/$fileName")

        } catch (e: Exception) {
            Timber.e(e, "Error writing debug file: $fileName")
        }
    }

    @VisibleForTesting
    fun clearCachedUri(context: Context) {
        val sharedPrefs = context.getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit { remove("grantedUri") }
    }
}
