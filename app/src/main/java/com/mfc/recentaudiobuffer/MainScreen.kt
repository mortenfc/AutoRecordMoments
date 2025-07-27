package com.mfc.recentaudiobuffer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerControlView

@UnstableApi
@Composable
fun MainScreen(
    signInButtonText: MutableState<String>,
    onSignInClick: () -> Unit,
    authError: String?,
    onDismissSignInErrorDialog: () -> Unit,
    isRecordingFromService: Boolean,
    onStartBufferingClick: () -> Unit,
    onStopBufferingClick: () -> Unit,
    onResetBufferClick: () -> Unit,
    onSaveBufferClick: () -> Unit,
    onPickAndPlayFileClick: () -> Unit,
    showRecentFilesDialog: Boolean,
    onFileSelected: (Uri) -> Unit,
    onDonateClick: () -> Unit,
    onSettingsClick: () -> Unit,
    showDirectoryPermissionDialog: Boolean,
    onDirectoryAlertDismiss: () -> Unit,
    onTrimFileClick: () -> Unit,
    showTrimFileDialog: Boolean,
    onTrimFileSelected: (Uri) -> Unit,
    mediaPlayerManager: MediaPlayerManager,
    isLoading: Boolean,
    showSaveDialog: Boolean,
    suggestedFileName: String,
    onConfirmSave: (fileName: String) -> Unit,
    onDismissSaveDialog: () -> Unit,
    isPreview: Boolean = false
) {
    var showPrivacyInfoDialog by remember { mutableStateOf(false) }

    // FIXED: Use animateColorAsState for color animations
    val recordingButtonBackgroundColor by animateColorAsState(
        targetValue = if (isRecordingFromService) colorResource(id = R.color.red_pause).copy(
            alpha = 1f, red = 0.65f, blue = 0.3f
        )
        else colorResource(id = R.color.green_start).copy(alpha = 1f, green = 0.95f),
        animationSpec = tween(durationMillis = 400, easing = EaseInOutCubic),
        label = "recordingButtonBackgroundColor"
    )

    // FIXED: Use animateColorAsState for color animations
    val recordingButtonElementsColor by animateColorAsState(
        targetValue = if (isRecordingFromService) Color.White else colorResource(id = R.color.teal_900),
        animationSpec = tween(durationMillis = 400, delayMillis = 50, easing = EaseInOutCubic),
        label = "recordingButtonElementsColor"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = stringResource(id = R.string.main),
            signInButtonText = signInButtonText,
            onSignInClick = onSignInClick,
            onSettingsClick = onSettingsClick,
            authError = authError,
            onDismissErrorDialog = onDismissSignInErrorDialog
        )
        if (!isPreview) {
            AdMobBanner()
        }

        Scaffold { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(colorResource(id = R.color.teal_100))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center)
                        .padding(bottom = 80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center
                    ) {
                        RecordingToggleButton(
                            isRecording = isRecordingFromService,
                            backgroundColor = recordingButtonBackgroundColor,
                            elementsColor = recordingButtonElementsColor,
                            onClick = {
                                if (isRecordingFromService) onStopBufferingClick() else onStartBufferingClick()
                            })
                        IconButton(
                            onClick = { showPrivacyInfoDialog = true },
                            modifier = Modifier.offset(x = 100.dp, y = -100.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Show privacy info",
                                tint = colorResource(id = R.color.purple_accent),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(48.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SecondaryActionButton(
                            text = stringResource(R.string.save_the_buffer_as_a_recording),
                            icon = R.drawable.baseline_save_alt_24,
                            onClick = onSaveBufferClick
                        )
                        SecondaryActionButton(
                            text = "Remove All Non-\nSpeech From File",
                            icon = R.drawable.outline_content_cut_24,
                            onClick = onTrimFileClick
                        )
                        SecondaryActionButton(
                            text = stringResource(R.string.play_a_recording),
                            icon = R.drawable.baseline_play_circle_outline_24,
                            onClick = onPickAndPlayFileClick
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    SecondaryActionButton(
                        text = stringResource(R.string.clear_the_buffer),
                        icon = R.drawable.baseline_delete_outline_24,
                        onClick = onResetBufferClick
                    )
                }

                DonateBanner(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    onClick = onDonateClick
                )

                if (!isPreview) {
                    PlayerControlViewContainer(
                        mediaPlayerManager = mediaPlayerManager,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                Column {
                    AnimatedVisibility(
                        visible = isLoading,
                        enter = fadeIn(animationSpec = tween(durationMillis = 300)),
                        exit = fadeOut(animationSpec = tween(durationMillis = 300))
                    ) {
                        LoadingIndicator()
                    }
                }
            }
        }
    }

    // --- Dialogs ---
    if (showRecentFilesDialog) {
        RecentFilesDialog(
            onDismiss = { onFileSelected(Uri.EMPTY) }, onFileSelected = onFileSelected
        )
    }

    if (showTrimFileDialog) {
        RecentFilesDialog(
            onDismiss = { onTrimFileSelected(Uri.EMPTY) }, onFileSelected = onTrimFileSelected
        )
    }

    if (showPrivacyInfoDialog) {
        PrivacyInfoDialog(onDismissRequest = { showPrivacyInfoDialog = false })
    }

    if (showDirectoryPermissionDialog) {
        DirectoryPickerDialog(onDismiss = onDirectoryAlertDismiss)
    }

    if (showSaveDialog) {
        FileSaveDialog(
            suggestedName = suggestedFileName,
            onDismiss = onDismissSaveDialog,
            onSave = onConfirmSave
        )
    }
}

@Composable
fun RecordingToggleButton(
    isRecording: Boolean, backgroundColor: Color, elementsColor: Color, onClick: () -> Unit
) {
    val buttonText = if (isRecording) "Pause\nRecording" else "Start\nRecording"
    val iconRes = if (isRecording) R.drawable.baseline_mic_off_24 else R.drawable.baseline_mic_24

    Button(
        onClick = onClick,
        modifier = Modifier
            .size(180.dp)
            .circularShadow(radius = 5.dp, offsetY = 5.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = backgroundColor),
        border = BorderStroke(2.dp, colorResource(id = R.color.purple_accent)),
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = buttonText,
                modifier = Modifier.size(64.dp),
                tint = elementsColor
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = buttonText,
                color = elementsColor,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
fun SecondaryActionButton(text: String, icon: Int, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .size(72.dp)
                .roundedRectShadow(shadowRadius = 4.dp, offsetY = 4.dp, cornerRadius = 24.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colorResource(id = R.color.teal_350),
                contentColor = colorResource(id = R.color.teal_900)
            ),
            border = BorderStroke(2.dp, colorResource(id = R.color.purple_accent)),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = colorResource(id = R.color.teal_900)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = text,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = colorResource(id = R.color.teal_900),
            textAlign = TextAlign.Center
        )
    }
}


@Composable
fun MainButton(
    text: String,
    icon: Int,
    onClick: () -> Unit,
    iconTint: Color = Color.White,
    modifier: Modifier = Modifier.fillMaxWidth(fraction = 0.7f),
    enabled: Boolean = true,
    contentPadding: Dp = 16.dp,
    iconSize: Dp = 22.dp,
    maxLines: Int = 1
) {
    // --- ADJUSTED OFFSETS ---
    // The button calculates its specific insets based on its internal padding.
    val horizontalInset = (16.dp - contentPadding) / 3.5f
    val verticalInset = (16.dp - contentPadding) / 1.9f

    Button(
        onClick = onClick,
        modifier = modifier
            .padding(bottom = 30.dp)
            .roundedRectShadow(
                shadowRadius = 5.dp,
                offsetY = 5.dp,
                cornerRadius = 8.dp, // This button has 8dp corners
                insetX = horizontalInset,
                insetY = verticalInset
            ),
        colors = ButtonDefaults.buttonColors(
            containerColor = colorResource(id = R.color.teal_350),
            contentColor = colorResource(id = R.color.teal_900),
            disabledContainerColor = colorResource(id = R.color.light_grey),
            disabledContentColor = colorResource(id = R.color.black)
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(contentPadding),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                colorResource(id = R.color.purple_accent)
            ), width = 2.dp
        ),
        enabled = enabled
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(id = icon),
                contentDescription = text,
                tint = iconTint,
                modifier = Modifier.size(iconSize)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (enabled) text else "Disabled",
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun LoadingIndicator() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {}), contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = colorResource(id = R.color.purple_accent))
    }
}

