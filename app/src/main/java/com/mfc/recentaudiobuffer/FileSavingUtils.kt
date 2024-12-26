package com.mfc.recentaudiobuffer

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileSavingService : Service() {
    companion object{
        private const val RESULT_NOTIFICATION_ID = 2
        public const val RESULT_NOTIFICATION_CHANNEL_ID = "result_channel"
        public const val RESULT_NOTIFICATION_CHANNEL_NAME = "Result of an operation"
        public const val RESULT_NOTIFICATION_CHANNEL_DESCRIPTION =
            "A user message as a notification"
    }

    private val logTag = "FileSavingService"
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val grantedUri = intent?.getParcelableExtra<Uri>("grantedUri")
        val audioData = intent?.getByteArrayExtra("audioData")
        Log.d(logTag, "FileSavingService: grantedUri = $grantedUri")

        if (grantedUri != null && audioData != null) {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val (title, text, icon) = if (FileSavingUtils.fixBaseNameToSave(
                    this,
                    grantedUri,
                    audioData,
                    "quick_save"
                )
            ) {
                Triple(
                    "Audio Saved and Cleared",
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

            val notification = NotificationCompat.Builder(this, RESULT_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(icon)
                .build()
            notificationManager.notify(RESULT_NOTIFICATION_ID, notification)
        } else {
            Log.e(logTag, "Failed to save file to $grantedUri, of data: $audioData")
        }

        stopSelf() // Stop the service after saving
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }
}

object FileSavingUtils {
    private const val logTag = "FileSavingUtils"
    private fun saveFile(
        context: Context, myBufferService: MyBufferServiceInterface, data: ByteArray, fileUri: Uri
    ): Boolean {
        var success = false
        try {
            val outputStream = context.contentResolver.openOutputStream(fileUri)
            outputStream?.let { stream ->
                try {
                    myBufferService.writeWavHeader(
                        stream, data.size.toLong()
                    )
                    stream.write(data)
                    stream.flush()
                    Log.d(logTag, "File saved successfully")

                    success = true
                } catch (e: IOException) {
                    Log.e(logTag, "Error writing data to stream", e)
                } finally {
                    stream.close()
                }
            } ?: run {
                Log.e(logTag, "Failed to open output stream for $fileUri")
            }
        } catch (e: IOException) {
            Log.e(logTag, "Failed to save file to $fileUri", e)
        } catch (e: SecurityException) {
            Log.e(logTag, "Permission denied to access $fileUri", e)
        }

        return success
    }

    public fun cacheGrantedUri(context: Context, uri: Uri) {
        val sharedPrefs = context.getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("grantedUri", uri.toString()).apply()
    }

    public fun getCachedGrantedUri(context: Context): Uri? {
        val sharedPrefs = context.getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        val uriString = sharedPrefs.getString("grantedUri", null)
        return if (uriString != null) Uri.parse(uriString) else null
    }

    @SuppressLint("RestrictedApi")
    fun fixBaseNameToSave(
        context: Context, grantedDirectoryUri: Uri, data: ByteArray, baseNameInput: String
    ): Boolean {
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
            Log.e(logTag, "Invalid directory URI or directory does not exist: $grantedDirectoryUri")
            return false
        }

        var file = directory.findFile(filename)
        if (file != null) {
            // File exists, prompt for overwrite
            var overwrite = false
            val dialog = AlertDialog.Builder(context)
                .setTitle("File Exists")
                .setMessage("A file with the name '$filename' already exists. Do you want to overwrite it?")
                .setPositiveButton("Overwrite") { _, _ -> overwrite = true }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
                .create()
            dialog.show()
            if (!overwrite) {
                Log.i(logTag, "File not overwritten: $filename")
                return false
            }
            file.delete()
        }
        file = directory.createFile("audio/wav", filename)
        if (file == null) {
            Log.e(logTag, "Failed to create file: $filename")
            return false
        }

        return saveFile(
            context, ViewModelHolder.getSharedViewModel().myBufferService!!, data, file.uri
        )
    }

    @SuppressLint("RestrictedApi")
    public fun promptSaveFileName(
        context: Context, grantedDirectoryUri: Uri, data: ByteArray
    ): Boolean {
        var success = false
        Log.i(logTag, "saveBufferToFile()")
        // 1. Prompt for filename here
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Enter Filename")
        val input = EditText(context)
        input.setText(context.getString(R.string.DefaultRecordingName))
        builder.setView(input)
        builder.setPositiveButton("OK") { _, _ ->
            success = fixBaseNameToSave(context, grantedDirectoryUri, data, input.text.toString())
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()

        return success
    }
}