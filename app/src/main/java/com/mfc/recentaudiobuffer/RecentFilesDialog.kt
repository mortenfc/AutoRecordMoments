package com.mfc.recentaudiobuffer

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

// A simple data class to hold our file info
data class AudioFile(
    val uri: Uri,
    val name: String,
    val dateModified: Long
)

@Composable
fun RecentFilesDialog(
    onDismiss: () -> Unit,
    onFileSelected: (Uri) -> Unit
) {
    val context = LocalContext.current
    var audioFiles by remember { mutableStateOf<List<AudioFile>>(emptyList()) }
    // --- NEW: State to track loading ---
    var isLoading by remember { mutableStateOf(true) }

    // This effect runs once when the dialog is shown to fetch the files
    LaunchedEffect(Unit) {
        // Run the file query on a background thread
        val files = withContext(Dispatchers.IO) {
            val grantedUri = FileSavingUtils.getCachedGrantedUri(context)
            queryRecentAudio(context, grantedUri)
        }
        audioFiles = files
        // --- Set loading to false after the query is complete ---
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // --- STYLING: Apply app's color scheme ---
        containerColor = colorResource(id = R.color.teal_100),
        title = {
            Text(
                "Recent Recordings",
                fontWeight = FontWeight.Bold,
                color = colorResource(id = R.color.teal_900)
            )
        },
        text = {
            // --- UI LOGIC: Show loading indicator or list ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 400.dp), // Give the box a defined size
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = colorResource(id = R.color.purple_accent))
                } else if (audioFiles.isEmpty()) {
                    Text("No audio files found in the selected directory.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(audioFiles) { file ->
                            FileListItem(file = file, onClick = { onFileSelected(file.uri) })
                            HorizontalDivider(color = colorResource(id = R.color.teal_350))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "CANCEL",
                    fontWeight = FontWeight.Bold,
                    color = colorResource(id = R.color.purple_accent)
                )
            }
        }
    )
}

@Composable
private fun FileListItem(file: AudioFile, onClick: () -> Unit) {
    val date = remember {
        SimpleDateFormat("MMM dd, yyyy, hh:mm a", Locale.getDefault()).format(Date(file.dateModified))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp)
    ) {
        Text(
            text = file.name,
            fontWeight = FontWeight.Medium,
            color = colorResource(id = R.color.teal_900) // Themed text
        )
        Text(
            text = date,
            style = MaterialTheme.typography.bodySmall,
            color = colorResource(id = R.color.teal_700) // Themed secondary text
        )
    }
}

// It's good practice to run file system access off the main thread
private suspend fun queryRecentAudio(context: Context, directoryUri: Uri?): List<AudioFile> = withContext(Dispatchers.IO) {
    if (directoryUri == null) {
        return@withContext emptyList()
    }

    val directory = DocumentFile.fromTreeUri(context, directoryUri)
        ?: return@withContext emptyList()

    directory.listFiles()
        .filter { it.isFile && it.name?.endsWith(".wav", ignoreCase = true) == true }
        .map { file ->
            AudioFile(
                uri = file.uri,
                name = file.name ?: "Unknown",
                dateModified = file.lastModified()
            )
        }
        .sortedByDescending { it.dateModified }
}