@Composable
fun DonateBanner(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.teal_350)),
        border = BorderStroke(2.dp, colorResource(id = R.color.purple_accent))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.dollar),
                contentDescription = null,
                tint = colorResource(id = R.color.gold),
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(id = R.string.donate_and_remove_ads),
                fontWeight = FontWeight.Bold,
                color = colorResource(id = R.color.teal_900),
                fontSize = 20.sp
            )
        }
    }
}

@UnstableApi
@Composable
fun PlayerControlViewContainer(
    mediaPlayerManager: MediaPlayerManager, modifier: Modifier = Modifier
) {
    var isVisible by remember { mutableStateOf(false) }
    var currentFileName by remember { mutableStateOf("") }
    var currentUri by remember { mutableStateOf<Uri?>(null) }

    DisposableEffect(mediaPlayerManager) {
        mediaPlayerManager.onPlayerReady = { uri, fileName ->
            isVisible = true
            currentUri = uri
            currentFileName = fileName
        }
        onDispose {
            mediaPlayerManager.closeMediaPlayer()
        }
    }


    AnimatedVisibility(
        visible = isVisible,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(durationMillis = 200)),
        exit = fadeOut(animationSpec = tween(durationMillis = 200))
    ) {
        key(currentUri) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.8f))
            ) {
                ConstraintLayout(modifier = Modifier.fillMaxWidth()) {
                    val (playerViewRef, fileNameRef) = createRefs()

                    AndroidView(modifier = Modifier.constrainAs(playerViewRef) {
                        top.linkTo(parent.top)
                        start.linkTo(parent.start)
                        end.linkTo(parent.end)
                        bottom.linkTo(parent.bottom)
                    }, factory = { context ->
                        PlayerControlView(context).apply {
                            player = mediaPlayerManager.player
                            showTimeoutMs = 0
                        }
                    }, update = { view ->
                        view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        view.player = mediaPlayerManager.player
                        view.show()
                    })
                    Text(
                        text = currentFileName,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.constrainAs(fileNameRef) {
                            start.linkTo(parent.start, margin = 56.dp)
                            end.linkTo(parent.end, margin = 56.dp)
                            bottom.linkTo(parent.bottom, margin = 16.dp)
                        })
                }
                IconButton(
                    onClick = {
                        mediaPlayerManager.closeMediaPlayer()
                        isVisible = false
                    }, modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.exo_icon_close),
                        contentDescription = stringResource(R.string.close_media_player),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MaterialTheme {
        MainScreen(
            signInButtonText = remember { mutableStateOf("Sign Out") },
            onSignInClick = {},
            authError = null,
            onDismissSignInErrorDialog = {},
            isRecordingFromService = true,
            onStartBufferingClick = {},
            onStopBufferingClick = {},
            onResetBufferClick = {},
            onSaveBufferClick = {},
            onPickAndPlayFileClick = {},
            showRecentFilesDialog = false,
            onFileSelected = {},
            onDonateClick = {},
            onSettingsClick = {},
            showDirectoryPermissionDialog = false,
            onDirectoryAlertDismiss = {},
            onTrimFileClick = {},
            showTrimFileDialog = false,
            onTrimFileSelected = {},
            mediaPlayerManager = MediaPlayerManager(LocalContext.current) { _, _ -> },
            isLoading = false,
            isPreview = true,
            showSaveDialog = true,
            suggestedFileName = "preview_file.wav",
            onConfirmSave = {},
            onDismissSaveDialog = {})
    }
}

@Preview(showBackground = true)
@Composable
fun MainButtonPreview() {
    MainButton(
        "jesper ROFLMFAO",
        icon = R.drawable.baseline_play_circle_outline_24,
        {},
        Color.White,
        Modifier.fillMaxWidth(0.7f),
        true
    )
}

@Preview(showBackground = true)
@Composable
fun ToggleButtonPreview() {
    val recordingButtonColor by animateColorAsState(
        targetValue = colorResource(id = R.color.red_pause).copy(red = 0.65f),
        label = "RecordingButtonColor"
    )
    RecordingToggleButton(
        isRecording = true,
        backgroundColor = recordingButtonColor,
        elementsColor = Color.White,
        onClick = {})
}

@Preview(showBackground = true)
@Composable
fun SecondaryButtonPreview() {
    SecondaryActionButton(
        text = stringResource(R.string.play_a_recording),
        icon = R.drawable.baseline_play_circle_outline_24,
        onClick = {})
}

@Preview(showBackground = true)
@Composable
fun DonateBannerPreview() {
    DonateBanner(
        modifier = Modifier.padding(16.dp), onClick = {})
}