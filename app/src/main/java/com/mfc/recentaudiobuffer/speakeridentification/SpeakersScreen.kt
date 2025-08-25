package com.mfc.recentaudiobuffer.speakeridentification

import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mfc.recentaudiobuffer.*
import com.mfc.recentaudiobuffer.R

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
        onScanRecordings = viewModel::scanRecordingsForUnknownSpeakers,
        onStopScanning = viewModel::stopScanning,
        onClearScanState = viewModel::clearScanState,
        onIdentifySpeaker = viewModel::addSpeaker,
        onRenameSpeaker = viewModel::renameSpeaker,
        onDeleteSpeaker = viewModel::deleteSpeaker,
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
    onScanRecordings: () -> Unit,
    onStopScanning: () -> Unit,
    onClearScanState: () -> Unit,
    onIdentifySpeaker: (String, UnknownSpeaker) -> Unit,
    onRenameSpeaker: (Speaker, String) -> Unit,
    onDeleteSpeaker: (Speaker) -> Unit,
    onNavigateBack: () -> Unit,
    isPreview: Boolean = true
) {
    var showRenameDialog by remember { mutableStateOf<Speaker?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<Speaker?>(null) }
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
            modifier = Modifier.padding(paddingValues).fillMaxSize(),
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
                    if (!isUserSignedIn) {
                        Text(
                            "Sign in to sync",
                            fontStyle = FontStyle.Italic,
                            color = colorResource(id = R.color.purple_accent)
                        )
                    }
                }
            }

            if (speakers.isEmpty()) {
                item {
                    Text(
                        "No speakers identified. Scan your recordings to find new speakers.",
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = colorResource(id = R.color.teal_700)
                    )
                }
            } else {
                items(speakers) { speaker ->
                    IdentifiedSpeakerCard(
                        speaker = speaker,
                        onRenameClick = { showRenameDialog = speaker },
                        onDeleteClick = { showDeleteConfirmDialog = speaker })
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = colorResource(id = R.color.purple_accent).copy(alpha = 0.5f))
                Spacer(Modifier.height(16.dp))
                Text(
                    "Identify New Speakers from Recordings",
                    style = MaterialTheme.typography.titleLarge,
                    color = colorResource(id = R.color.teal_900)
                )
            }

            item {
                when (val state = uiState) {
                    is SpeakerDiscoveryUiState.Idle -> {
                        Button(
                            onClick = onScanRecordings,
                            colors = ButtonDefaults.buttonColors(containerColor = colorResource(id = R.color.purple_accent))
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Scan Recordings")
                        }
                    }

                    is SpeakerDiscoveryUiState.Scanning -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LinearProgressIndicator(
                                progress = { state.progress }, modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Scanning file ${state.currentFile} of ${state.totalFiles}...",
                                color = colorResource(id = R.color.teal_900)
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = onStopScanning,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop Scanning")
                                Spacer(Modifier.width(8.dp))
                                Text("Stop")
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
                        TextButton(onClick = onScanRecordings) { Text("Rescan") }
                    }

                    is SpeakerDiscoveryUiState.Clustering -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
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
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Stopping scan ...",
                                color = colorResource(id = R.color.teal_900)
                            )
                        }
                    }
                }
            }

            if (uiState is SpeakerDiscoveryUiState.Success) {
                items(uiState.unknownSpeakers) { unknownSpeaker ->
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

    // --- Dialogs ---
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
    showNameDialogFor?.let { speakerToIdentify ->
        AddOrRenameSpeakerDialog(
            onDismiss = { showNameDialogFor = null }, onConfirm = { name ->
                onIdentifySpeaker(name, speakerToIdentify)
                identifiedSpeakers.add(speakerToIdentify.id)
                showNameDialogFor = null
            }, title = "Identify New Speaker"
        )
    }
}

@Composable
fun IdentifiedSpeakerCard(
    speaker: Speaker, onRenameClick: () -> Unit, onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.teal_150)),
        border = BorderStroke(1.dp, colorResource(id = R.color.purple_accent))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = speaker.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colorResource(id = R.color.teal_900)
            )
            Row {
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
        modifier = Modifier.fillMaxWidth()
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
                        tint = colorResource(id = R.color.purple_accent)
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
                    Text("Identify Speaker")
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
                onDismiss()
            }, enabled = isNameValid
        ) {
            Text("Save")
        }
    }, dismissButton = {
        TextButton(onClick = onDismiss) {
            Text("Cancel")
        }
    })
}

// --- Previews ---

@Preview(showBackground = true, name = "Idle State")
@Composable
fun SpeakersScreenIdlePreview() {
    val mockSpeakers = listOf(
        Speaker(id = "1", name = "Alice", embedding = floatArrayOf()),
        Speaker(id = "2", name = "Bob", embedding = floatArrayOf())
    )
    SpeakersScreenContent(
        speakers = mockSpeakers,
        uiState = SpeakerDiscoveryUiState.Idle,
        isUserSignedIn = false,
        onScanRecordings = {},
        onStopScanning = {},
        onClearScanState = {},
        onIdentifySpeaker = { _, _ -> },
        onRenameSpeaker = { _, _ -> },
        onDeleteSpeaker = {},
        onNavigateBack = {})
}

@Preview(showBackground = true, name = "Scanning State")
@Composable
fun SpeakersScreenScanningPreview() {
    SpeakersScreenContent(
        speakers = emptyList(),
        uiState = SpeakerDiscoveryUiState.Scanning(0.6f, 6, 10),
        isUserSignedIn = true,
        onScanRecordings = {},
        onStopScanning = {},
        onClearScanState = {},
        onIdentifySpeaker = { _, _ -> },
        onRenameSpeaker = { _, _ -> },
        onDeleteSpeaker = {},
        onNavigateBack = {})
}

@Preview(showBackground = true, name = "Success with Unknown Speakers")
@Composable
fun SpeakersScreenSuccessPreview() {
    val mockUnknown = listOf(
        UnknownSpeaker("unknown1", emptyList(), Uri.EMPTY),
        UnknownSpeaker("unknown2", emptyList(), Uri.EMPTY)
    )
    SpeakersScreenContent(
        speakers = emptyList(),
        uiState = SpeakerDiscoveryUiState.Success(mockUnknown),
        isUserSignedIn = true,
        onScanRecordings = {},
        onStopScanning = {},
        onClearScanState = {},
        onIdentifySpeaker = { _, _ -> },
        onRenameSpeaker = { _, _ -> },
        onDeleteSpeaker = {},
        onNavigateBack = {})
}

@Preview(showBackground = true, name = "Error State")
@Composable
fun SpeakersScreenErrorPreview() {
    SpeakersScreenContent(
        speakers = emptyList(),
        uiState = SpeakerDiscoveryUiState.Error("Failed to access recordings directory."),
        isUserSignedIn = true,
        onScanRecordings = {},
        onStopScanning = {},
        onClearScanState = {},
        onIdentifySpeaker = { _, _ -> },
        onRenameSpeaker = { _, _ -> },
        onDeleteSpeaker = {},
        onNavigateBack = {})
}
