package com.mfc.recentaudiobuffer

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.test.internal.runner.junit4.statement.UiThreadStatement.runOnUiThread
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileSavingService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val grantedUri = intent?.getParcelableExtra<Uri>("grantedUri")
        val audioData = intent?.getByteArrayExtra("audioData")

        if (grantedUri != null && audioData != null) {
            FileSavingUtils.fixBaseNameToSave(this, grantedUri, audioData, "quick_save")
        } else {
            Log.e("FileSavingService", "Failed to save file to $grantedUri, of data: $audioData")
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
        context: Context, myBufferService: MyBufferServiceInterface, data: ByteArray, uri: Uri?
    ): Boolean {
        var success = false
        uri?.let {
            try {
                val outputStream = context.contentResolver.openOutputStream(it)
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
                    Log.e(logTag, "Failed to open output stream for $uri")
                }
            } catch (e: IOException) {
                Log.e(logTag, "Failed to save file to $uri", e)
            } catch (e: SecurityException) {
                Log.e(logTag, "Permission denied to access $uri", e)
            }
        } ?: run {
            Log.e(logTag, "No URI received from directory chooser")
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

    public fun fileExists(context: Context, fileUri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(fileUri)?.use { true } ?: false
        } catch (e: FileNotFoundException) {
            false
        } catch (e: Exception) {
            // Handle other exceptions, e.g., SecurityException
            Log.e("fileExists", "Error checking file existence: ${e.message}", e)
            false
        }
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

        val fileUri = grantedDirectoryUri.buildUpon().appendPath(filename).build()
        if (fileExists(context, fileUri)) {
            runOnUiThread {
                Toast.makeText(
                    context,
                    "Somehow context filename and timestamp already existed, overwrote it: $filename",
                    Toast.LENGTH_SHORT
                ).show()
                Log.e(
                    logTag,
                    "Somehow context filename and timestamp already existed, overwrote it: $filename"
                )
            }
        }

        var success: Boolean = false

        fileUri?.let {
            success = saveFile(
                context, ViewModelHolder.getSharedViewModel().myBufferService!!, data, it
            )
        } ?: run {
            // Handle file creation failure
            runOnUiThread {
                Toast.makeText(
                    context, "Error creating file", Toast.LENGTH_SHORT
                ).show()
                Log.e(logTag, "Failed to create file: $filename")
            }
        }


        return success
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