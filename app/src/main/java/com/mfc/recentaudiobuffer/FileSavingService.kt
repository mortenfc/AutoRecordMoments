package com.mfc.recentaudiobuffer

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * A service dedicated to writing a file to its final destination.
 * It receives a Uri to a temporary file in the app's cache and a destination directory Uri.
 * It streams the data, posts a result notification, and cleans up the temporary file.
 */
class FileSavingService : Service() {
    companion object {
        private const val RESULT_NOTIFICATION_ID = 2
        const val RESULT_NOTIFICATION_CHANNEL_ID = "result_channel"
        const val RESULT_NOTIFICATION_CHANNEL_NAME = "Result of an operation"
        const val RESULT_NOTIFICATION_CHANNEL_DESCRIPTION = "A user message as a notification"
        const val ACTION_OPEN_FILE = "com.mfc.recentaudiobuffer.ACTION_OPEN_FILE"

        // Intent Extras
        const val EXTRA_TEMP_FILE_URI = "com.mfc.recentaudiobuffer.EXTRA_TEMP_FILE_URI"
        const val EXTRA_DEST_DIR_URI = "com.mfc.recentaudiobuffer.EXTRA_DEST_DIR_URI"
        const val EXTRA_DEST_FILENAME = "com.mfc.recentaudiobuffer.EXTRA_DEST_FILENAME"
        const val EXTRA_AUDIO_CONFIG = "com.mfc.recentaudiobuffer.EXTRA_AUDIO_CONFIG"
        const val EXTRA_SAVED_FILE_URI = "com.mfc.recentaudiobuffer.EXTRA_SAVED_FILE_URI"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Extract data from the intent
        val tempFileUri = intent?.getParcelableExtra<Uri>(EXTRA_TEMP_FILE_URI)
        val destDirUri = intent?.getParcelableExtra<Uri>(EXTRA_DEST_DIR_URI)
        val destFileName = intent?.getStringExtra(EXTRA_DEST_FILENAME)
        val audioConfig = intent?.getParcelableExtra<AudioConfig>(EXTRA_AUDIO_CONFIG)

        if (tempFileUri == null || destDirUri == null || destFileName == null || audioConfig == null) {
            Timber.e("FileSavingService started with incomplete data. Aborting.")
            postResultNotification(
                success = false,
                title = "ERROR: Save Failed",
                text = "Could not save audio due to an internal error.",
                savedFileUri = null
            )
            stopSelf()
            return START_NOT_STICKY
        }

        // Perform the file operation
        val savedFileUri = saveFile(tempFileUri, destDirUri, destFileName, audioConfig)

        // Post the result notification
        if (savedFileUri != null) {
            postResultNotification(
                success = true,
                title = "Audio Saved",
                text = "The audio has been successfully saved.",
                savedFileUri = savedFileUri
            )
        } else {
            postResultNotification(
                success = false,
                title = "ERROR: Save Failed",
                text = "Could not write the audio file to the destination.",
                savedFileUri = null
            )
        }

        // Clean up the temporary file
        cleanupTempFile(tempFileUri)

        stopSelf()
        return START_NOT_STICKY
    }

    /**
     * Streams data from the temporary file to the final destination file.
     * @return The Uri of the successfully saved file, or null on failure.
     */
    private fun saveFile(
        tempFileUri: Uri,
        destDirUri: Uri,
        fileName: String,
        config: AudioConfig
    ): Uri? {
        val destDir = if (destDirUri.scheme == "file") {
            // For testing with the local cache directory (file://)
            DocumentFile.fromFile(File(destDirUri.path!!))
        } else {
            // For production with a user-selected directory (content://)
            DocumentFile.fromTreeUri(this, destDirUri)
        }
        if (destDir == null || !destDir.exists() || !destDir.isDirectory) {
            Timber.e("Destination directory is invalid or doesn't exist: $destDirUri")
            return null
        }

        // Overwrite if exists, as MainActivity should have already prompted the user.
        destDir.findFile(fileName)?.delete()

        val finalFile = destDir.createFile("audio/wav", fileName)
        if (finalFile == null) {
            Timber.e("Failed to create destination file: $fileName in $destDirUri")
            return null
        }

        try {
            // Open streams using 'use' for automatic closing
            contentResolver.openInputStream(tempFileUri)?.use { inputStream ->
                contentResolver.openOutputStream(finalFile.uri)?.use { outputStream ->
                    // Get the size of the raw audio data from the temp file
                    val audioDataSize = inputStream.available()

                    // Write the proper WAV header first
                    WavUtils.writeWavHeader(outputStream, audioDataSize, config)

                    // Copy the raw audio data from the temp file to the final file
                    inputStream.copyTo(outputStream)
                    Timber.d("File saved successfully to ${finalFile.uri}")
                    return finalFile.uri
                }
            }
        } catch (e: IOException) {
            Timber.e(e, "Failed to stream data to final destination.")
            // Clean up partially created file on error
            finalFile.delete()
        }
        return null
    }

    /**
     * Deletes the temporary file from the cache.
     */
    private fun cleanupTempFile(tempFileUri: Uri) {
        try {
            val tempFile = File(tempFileUri.path!!)
            if (tempFile.exists()) {
                if (tempFile.delete()) {
                    Timber.d("Temporary file deleted: $tempFileUri")
                } else {
                    Timber.w("Failed to delete temporary file: $tempFileUri")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error cleaning up temporary file.")
        }
    }

    /**
     * Creates and displays a notification with the result of the save operation.
     */
    @OptIn(UnstableApi::class)
    private fun postResultNotification(
        success: Boolean,
        title: String,
        text: String,
        savedFileUri: Uri?
    ) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notificationBuilder =
            NotificationCompat.Builder(this, RESULT_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(if (success) R.drawable.file_save_success else R.drawable.file_save_failure_notification_icon)
                .setAutoCancel(true)
                .setTimeoutAfter(1000 * 60) // 1 minute

        if (success && savedFileUri != null) {
            // Create an intent to open MainActivity when the notification is tapped
            val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_FILE
                putExtra(EXTRA_SAVED_FILE_URI, savedFileUri)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                mainActivityIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            notificationBuilder.setContentIntent(pendingIntent)
        }

        notificationManager.notify(RESULT_NOTIFICATION_ID, notificationBuilder.build())
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
