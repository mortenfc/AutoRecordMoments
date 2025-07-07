package com.mfc.recentaudiobuffer

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.app.PendingIntent
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import timber.log.Timber

class FileSavingService : Service() {
    companion object {
        private const val RESULT_NOTIFICATION_ID = 2
        const val RESULT_NOTIFICATION_CHANNEL_ID = "result_channel"
        const val RESULT_NOTIFICATION_CHANNEL_NAME = "Result of an operation"
        const val RESULT_NOTIFICATION_CHANNEL_DESCRIPTION = "A user message as a notification"
        const val ACTION_OPEN_FILE = "com.mfc.recentaudiobuffer.ACTION_OPEN_FILE"
        const val EXTRA_SAVED_FILE_URI = "com.mfc.recentaudiobuffer.EXTRA_SAVED_FILE_URI"
    }

    private val logTag = "FileSavingService"

    @OptIn(UnstableApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val grantedUri = intent?.getParcelableExtra<Uri>("grantedUri")
        // Get the big buffer from the static variable. IPC has a limit of 1 MB of data to send with intents
        val audioData = MyBufferService.sharedAudioDataToSave
        Timber.d("FileSavingService: grantedUri = $grantedUri")

        if (grantedUri != null && audioData != null) {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val savedFileUri = FileSavingUtils.fixBaseNameToSave(
                this, grantedUri, audioData, "quick_save"
            )
            var (title, text, icon) = if (savedFileUri != null) {
                Triple(
                    "Audio saved to file",
                    "The recent audio buffer has been saved and cleared.",
                    R.drawable.file_save_success
                )
            } else {
                Triple(
                    "ERROR: Audio failed to save",
                    "Use the app view instead",
                    R.drawable.file_save_failure_notification_icon
                )
            }

            val notificationBuilder =
                NotificationCompat.Builder(this, RESULT_NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(title).setContentText(text).setSmallIcon(icon)
                    .setAutoCancel(true).setTimeoutAfter(1000 * 60) // 1 minute

            if (savedFileUri != null) {
                // Launch MainActivity with the saved file URI
                val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
                    action = ACTION_OPEN_FILE
                    putExtra(EXTRA_SAVED_FILE_URI, savedFileUri)
                }
                val mainActivityPendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    mainActivityIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                Timber.d(
                    "PendingIntent $mainActivityPendingIntent \n to launch MainActivity to open file URI: $savedFileUri"
                )

                notificationBuilder.setContentIntent(mainActivityPendingIntent)
            } else {
                Timber.e("Failed to create PendingIntent to open file")
                // Update the notification text on failure
                text = "ERROR: Failed to open saved file..."
                notificationBuilder.setContentText(text)
            }

            val notification = notificationBuilder.build()
            notificationManager.notify(RESULT_NOTIFICATION_ID, notification)
            // Clear the static variable after saving
            MyBufferService.sharedAudioDataToSave = null
        } else {
            Timber.e("Failed to save file to $grantedUri, of data: $audioData")
        }

        stopSelf() // Stop the service after saving
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }
}

object FileSavingUtils {
    var showSavingDialog by mutableStateOf(false)
    var showDirectoryPermissionDialog by mutableStateOf(false)
    private const val logTag = "FileSavingUtils"
    var currentGrantedDirectoryUri: Uri? = null
    var currentData: ByteArray? = null

    private fun saveFile(
        context: Context, myBufferService: MyBufferServiceInterface, data: ByteArray, fileUri: Uri
    ): Boolean {
        var success = false
        try {
            val outputStream = context.contentResolver.openOutputStream(fileUri)
            outputStream?.let { stream ->
                try {
                    myBufferService.writeWavHeader(
                        stream, data.size
                    )
                    stream.write(data)
                    stream.flush()
                    Timber.d("File saved successfully")

                    success = true
                } catch (e: IOException) {
                    Timber.e("Error writing data to stream $e")
                } finally {
                    stream.close()
                }
            } ?: run {
                Timber.e("Failed to open output stream for $fileUri")
            }
        } catch (e: IOException) {
            Timber.e("Failed to save file to $fileUri $e")
        } catch (e: SecurityException) {
            Timber.e("Permission denied to access $fileUri $e")
        }

        return success
    }

    fun isUriValidAndAccessible(context: Context, uri: Uri?): Boolean {
        if (uri == null) {
            Timber.e("isUriValidAndAccessible: URI is null")
            return false
        }

        if (!isUriSaf(uri)) {
            Timber.e("isUriValidAndAccessible: URI is not a SAF URI: $uri")
            return false
        }

        val documentFile = DocumentFile.fromTreeUri(context, uri)
        if (documentFile == null || !documentFile.exists() || !documentFile.isDirectory) {
            Timber.e(
                "isUriValidAndAccessible: DocumentFile is null, does not exist, or is not a directory: $uri"
            )
            return false
        }

        return true
    }

    private fun isUriSaf(uri: Uri): Boolean {
        return uri.scheme == "content" && uri.authority != null
    }

    fun cacheGrantedUri(uri: Uri) {
        val sharedPrefs = RecentAudioBufferApplication.instance.getSharedPreferences(
            "MyPrefs", Context.MODE_PRIVATE
        )
        sharedPrefs.edit().putString("grantedUri", uri.toString()).apply()
    }

    fun getCachedGrantedUri(): Uri? {
        val sharedPrefs = RecentAudioBufferApplication.instance.getSharedPreferences(
            "MyPrefs", Context.MODE_PRIVATE
        )
        val uriString = sharedPrefs.getString("grantedUri", null)
        return if (uriString != null) Uri.parse(uriString) else null
    }

    @SuppressLint("RestrictedApi")
    fun fixBaseNameToSave(
        context: Context, grantedDirectoryUri: Uri, data: ByteArray, baseNameInput: String
    ): Uri? {
        var filename = if (baseNameInput.endsWith(".wav", ignoreCase = true)) {
            baseNameInput // Already ends with .wav, no change needed
        } else {
            val baseName = baseNameInput.substringBeforeLast(".") // Extract base name
            "$baseName.wav" // Append .wav to the base name
        }

        val timestamp = SimpleDateFormat("yy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        filename = "${filename.substringBeforeLast(".")}_${timestamp}.wav"

        val directory = DocumentFile.fromTreeUri(context, grantedDirectoryUri)
        if (directory == null || !directory.exists() || !directory.isDirectory) {
            Timber.e("Invalid directory URI or directory does not exist: $grantedDirectoryUri")
            return null
        }

        var file = directory.findFile(filename)
        if (file != null) {
            // File exists, prompt for overwrite
            var overwrite = false
            val dialog = AlertDialog.Builder(context).setTitle("File Exists")
                .setMessage("A file with the name '$filename' already exists. Do you want to overwrite it?")
                .setPositiveButton("Overwrite") { _, _ -> overwrite = true }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }.create()
            dialog.show()
            if (!overwrite) {
                Timber.i("File not overwritten: $filename")
                return null
            }
            file.delete()
        }
        file = directory.createFile("audio/wav", filename)
        if (file == null) {
            Timber.e("Failed to create file: $filename")
            return null
        }
        val success = saveFile(
            context,
            RecentAudioBufferApplication.getSharedViewModel().myBufferService!!,
            data,
            file.uri
        )
        return if (success) file.uri else null
    }

    fun promptSaveFileName(
        grantedDirectoryUri: Uri, data: ByteArray
    ) {
        currentGrantedDirectoryUri = grantedDirectoryUri
        currentData = data
        showSavingDialog = true
    }
}