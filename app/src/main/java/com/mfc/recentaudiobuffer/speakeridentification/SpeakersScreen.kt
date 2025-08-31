package com.mfc.recentaudiobuffer.speakeridentification

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.DefaultStrokeLineCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mfc.recentaudiobuffer.AuthViewModel
import com.mfc.recentaudiobuffer.MediaPlayerManager
import com.mfc.recentaudiobuffer.R
import com.mfc.recentaudiobuffer.TopAppBar
import com.mfc.recentaudiobuffer.TopAppBarContent
import com.mfc.recentaudiobuffer.appButtonColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SpeakersScreen(
    viewModel: SpeakersViewModel,
    onNavigateBack: () -> Unit,
    authViewModel: AuthViewModel = hiltViewModel()
) {
    val speakers by viewModel.speakers.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val signInButtonText by authViewModel.signInButtonText
    val isUserSignedIn = signInButtonText == "Sign Out"

    SpeakersScreenContent(
        speakers = speakers,
        uiState = uiState,
        isUserSignedIn = isUserSignedIn,
        onPrepareFileSelection = viewModel::prepareFileSelection,
        onToggleFileSelection = viewModel::toggleFileSelection,
        onStartScan = viewModel::startScan,
        onStopScanning = viewModel::stopScanning,
        onClearScanState = viewModel::clearScanState,
        onIdentifySpeaker = viewModel::addSpeaker,
        onRenameSpeaker = viewModel::renameSpeaker,
        onDeleteSpeaker = viewModel::deleteSpeaker,
        onDeleteAllSpeakers = viewModel::deleteAllSpeakers,
        onResetProcessedFiles = viewModel::resetProcessedFiles,
        onNavigateBack = onNavigateBack,
        isPreview = false
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeakersScreenContent(
    speakers: List<Speaker>,
    uiState: SpeakerDiscoveryUiState,
    isUserSignedIn: Boolean,
    onPrepareFileSelection: () -> Unit,
    onToggleFileSelection: (Uri) -> Unit,
    onStartScan: (Set<Uri>) -> Unit,
    onStopScanning: () -> Unit,
    onClearScanState: () -> Unit,
    onIdentifySpeaker: (String, UnknownSpeaker) -> Unit,
    onRenameSpeaker: (Speaker, String) -> Unit,
    onDeleteSpeaker: (Speaker) -> Unit,
    onDeleteAllSpeakers: () -> Unit,
    onResetProcessedFiles: () -> Unit,
    onNavigateBack: () -> Unit,
    isPreview: Boolean = true
) {
    var showRenameDialog by remember { mutableStateOf<Speaker?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<Speaker?>(null) }
    var showDeleteAllConfirmDialog by remember { mutableStateOf(false) }
    var showNameDialogFor by remember { mutableStateOf<UnknownSpeaker?>(null) }
    val identifiedSpeakers = remember { mutableStateListOf<String>() }

    val context = LocalContext.current
    var currentlyPlayingSpeakerId by remember { mutableStateOf<String?>(null) }
    val mediaPlayerManager = remember {
        MediaPlayerManager(context) { _, _ ->
        }.apply {
            player?.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                        currentlyPlayingSpeakerId = null
                    }
                }
            })
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayerManager.closeMediaPlayer()
        }
    }

    // --- Dialogs ---
    if (uiState is SpeakerDiscoveryUiState.FileSelection) {
        FileSelectionDialog(
            fileSelectionState = uiState,
            onDismiss = onClearScanState,
            onConfirm = { onStartScan(uiState.selectedFileUris) },
            onToggleFile = onToggleFileSelection,
            onResetProcessedFiles = onResetProcessedFiles
        )
    }

    showRenameDialog?.let { speaker ->
        AddOrRenameSpeakerDialog(
            onDismiss = { showRenameDialog = null },
            onConfirm = { newName -> onRenameSpeaker(speaker, newName) },
            title = "Rename Speaker",
            initialName = speaker.name
        )
    }

    showDeleteConfirmDialog?.let { speaker ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text("Delete Speaker") },
            text = { Text("Are you sure you want to delete '${speaker.name}'? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSpeaker(speaker)
                        showDeleteConfirmDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colorResource(id = R.color.red))
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text("Cancel")
                }
            })
    }

    if (showDeleteAllConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirmDialog = false },
            title = { Text("Delete All Speakers") },
            text = { Text("Are you sure you want to delete all identified speakers? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteAllSpeakers()
                        showDeleteAllConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colorResource(id = R.color.red))
                ) { Text("Delete All") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirmDialog = false }) {
                    Text("Cancel")
                }
            })
    }

    showNameDialogFor?.let { speakerToIdentify ->
        AddOrRenameSpeakerDialog(
            onDismiss = { showNameDialogFor = null }, onConfirm = { name ->
                onIdentifySpeaker(name, speakerToIdentify)
                identifiedSpeakers.add(speakerToIdentify.id)
                showNameDialogFor = null
            }, title = "Identify New Speaker"
        )
    }


    Scaffold(
        containerColor = colorResource(id = R.color.teal_100),
        topBar = {
            if (isPreview) TopAppBarContent(
                title = "Manage Speakers",
                onBackButtonClicked = { onNavigateBack() },
                // Dummy values for preview-ability, the real TopAppBar will supply real ones
                signInButtonText = if (isUserSignedIn) "Sign Out" else "Sign In",
                isSigningIn = false,
                authError = null,
                onSignInClick = {},
                onDismissErrorDialog = {},
                onIconClick = { onNavigateBack() },
                onSettingsClick = null
            )
            else {
                TopAppBar(
                    title = "Manage Speakers",
                    onBackButtonClicked = onNavigateBack,
                )
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Identified Speakers",
                        style = MaterialTheme.typography.titleLarge,
                        color = colorResource(id = R.color.teal_900)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (speakers.isNotEmpty()) {
                            IconButton(onClick = { showDeleteAllConfirmDialog = true }) {
                                Icon(
                                    Icons.Default.DeleteSweep,
                                    contentDescription = "Delete All Speakers",
                                    tint = colorResource(id = R.color.red)
                                )
                            }
                        }
                        if (!isUserSignedIn) {
                            Text(
                                "Sign in to sync",
                                fontStyle = FontStyle.Italic,
                                color = colorResource(id = R.color.purple_accent)
                            )
                        }
                    }
                }
            }

            if (speakers.isEmpty()) {
                item {
                    Text(
                        "No speakers identified. Scan your recordings to find new speakers to optionally auto-save conversations of.",
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = colorResource(id = R.color.teal_700)
                    )
                }
            } else {
                items(speakers, key = { it.id }) { speaker ->
                    IdentifiedSpeakerCard(
                        speaker = speaker,
                        isPlaying = currentlyPlayingSpeakerId == speaker.id,
                        onPlayClick = {
                            if (currentlyPlayingSpeakerId == speaker.id) {
                                mediaPlayerManager.closeMediaPlayer()
                                currentlyPlayingSpeakerId = null
                            } else {
                                speaker.sampleUri?.let { uri ->
                                    mediaPlayerManager.setUpMediaPlayer(uri)
                                    currentlyPlayingSpeakerId = speaker.id
                                }
                            }
                        },
                        onRenameClick = { showRenameDialog = speaker },
                        onDeleteClick = { showDeleteConfirmDialog = speaker })
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = colorResource(id = R.color.purple_accent).copy(alpha = 0.5f))
                Spacer(Modifier.height(16.dp))
                Text(
                    "Identify New Speakers",
                    style = MaterialTheme.typography.titleLarge,
                    color = colorResource(id = R.color.teal_900)
                )
            }

            item {
                when (val state = uiState) {
                    is SpeakerDiscoveryUiState.Idle, is SpeakerDiscoveryUiState.FileSelection -> {
                        Button(
                            onClick = onPrepareFileSelection,
                            enabled = uiState !is SpeakerDiscoveryUiState.LoadingFiles,
                            colors = ButtonDefaults.buttonColors(containerColor = colorResource(id = R.color.teal_350)),
                            border = BorderStroke(2.dp, colorResource(id = R.color.purple_accent))
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = colorResource(id = R.color.teal_900)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Scan Recordings", color = colorResource(id = R.color.teal_900))
                        }
                    }

                    is SpeakerDiscoveryUiState.LoadingFiles -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = colorResource(id = R.color.purple_accent))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Loading recordings...",
                                color = colorResource(id = R.color.teal_900)
                            )
                        }
                    }

                    is SpeakerDiscoveryUiState.Scanning -> {
                        val animatedProgress by animateFloatAsState(
                            targetValue = state.progress,
                            animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                            label = "Scan Progress"
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .height(6.dp)
                                    .fillMaxWidth(fraction = 0.9f),
                                progress = { animatedProgress },
                                color = colorResource(id = R.color.purple_accent),
                                trackColor = colorResource(id = R.color.purple_accent).copy(alpha = 0.25f),
                                strokeCap = DefaultStrokeLineCap,
                                gapSize = 4.dp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Scanning file ${state.currentFile} of ${state.totalFiles}...",
                                color = colorResource(id = R.color.teal_900)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Stop earlier to only process the currently scanned files",
                                fontStyle = FontStyle.Italic,
                                fontSize = 12.sp,
                                color = colorResource(id = R.color.teal_900)
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = onStopScanning,
                                colors = appButtonColors(),
                                border = BorderStroke(
                                    width = 2.dp, color = colorResource(id = R.color.purple_accent)
                                )
                            ) {
                                Icon(
                                    painterResource(id = R.drawable.stop_circle_24dp),
                                    contentDescription = "Stop Scanning",
                                    tint = colorResource(id = R.color.red_pause),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Stop", color = colorResource(id = R.color.teal_900))
                            }
                        }
                    }

                    is SpeakerDiscoveryUiState.Error -> {
                        Text(state.message, color = colorResource(id = R.color.red))
                        TextButton(onClick = onClearScanState) { Text("Try Again") }
                    }

                    is SpeakerDiscoveryUiState.Success -> {
                        if (state.unknownSpeakers.isEmpty()) {
                            Text(
                                "Scan complete. No new speakers were found.",
                                color = colorResource(id = R.color.teal_900)
                            )
                        } else {
                            Text(
                                "Found ${state.unknownSpeakers.size} potential new speaker(s).",
                                style = MaterialTheme.typography.bodyLarge,
                                color = colorResource(id = R.color.teal_900)
                            )
                        }
                        Button(
                            onClick = onPrepareFileSelection,
                            colors = appButtonColors(),
                            border = BorderStroke(
                                2.dp, color = colorResource(id = R.color.purple_accent)
                            )
                        ) {
                            Text("Scan Again")
                        }
                    }

                    is SpeakerDiscoveryUiState.Clustering -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = colorResource(id = R.color.purple_accent))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Grouping speakers ...",
                                color = colorResource(id = R.color.teal_900)
                            )
                        }
                    }

                    is SpeakerDiscoveryUiState.Stopping -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = colorResource(id = R.color.purple_accent))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Stopping scan ...", color = colorResource(id = R.color.teal_900)
                            )
                        }
                    }
                }
            }

            if (uiState is SpeakerDiscoveryUiState.Success) {
                items(uiState.unknownSpeakers, key = { it.id }) { unknownSpeaker ->
                    val isIdentified = identifiedSpeakers.contains(unknownSpeaker.id)
                    if (!isIdentified) {
                        UnknownSpeakerCard(
                            unknownSpeaker = unknownSpeaker,
                            isPlaying = currentlyPlayingSpeakerId == unknownSpeaker.id,
                            onPlayClick = {
                                if (currentlyPlayingSpeakerId == unknownSpeaker.id) {
                                    mediaPlayerManager.closeMediaPlayer()
                                    currentlyPlayingSpeakerId = null
                                } else {
                                    unknownSpeaker.sampleUri?.let { uri ->
                                        mediaPlayerManager.setUpMediaPlayer(uri)
                                        currentlyPlayingSpeakerId = unknownSpeaker.id
                                    }
                                }
                            },
                            onIdentifyClick = { showNameDialogFor = unknownSpeaker })
                    }
                }
            }
        }
    }
}

