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

package com.mfc.recentaudiobuffer.speakeridentification

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.StopCircle
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
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
import androidx.core.net.toUri

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
        onExportDebugReport = viewModel::exportDebugReport,
        config = viewModel.config,
        onRescanWithCurrentFiles = viewModel::rescanWithCurrentFiles,
        isPreview = false
    )
}

@SuppressLint("ServiceCast")
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
    onExportDebugReport: () -> String,
    config: SpeakerClusteringConfig,
    onRescanWithCurrentFiles: () -> Unit,
    isPreview: Boolean = true
) {
    var showRenameDialog by remember { mutableStateOf<Speaker?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<Speaker?>(null) }
    var showDeleteAllConfirmDialog by remember { mutableStateOf(false) }
    var showTuningDialog by remember { mutableStateOf(false) }
    var showNameDialogFor by remember { mutableStateOf<UnknownSpeaker?>(null) }
    val identifiedSpeakers = remember { mutableStateListOf<String>() }

    var debugMode by remember { mutableStateOf(false) }
    val showDebugReportDialog = remember { mutableStateOf(false) }

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

    // Tuning Settings Dialog
    if (showTuningDialog) {
        ClusteringSettingsDialog(
            config = config,
            onDismiss = { showTuningDialog = false },
            onApply = {
                onRescanWithCurrentFiles()
                showTuningDialog = false
            })
    }

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

    if (showDebugReportDialog.value) {
        DebugReportDialog(onExportDebugReport, showDebugReportDialog, context)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Existing delete all button
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
                // Debug row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Debug mode toggle
                    IconButton(
                        onClick = { debugMode = !debugMode }, modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            if (debugMode) Icons.Outlined.CloseFullscreen else Icons.Filled.BugReport,
                            contentDescription = "Toggle Debug",
                            modifier = Modifier.size(20.dp),
                            tint = colorResource(id = R.color.green_debug)
                        )
                    }

                    // Tuning settings button (only show when debugging)
                    if (debugMode) {
                        IconButton(
                            onClick = { showTuningDialog = true }, modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = "Tuning Settings",
                                tint = colorResource(id = R.color.purple_accent),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Export debug report button
                        IconButton(
                            onClick = { showDebugReportDialog.value = true },
                            modifier = Modifier.size(36.dp),
                            enabled = uiState is SpeakerDiscoveryUiState.Success
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "Export Report",
                                tint = if (uiState is SpeakerDiscoveryUiState.Success) colorResource(
                                    id = R.color.purple_accent
                                )
                                else colorResource(id = R.color.teal_500),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Debug info banner when in debug mode
                if (debugMode) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = colorResource(id = R.color.purple_accent).copy(
                                alpha = 0.1f
                            )
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = colorResource(id = R.color.purple_accent),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Debug mode enabled. Tap speakers to see clustering details.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorResource(id = R.color.teal_900)
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
                            enabled = true,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colorResource(
                                    id = R.color.teal_350
                                )
                            ),
                            border = BorderStroke(
                                2.dp, colorResource(id = R.color.purple_accent)
                            )
                        ) {
                            Icon(
                                Icons.Default.ImageSearch,
                                contentDescription = null,
                                tint = colorResource(id = R.color.teal_900)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Scan Recordings", color = colorResource(id = R.color.teal_900)
                            )
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
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            GradientProgressBar(
                                Modifier.height(6.dp).fillMaxWidth(), progress = state.progress
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
                                    Icons.Outlined.StopCircle,
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
                            Text("Select files for scanning")
                        }

                        if (debugMode) {
                            Button(
                                onClick = { onRescanWithCurrentFiles() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colorResource(id = R.color.purple_accent)
                                )
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Re-scan current files")
                            }
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
                            onIdentifyClick = { showNameDialogFor = unknownSpeaker },
                            showDebugInfo = debugMode,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DebugReportDialogContent(
    debugReport: String, onCopy: (String) -> Unit, onDismiss: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.teal_350)) // Adjusted color for better preview
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Debug Report",
                style = MaterialTheme.typography.titleLarge,
                color = colorResource(id = R.color.teal_900), // Adjusted color
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))

            // Scrollable report content
            Box(
                modifier = Modifier.fillMaxWidth().height(400.dp).background(
                    color = colorResource(id = R.color.teal_100), // Adjusted color
                    shape = RoundedCornerShape(8.dp)
                ).border(
                    1.dp, colorResource(id = R.color.purple_accent), RoundedCornerShape(8.dp)
                ).padding(8.dp)
            ) {
                SelectionContainer {
                    Text(
                        debugReport,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        color = colorResource(id = R.color.teal_900),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { onCopy(debugReport) }, colors = appButtonColors()
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Copy")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onDismiss, colors = appButtonColors()
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun DebugReportDialog(
    onExportDebugReport: () -> String,
    showDebugReportDialog: MutableState<Boolean>,
    context: Context
) {
    Dialog(onDismissRequest = { showDebugReportDialog.value = false }) {
        DebugReportDialogContent(
            debugReport = onExportDebugReport(),
            onDismiss = { showDebugReportDialog.value = false },
            onCopy = { report ->
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Debug Report", report)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
            })
    }
}

/**
 * A custom, visually appealing progress bar with a gradient and rounded corners.
 *
 * @param modifier The modifier to be applied to the progress bar.
 * @param progress The progress value between 0.0f and 1.0f.
 * @param progressColorStart The starting color of the progress gradient.
 * @param progressColorEnd The ending color of the progress gradient.
 * @param trackColor The color of the background track.
 * @param strokeWidth The height of the progress bar.
 */
@Composable
fun GradientProgressBar(
    modifier: Modifier = Modifier,
    progress: Float,
    progressColorStart: Color = Color(0xFF6A11CB),
    progressColorEnd: Color = Color(0xFF2575FC),
    trackColor: Color = Color.LightGray.copy(alpha = 0.3f),
    strokeWidth: Dp = 12.dp
) {
    // Animate the progress value to provide a smooth transition.
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 1000, delayMillis = 0),
        label = "ProgressBarAnimation"
    )

    // A gradient brush for the progress indicator.
    val progressGradient = Brush.horizontalGradient(
        colors = listOf(progressColorStart, progressColorEnd)
    )

    Canvas(
        modifier = modifier.height(strokeWidth)
    ) {
        val cornerRadius = CornerRadius(size.height / 2, size.height / 2)

        // Draw the background track
        drawRoundRect(
            color = trackColor, topLeft = Offset.Zero, size = size, cornerRadius = cornerRadius
        )

        // Calculate the width of the progress indicator
        val progressWidth = size.width * animatedProgress.coerceIn(0f, 1f)

        // Draw the progress indicator with a gradient
        // We use clipRect to ensure the progress bar doesn't draw outside its bounds,
        // which is important for the rounding at the end.
        clipRect(right = progressWidth) {
            drawRoundRect(
                brush = progressGradient,
                topLeft = Offset.Zero,
                size = Size(width = size.width, height = size.height),
                cornerRadius = cornerRadius
            )
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
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
    onIdentifyClick: () -> Unit,
    showDebugInfo: Boolean = false // Add toggle for debug mode
) {
    Card(
        modifier = Modifier.fillMaxWidth()
            .shadow(elevation = 4.dp, shape = RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.teal_150)),
        border = BorderStroke(2.dp, colorResource(id = R.color.purple_accent))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Unknown Speaker Found",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorResource(id = R.color.teal_900)
                )

                // Show confidence/similarity score
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            unknownSpeaker.debugInfo.averageSimilarityToCentroid > 0.8f -> colorResource(
                                id = R.color.teal_700
                            ).copy(alpha = 0.2f)

                            unknownSpeaker.debugInfo.averageSimilarityToCentroid > 0.6f -> colorResource(
                                id = R.color.purple_accent
                            ).copy(alpha = 0.2f)

                            else -> colorResource(id = R.color.red).copy(alpha = 0.2f)
                        }
                    ), shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        "Confidence: ${(unknownSpeaker.debugInfo.averageSimilarityToCentroid * 100).toInt()}%",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = colorResource(id = R.color.teal_900)
                    )
                }
            }

            // Debug Information Section (collapsible)
            if (showDebugInfo) {
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = colorResource(id = R.color.teal_100).copy(alpha = 0.5f)
                    ), shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Debug Info - ID: ${unknownSpeaker.id}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = colorResource(id = R.color.teal_900)
                        )
                        Spacer(Modifier.height(4.dp))

                        DebugInfoRow("Clustering", unknownSpeaker.debugInfo.clusteringMethod)
                        DebugInfoRow(
                            "Cluster Size", "${unknownSpeaker.debugInfo.clusterSize} segments"
                        )
                        DebugInfoRow(
                            "Original Size",
                            "${unknownSpeaker.debugInfo.originalClusterSize} segments"
                        )
                        DebugInfoRow(
                            "Discarded", "${unknownSpeaker.debugInfo.discardedSegments} segments"
                        )
                        DebugInfoRow(
                            "Purity Score", "%.3f".format(unknownSpeaker.debugInfo.purityScore)
                        )
                        DebugInfoRow(
                            "Variance", "%.5f".format(unknownSpeaker.debugInfo.variance)
                        )

                        if (unknownSpeaker.debugInfo.mergeHistory.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Merge History:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = colorResource(id = R.color.teal_900)
                            )
                            unknownSpeaker.debugInfo.mergeHistory.forEach { merge ->
                                Text(
                                    "• $merge",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorResource(id = R.color.teal_700)
                                )
                            }
                        }

                        if (unknownSpeaker.debugInfo.filterReasons.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Filters Applied:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = colorResource(id = R.color.red)
                            )
                            unknownSpeaker.debugInfo.filterReasons.forEach { reason ->
                                Text(
                                    "• $reason",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorResource(id = R.color.red)
                                )
                            }
                        }
                    }
                }
            }

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

