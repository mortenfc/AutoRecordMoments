
package com.mfc.recentaudiobuffer

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import java.io.File
import java.io.IOException

class FileSavingService : Service() {
    companion object {
        private const val RESULT_NOTIFICATION_ID = 2
        const val RESULT_NOTIFICATION_CHANNEL_ID = "result_channel"
        const val RESULT_NOTIFICATION_CHANNEL_NAME = "Result of an operation"
        const val RESULT_NOTIFICATION_CHANNEL_DESCRIPTION = "A user message as a notification"
        const val ACTION_OPEN_FILE = "com.mfc.recentaudiobuffer.ACTION_OPEN_FILE"

        // NEW: Action for silent auto-saves
        const val ACTION_SILENT_SAVE_COMPLETE = "com.mfc.recentaudiobuffer.ACTION_SILENT_SAVE_COMPLETE"

        // Intent Extras
        const val EXTRA_TEMP_FILE_URI = "com.mfc.recentaudiobuffer.EXTRA_TEMP_FILE_URI"
        const val EXTRA_DEST_DIR_URI = "com.mfc.recentaudiobuffer.EXTRA_DEST_DIR_URI"
        const val EXTRA_DEST_FILENAME = "com.mfc.recentaudiobuffer.EXTRA_DEST_FILENAME"
        const val EXTRA_AUDIO_CONFIG = "com.mfc.recentaudiobuffer.EXTRA_AUDIO_CONFIG"
        const val EXTRA_SAVED_FILE_URI = "com.mfc.recentaudiobuffer.EXTRA_SAVED_FILE_URI"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tempFileUri = intent?.getParcelableExtra<Uri>(EXTRA_TEMP_FILE_URI)
        val destDirUri = intent?.getParcelableExtra<Uri>(EXTRA_DEST_DIR_URI)
        val destFileName = intent?.getStringExtra(EXTRA_DEST_FILENAME)
        val audioConfig = intent?.getParcelableExtra<AudioConfig>(EXTRA_AUDIO_CONFIG)

        if (tempFileUri == null || destDirUri == null || destFileName == null || audioConfig == null) {
            Timber.e("FileSavingService started with incomplete data. Aborting.")
            // For silent saves, we don't want to post a failure notification that might spam the user.
            if (intent?.action != ACTION_SILENT_SAVE_COMPLETE) {
                postResultNotification(
                    success = false,
                    title = "ERROR: Save Failed",
                    text = "Could not save audio due to an internal error.",
                    savedFileUri = null
                )
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val savedFileUri = saveFile(tempFileUri, destDirUri, destFileName, audioConfig)

        when (intent.action) {
            ACTION_SILENT_SAVE_COMPLETE -> {
                if (savedFileUri != null) {
                    Timber.d("Silent save successful for $destFileName")
                    // Optionally, post a less intrusive notification for auto-saves
                    postResultNotification(
                        success = true,
                        title = "Auto-Saved Recording",
                        text = "$destFileName was saved automatically.",
                        savedFileUri = savedFileUri
                    )
                } else {
                    Timber.e("Silent save failed for $destFileName")
                    // We typically don't notify the user of a failed auto-save to avoid spam.
                }
            }
            else -> { // Default behavior for manual saves
                if (savedFileUri != null) {
                    val resetIntent = Intent(this, MyBufferService::class.java).apply {
                        action = MyBufferService.ACTION_ON_SAVE_SUCCESS
                    }
                    startService(resetIntent)
                    postResultNotification(
                        success = true,
                        title = "Audio Saved",
                        text = "The audio has been successfully saved.",
                        savedFileUri = savedFileUri
                    )
                } else {
                    val resetIntent = Intent(this, MyBufferService::class.java).apply {
                        action = MyBufferService.ACTION_ON_SAVE_FAIL
                    }
                    startService(resetIntent)
                    postResultNotification(
                        success = false,
                        title = "ERROR: Save Failed",
                        text = "Could not write the audio file to the destination.",
                        savedFileUri = null
                    )
                }
            }
        }

        cleanupTempFile(tempFileUri)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun saveFile(
        tempFileUri: Uri,
        destDirUri: Uri,
        fileName: String,
        config: AudioConfig
    ): Uri? {
        val destDir = DocumentFile.fromTreeUri(this, destDirUri)
        if (destDir == null || !destDir.exists() || !destDir.isDirectory) {
            Timber.e("Destination directory is invalid or doesn't exist: $destDirUri")
            return null
        }

        destDir.findFile(fileName)?.delete()

        val finalFile = destDir.createFile("audio/wav", fileName)
        if (finalFile == null) {
            Timber.e("Failed to create destination file: $fileName in $destDirUri")
            return null
        }

        try {
            contentResolver.openInputStream(tempFileUri)?.use { inputStream ->
                contentResolver.openOutputStream(finalFile.uri)?.use { outputStream ->
                    val audioDataSize = inputStream.available()
                    WavUtils.writeWavHeader(outputStream, audioDataSize, config)
                    inputStream.copyTo(outputStream)
                    Timber.d("File saved successfully to ${finalFile.uri}")
                    return finalFile.uri
                }
            }
        } catch (e: IOException) {
            Timber.e(e, "Failed to stream data to final destination.")
            finalFile.delete()
        }
        return null
    }

    private fun cleanupTempFile(tempFileUri: Uri) {
        try {
            val tempFile = File(tempFileUri.path!!)
            if (tempFile.exists()) {
                if (!tempFile.delete()) {
                    Timber.w("Failed to delete temporary file: $tempFileUri")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error cleaning up temporary file.")
        }
    }

    @OptIn(UnstableApi::class)
    private fun postResultNotification(
        success: Boolean,
        title: String,
        text: String,
        savedFileUri: Uri?
    ) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notificationBuilder =
            NotificationCompat.Builder(this, RESULT_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(if (success) R.drawable.file_save_success else R.drawable.file_save_failure_notification_icon)
                .setAutoCancel(true)
                .setTimeoutAfter(1000 * 60) // 1 minute

        if (success && savedFileUri != null) {
            val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_FILE
                putExtra(EXTRA_SAVED_FILE_URI, savedFileUri)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                savedFileUri.hashCode(), // Use a unique request code for each file
                mainActivityIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            notificationBuilder.setContentIntent(pendingIntent)
        }

        // Use a unique notification ID for each file to prevent them from overwriting each other
        val notificationId = savedFileUri?.hashCode() ?: RESULT_NOTIFICATION_ID
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    override fun onBind(intent: Intent?): IBinder? = null
}