@Composable
fun IdentifiedSpeakerCard(
    speaker: Speaker,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.teal_150)),
        border = BorderStroke(1.dp, colorResource(id = R.color.purple_accent))
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = speaker.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colorResource(id = R.color.teal_900),
                modifier = Modifier.weight(1f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPlayClick, enabled = speaker.sampleUri != null) {
                    Icon(
                        if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = "Play sample for ${speaker.name}",
                        tint = if (speaker.sampleUri != null) colorResource(id = R.color.teal_900) else MaterialTheme.colorScheme.onSurface.copy(
                            alpha = 0.38f
                        )
                    )
                }
                IconButton(onClick = onRenameClick) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Rename ${speaker.name}",
                        tint = colorResource(id = R.color.teal_900)
                    )
                }
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete ${speaker.name}",
                        tint = colorResource(id = R.color.red)
                    )
                }
            }
        }
    }
}

@Composable
fun UnknownSpeakerCard(
    unknownSpeaker: UnknownSpeaker,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    onIdentifyClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 4.dp, shape = RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.teal_150)),
        border = BorderStroke(2.dp, colorResource(id = R.color.purple_accent))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Unknown Speaker Found",
                style = MaterialTheme.typography.titleMedium,
                color = colorResource(id = R.color.teal_900)
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = onPlayClick,
                    enabled = unknownSpeaker.sampleUri != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorResource(id = R.color.teal_350)
                    ),
                    border = BorderStroke(1.dp, colorResource(id = R.color.purple_accent))
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = "Play Sample",
                        tint = colorResource(id = R.color.teal_900)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isPlaying) "Stop" else "Play Sample",
                        color = colorResource(id = R.color.teal_900)
                    )
                }

                Button(
                    onClick = onIdentifyClick, colors = ButtonDefaults.buttonColors(
                        containerColor = colorResource(id = R.color.purple_accent)
                    )
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = "Identify")
                    Spacer(Modifier.width(8.dp))
                    Text("Identify")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddOrRenameSpeakerDialog(
    onDismiss: () -> Unit, onConfirm: (String) -> Unit, title: String, initialName: String = ""
) {
    var name by remember { mutableStateOf(initialName) }
    val isNameValid = name.isNotBlank()

    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Speaker's Name") },
            singleLine = true,
            isError = !isNameValid
        )
    }, confirmButton = {
        Button(
            onClick = {
                onConfirm(name)
            }, enabled = isNameValid, colors = ButtonDefaults.buttonColors(
                containerColor = colorResource(id = R.color.purple_accent)
            )
        ) {
            Text("Save")
        }
    }, dismissButton = {
        TextButton(onClick = onDismiss) {
            Text("Cancel")
        }
    })
}