@Composable
fun DebugInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = colorResource(id = R.color.teal_700)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = colorResource(id = R.color.teal_900)
        )
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
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { allFiles.forEach { onToggleFile(it.uri) } },
                            enabled = !isLoading
                        ) {
                            Text(
                                "Toggle All", color = colorResource(id = R.color.teal_900)
                            )
                        }
                        Spacer(Modifier.weight(0.5f))

                        TextButton(
                            onClick = { unprocessedFiles.forEach { onToggleFile(it.uri) } },
                            enabled = !isLoading
                        ) {
                            Text("Toggle New", color = colorResource(id = R.color.teal_900))
                        }

                        Spacer(Modifier.weight(0.5f))

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
                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle, enabled = enabled)
            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = colorResource(id = R.color.purple_accent),
                uncheckedColor = colorResource(id = R.color.teal_700),
                checkmarkColor = colorResource(id = R.color.teal_100),
                disabledCheckedColor = Color.Unspecified,
                disabledUncheckedColor = Color.Unspecified,
                disabledIndeterminateColor = Color.Unspecified
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
        onExportDebugReport = { -> "" },
        onRescanWithCurrentFiles = {},
        config = SpeakerClusteringConfig(LocalContext.current),
        onResetProcessedFiles = {},
    )
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
        onExportDebugReport = { -> "" },
        onRescanWithCurrentFiles = {},
        config = SpeakerClusteringConfig(LocalContext.current),
        onResetProcessedFiles = {},
    )
}

