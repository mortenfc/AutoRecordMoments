package com.mfc.recentaudiobuffer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import timber.log.Timber
import java.io.File
import java.io.IOException
import androidx.core.content.edit
import androidx.core.net.toUri
import com.mfc.recentaudiobuffer.WavUtils.writeWavHeader

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
        val hasPermissions = context.contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission && it.isWritePermission }

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
     * Saves a byte array of raw audio data to a temporary file in the app's cache directory.
     * This is the reliable way to pass data to the FileSavingService.
     *
     * @return The Uri of the created temporary file, or null on failure.
     */
    fun saveBufferToTempFile(context: Context, data: ByteArray): Uri? {
        return try {
            val tempFile = File.createTempFile("recording_buffer", ".raw", context.cacheDir)
            tempFile.outputStream().use {
                it.write(data)
            }
            Uri.fromFile(tempFile)
        } catch (e: IOException) {
            Timber.e(e, "Failed to create or write to temporary file.")
            null
        }
    }

    fun saveDebugFile(context: Context, fileName: String, data: ByteArray, config: AudioConfig) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val collection =
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val contentResolver = context.contentResolver
        var fileUri: Uri? = null

        try {
            fileUri = contentResolver.insert(collection, values)
            if (fileUri == null) {
                throw IOException("Failed to create new MediaStore record for $fileName")
            }

            contentResolver.openOutputStream(fileUri)?.use { stream ->
                writeWavHeader(stream, data.size, config)
                stream.write(data)
                stream.flush()
                Timber.d("DEBUG file saved successfully to URI: $fileUri")
            } ?: throw IOException("Failed to open output stream for $fileUri")

        } catch (e: Exception) {
            Timber.e(e, "Error writing debug file with MediaStore: $fileName")
            fileUri?.let { contentResolver.delete(it, null, null) }
        }
    }
}