@Composable
fun FileSelectionDialog(
    fileSelectionState: SpeakerDiscoveryUiState.FileSelection,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onToggleFile: (Uri) -> Unit,
    onResetProcessedFiles: () -> Unit
) {
    val (allFiles, selectedUris, processedUris, isLoading) = fileSelectionState
    val allSelected = selectedUris.size == allFiles.size && allFiles.isNotEmpty()
    val unprocessedFiles = allFiles.filter { !processedUris.contains(it.uri.toString()) }

    Dialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.teal_100))
        ) {
            Box(
                modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center
            ) {
                Column {
                    Text(
                        "Select Recordings to Scan",
                        style = MaterialTheme.typography.titleLarge,
                        color = colorResource(id = R.color.teal_900)
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { allFiles.forEach { onToggleFile(it.uri) } },
                            enabled = !isLoading
                        ) {
                            Text(
                                if (allSelected) "Deselect All" else "Select All",
                                color = colorResource(id = R.color.teal_900)
                            )
                        }
                        TextButton(
                            onClick = { unprocessedFiles.forEach { onToggleFile(it.uri) } },
                            enabled = !isLoading
                        ) {
                            Text("Select New", color = colorResource(id = R.color.teal_900))
                        }
                        TextButton(onClick = onResetProcessedFiles, enabled = !isLoading) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Reset",
                                modifier = Modifier.size(18.dp),
                                tint = colorResource(id = R.color.teal_900)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Reset", color = colorResource(id = R.color.teal_900))
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                    ) {
                        items(allFiles, key = { it.uri }) { file ->
                            FileRow(
                                file = file,
                                isSelected = selectedUris.contains(file.uri),
                                isProcessed = processedUris.contains(file.uri.toString()),
                                onToggle = { onToggleFile(file.uri) },
                                enabled = !isLoading
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss, enabled = !isLoading) {
                            Text(
                                "Cancel", color = colorResource(id = R.color.teal_900)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onConfirm,
                            enabled = selectedUris.isNotEmpty() && !isLoading,
                            colors = appButtonColors(),
                            border = BorderStroke(
                                width = 2.dp, color = colorResource(id = R.color.purple_accent)
                            )
                        ) {
                            Text("Scan ${selectedUris.size} Files")
                        }
                    }
                }
                if (isLoading) {
                    CircularProgressIndicator(color = colorResource(id = R.color.purple_accent))
                }
            }
        }
    }
}