@Preview(showBackground = true, name = "File Selection Dialog")
@Composable
fun FileSelectionDialogPreview() {
    val files = listOf(
        RecordingFile(
            "rec_01.wav", "file:///rec_01.wav".toUri(), 10.5f, System.currentTimeMillis()
        ), RecordingFile(
            "rec_02_very_long_name_to_see_how_it_truncates.wav",
            "file:///rec_02.wav".toUri(),
            2.1f,
            System.currentTimeMillis() - 86400000
        ), RecordingFile(
            "rec_03.wav", "file:///rec_03.wav".toUri(), 5.0f, System.currentTimeMillis() - 172800000
        )
    )
    FileSelectionDialog(
        fileSelectionState = SpeakerDiscoveryUiState.FileSelection(
            allFiles = files,
            selectedFileUris = setOf(files[0].uri),
            processedFileUris = setOf(files[2].uri.toString())
        ), onDismiss = {}, onConfirm = {}, onToggleFile = {}, onResetProcessedFiles = {})
}

@Preview(showBackground = true, name = "Debug Report Dialog")
@Composable
fun DebugReportDialogPreview() {
    DebugReportDialogContent(
        debugReport = """
                === SPEAKER DISCOVERY DEBUG REPORT ===
                Generated: Fri Sep 05 17:25:00 CEST 2025
                
                DBSCAN Primary:
                  eps: 0.65
                  minPts: 3
                  
                Quality Filters:
                  minClusterSize: 2
                  clusterPurityThreshold: 0.6
                  maxClusterVariance: 0.0025
                ========================================
                === DISCOVERED SPEAKERS ===
                
                Speaker: speaker_1
                  Confidence: 82%
                  Cluster Size: 45
                  Purity: 0.821
                  Variance: 0.00150
            """.trimIndent(), onCopy = {}, onDismiss = {})
}