@Composable
fun FileRow(
    file: RecordingFile,
    isSelected: Boolean,
    isProcessed: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean
) {
    val dateFormatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle, enabled = enabled)
            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = colorResource(id = R.color.purple_accent),
                uncheckedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                color = colorResource(id = R.color.teal_900)
            )
            Text(
                text = "${"%.2f".format(file.sizeMb)} MB • ${dateFormatter.format(Date(file.lastModified))}",
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(id = R.color.teal_500)
            )
        }
        if (isProcessed) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Already processed",
                tint = colorResource(id = R.color.teal_700),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}


// --- Previews ---

@Preview(showBackground = true, name = "Idle State")
@Composable
fun SpeakersScreenIdlePreview() {
    val mockSpeakers = listOf(
        Speaker(id = "1", name = "Alice", embedding = floatArrayOf(), sampleUri = Uri.EMPTY),
        Speaker(id = "2", name = "Bob", embedding = floatArrayOf())
    )
    SpeakersScreenContent(
        speakers = mockSpeakers,
        uiState = SpeakerDiscoveryUiState.Idle,
        isUserSignedIn = false,
        onPrepareFileSelection = {},
        onStartScan = {},
        onStopScanning = {},
        onClearScanState = {},
        onIdentifySpeaker = { _, _ -> },
        onRenameSpeaker = { _, _ -> },
        onDeleteSpeaker = {},
        onDeleteAllSpeakers = {},
        onNavigateBack = {},
        onToggleFileSelection = {},
        onResetProcessedFiles = {})
}

@Preview(showBackground = true, name = "Scanning State")
@Composable
fun SpeakersScreenScanningPreview() {
    SpeakersScreenContent(
        speakers = emptyList(),
        uiState = SpeakerDiscoveryUiState.Scanning(0.6f, 6, 10),
        isUserSignedIn = true,
        onPrepareFileSelection = {},
        onStartScan = {},
        onStopScanning = {},
        onClearScanState = {},
        onIdentifySpeaker = { _, _ -> },
        onRenameSpeaker = { _, _ -> },
        onDeleteSpeaker = {},
        onDeleteAllSpeakers = {},
        onNavigateBack = {},
        onToggleFileSelection = {},
        onResetProcessedFiles = {})
}

@Preview(showBackground = true, name = "File Selection Dialog")
@Composable
fun FileSelectionDialogPreview() {
    val files = listOf(
        RecordingFile(
            "rec_01.wav", Uri.parse("file:///rec_01.wav"), 10.5f, System.currentTimeMillis()
        ), RecordingFile(
            "rec_02_very_long_name_to_see_how_it_truncates.wav",
            Uri.parse("file:///rec_02.wav"),
            2.1f,
            System.currentTimeMillis() - 86400000
        ), RecordingFile(
            "rec_03.wav",
            Uri.parse("file:///rec_03.wav"),
            5.0f,
            System.currentTimeMillis() - 172800000
        )
    )
    FileSelectionDialog(
        fileSelectionState = SpeakerDiscoveryUiState.FileSelection(
            allFiles = files,
            selectedFileUris = setOf(files[0].uri),
            processedFileUris = setOf(files[2].uri.toString())
        ), onDismiss = {}, onConfirm = {}, onToggleFile = {}, onResetProcessedFiles = {})
}
