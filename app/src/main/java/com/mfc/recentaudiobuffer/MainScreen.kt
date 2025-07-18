package com.mfc.recentaudiobuffer

import android.annotation.SuppressLint
import android.net.Uri
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.VectorConverter
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerControlView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MainScreen(
    signInButtonText: MutableState<String>,
    onSignInClick: () -> Unit,
    onStartBufferingClick: () -> Unit,
    onStopBufferingClick: () -> Unit,
    onResetBufferClick: () -> Unit,
    onSaveBufferClick: () -> Unit,
    onPickAndPlayFileClick: () -> Unit,
    showRecentFilesDialog: MutableState<Boolean>,
    onFileSelected: (Uri) -> Unit,
    onDonateClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDirectoryAlertDismiss: () -> Unit,
    onTrimFileClick: () -> Unit,
    showTrimFileDialog: MutableState<Boolean>,
    onTrimFileSelected: (Uri) -> Unit,
    mediaPlayerManager: MediaPlayerManager,
    isRecordingFromService: MutableState<Boolean>,
    isLoading: MutableState<Boolean>,
    isPreview: Boolean = false
) {
    // State to manage if recording is active, controlling the main button's appearance and action
    var isLoadingState by remember { isLoading }
    var isRecording by remember { isRecordingFromService }
    var isInitialComposition by remember { mutableStateOf(true) }
    var showPrivacyInfoDialog by remember { mutableStateOf(false) }

    // This effect triggers whenever 'isRecording' changes.
    LaunchedEffect(isRecording) {
        // Don't run this logic the very first time the screen is composed
        if (isInitialComposition) {
            isInitialComposition = false
            return@LaunchedEffect
        }

        // Force the work onto a dedicated background thread
        withContext(Dispatchers.IO) {
            if (isRecording) {
                onStartBufferingClick()
            } else {
                onStopBufferingClick()
            }
        }
    }

    // Define colors for the recording button states
    val recordingButtonBackgroundColor by animateValueAsState(
        targetValue = if (isRecording) colorResource(id = R.color.red_pause).copy(
            alpha = 1f, red = 0.65f, blue = 0.3f
        ) else colorResource(id = R.color.green_start).copy(
            alpha = 1f, green = 0.95f
        ),
        label = "recordingButtonBackgroundColor",
        animationSpec = tween(durationMillis = 400, easing = EaseInOutCubic),
        typeConverter = Color.VectorConverter(ColorSpaces.Oklab)
    )
    val recordingButtonElementsColor by animateValueAsState(
        targetValue = if (isRecording) Color.White else colorResource(id = R.color.teal_900),
        label = "recordingButtonElementsColor",
        animationSpec = tween(durationMillis = 400, delayMillis = 50, easing = EaseInOutCubic),
        typeConverter = Color.VectorConverter(ColorSpaces.Oklab)
    )

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = stringResource(id = R.string.main),
            signInButtonText = signInButtonText,
            onSignInClick = onSignInClick,
            onSettingsClick = onSettingsClick
        )
        AdMobBanner()

        Scaffold { innerPadding ->
            // --- REVISED LAYOUT ---
            // The Box now acts as a container for layers.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(colorResource(id = R.color.teal_100))
            ) {
                // LAYER 1: The main content is always present in the composition.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center)
                        .padding(bottom = 80.dp), // Pushed up for donate banner
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Large, central toggle button for starting/stopping recording
                    Box(
                        modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center
                    ) {
                        // Large, central toggle button for starting/stopping recording
                        RecordingToggleButton(
                            isRecording = isRecording,
                            backgroundColor = recordingButtonBackgroundColor,
                            elementsColor = recordingButtonElementsColor,
                            onClick = {
                                isRecording = !isRecording
                            })

                        // Info icon button, offset to the side of the main button
                        IconButton(
                            onClick = { showPrivacyInfoDialog = true },
                            // (90dp button radius + 24dp standard icon radius - ? = 100)
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

                    // Secondary actions in a two-column layout
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

                // Bottom banner for donations
                DonateBanner(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    onClick = onDonateClick
                )

                // The media player controller, which will overlay at the bottom when visible
                if (!isPreview) {
                    PlayerControlViewContainer(
                        mediaPlayerManager = mediaPlayerManager,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                Column {
                    AnimatedVisibility(
                        visible = isLoadingState,
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
    if (showRecentFilesDialog.value) {
        RecentFilesDialog(
            onDismiss = { showRecentFilesDialog.value = false }, onFileSelected = onFileSelected
        )
    }

    if (showTrimFileDialog.value) {
        RecentFilesDialog(
            onDismiss = { showTrimFileDialog.value = false },
            onFileSelected = onTrimFileSelected
        )
    }

    if (showPrivacyInfoDialog) {
        PrivacyInfoDialog(onDismissRequest = { showPrivacyInfoDialog = false })
    }

    if (FileSavingUtils.showSavingDialog) {
        val context = LocalContext.current
        FileSavingUtils.currentGrantedDirectoryUri?.let { grantedDirectoryUri ->
            FileSavingUtils.currentData?.let { data ->
                FileSaveDialog(suggestedName = FileSavingUtils.suggestedFileName, onDismiss = {
                    FileSavingUtils.showSavingDialog = false
                    isLoading.value = false
                }, onSave = { filename ->
                    FileSavingUtils.fixBaseNameToSave(
                        context, grantedDirectoryUri, data, filename
                    )
                    FileSavingUtils.showSavingDialog = false
                    isLoading.value = false
                })
            }
        }
    }

    if (FileSavingUtils.showDirectoryPermissionDialog) {
        DirectoryPickerDialog(onDismiss = onDirectoryAlertDismiss)
    }
}

/**
 * A dialog that explains how the audio buffering and data privacy works.
 */
@Composable
fun PrivacyInfoDialog(onDismissRequest: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = colorResource(id = R.color.teal_100),
        title = {
            Text(
                "Privacy Info",
                fontWeight = FontWeight.Bold,
                color = colorResource(id = R.color.teal_900)
            )
        },
        text = {
            Text(
                "This app continuously records audio to a temporary buffer in your phone's memory (RAM). " + "No audio data (except settings) is saved or sent to the cloud unless you explicitly press the 'Save' button. " + "Clearing the buffer or closing the persistent notification will discard the buffered audio.",
                // Apply a style to enable hyphenation
                style = MaterialTheme.typography.bodyMedium.copy(
                    textAlign = TextAlign.Justify,
                    hyphens = Hyphens.Auto,
                    lineBreak = LineBreak.Paragraph,
                ),
                color = colorResource(id = R.color.teal_900),
                lineHeight = 20.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(
                    "GOT IT",
                    fontWeight = FontWeight.Bold,
                    color = colorResource(id = R.color.purple_accent)
                )
            }
        })
}

/**
 * The main, large, round button to start and stop recording.
 * It toggles its appearance and action based on the recording state.
 */
@Composable
fun RecordingToggleButton(
    isRecording: Boolean, backgroundColor: Color, elementsColor: Color, onClick: () -> Unit
) {
    val buttonText =
        if (isRecording) stringResource(R.string.pause_buffering_in_the_background) else stringResource(
            R.string.start_buffering_in_the_background
        )
    val iconRes = if (isRecording) R.drawable.baseline_mic_off_24 else R.drawable.baseline_mic_24

    Button(
        onClick = onClick, modifier = Modifier
            .size(180.dp)
            .drawBehind {
                val shadowColor = Color.Black
                val transparentColor = Color.Transparent
                val shadowRadius = 12.dp.toPx()
                val offset = 4.dp.toPx()

                val paint = Paint().apply {
                    this.color = transparentColor
                    this.isAntiAlias = true
                    this.asFrameworkPaint().setShadowLayer(
                        shadowRadius, 0f, offset, shadowColor.toArgb()
                    )
                }
                drawIntoCanvas { canvas ->
                    canvas.drawCircle(
                        radius = size.width / 2, center = center, paint = paint
                    )
                }
            }, shape = CircleShape, colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor, contentColor = Color.White
        ), border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                colorResource(id = R.color.purple_accent)
            ), width = 2.dp
        ), contentPadding = PaddingValues(0.dp)
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
                text = buttonText.uppercase(),
                color = elementsColor,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

/**
 * A smaller action button for secondary tasks like save, play, and clear.
 */
@Composable
fun SecondaryActionButton(
    text: String, icon: Int, onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .size(72.dp)
                .drawBehind {
                    val shadowColor = Color.Black
                    val transparentColor = Color.Transparent
                    val shadowRadius = 6.dp.toPx()
                    val offset = 3.dp.toPx()

                    val paint = Paint().apply {
                        this.color = transparentColor
                        this.isAntiAlias = true
                        this.asFrameworkPaint().setShadowLayer(
                            shadowRadius, 0f, offset, shadowColor.toArgb()
                        )
                    }
                    drawIntoCanvas { canvas ->
                        canvas.drawRoundRect(
                            left = 0f,
                            top = offset,
                            right = size.width,
                            bottom = size.height,
                            radiusX = 24.dp.toPx(),
                            radiusY = 24.dp.toPx(),
                            paint = paint
                        )
                    }
                },
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colorResource(id = R.color.teal_350),
                contentColor = colorResource(id = R.color.teal_900)
            ),
            border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    colorResource(id = R.color.purple_accent)
                ), width = 2.dp
            ),
            contentPadding = PaddingValues(0.dp) // Use 0.dp to control content placement manually
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    painter = painterResource(id = icon),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = colorResource(id = R.color.teal_900)
                )
            }
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
    width: Dp = 180.dp,
    enabled: Boolean = true,
    contentPadding: Dp = 16.dp,
    iconSize: Dp = 22.dp,
    maxLines: Int = 1
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .width(width)
            .padding(bottom = 30.dp)
            .drawBehind {
                val shadowColor = Color.Black
                val transparentColor = Color.Transparent
                val shadowRadius = 6.dp.toPx()
                val offsetDown = 3.dp.toPx()

                val paint = Paint().apply {
                    this.color = transparentColor
                    this.isAntiAlias = true
                    this.asFrameworkPaint().setShadowLayer(
                        shadowRadius, // Half of height
                        0f, // No horizontal offset
                        offsetDown, // Vertical offset
                        shadowColor.toArgb()
                    )
                }

                // --- ADJUSTED OFFSETS ---
                // Use a smaller horizontal offset by dividing by a larger number
                val horizontalOffset = (16.dp - contentPadding).toPx() / 3.5f
                // Use a slightly adjusted vertical offset
                val verticalOffset = (16.dp - contentPadding).toPx() / 1.9f

                drawIntoCanvas { canvas ->
                    canvas.drawRoundRect(
                        left = horizontalOffset, // Apply smaller horizontal offset
                        top = offsetDown + verticalOffset,
                        right = size.width - horizontalOffset, // Apply smaller horizontal offset
                        bottom = size.height - verticalOffset,
                        radiusX = 4.dp.toPx(),
                        radiusY = 4.dp.toPx(),
                        paint = paint
                    )
                }
            },
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

/**
 * A banner at the bottom of the screen for the donation/ad-removal action.
 */
@Composable
fun DonateBanner(
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    Card(
        onClick = onClick, // The Card handles the click and ripple effect itself
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = colorResource(id = R.color.teal_350)
        ),
        border = BorderStroke(2.dp, colorResource(id = R.color.purple_accent))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)
            ) {
                // 1. The Background Icon (The Outline)
                // It fills the entire Box.
                Icon(
                    painter = painterResource(id = R.drawable.dollar),
                    contentDescription = null, // Description is on the text
                    tint = colorResource(id = R.color.purple_accent), // Outline color
                    modifier = Modifier.matchParentSize()
                )
                // 2. The Foreground Icon (The Fill)
                // Padding shrinks its drawing area, revealing the background
                // icon as a uniform border.
                Icon(
                    painter = painterResource(id = R.drawable.dollar),
                    contentDescription = null,
                    tint = colorResource(id = R.color.gold), // Fill color
                    modifier = Modifier
                        .matchParentSize()
                        .padding(all = 1.5.dp) // Adjust padding to control stroke width
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // The text implementation remains correct.
            Box {
                Text(
                    text = stringResource(id = R.string.donate_and_remove_ads),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = colorResource(id = R.color.purple_accent),
                    style = TextStyle(
                        drawStyle = Stroke(width = 4f, join = StrokeJoin.Round)
                    )
                )
                Text(
                    text = stringResource(id = R.string.donate_and_remove_ads),
                    fontWeight = FontWeight.Bold,
                    color = colorResource(id = R.color.gold),
                    fontSize = 20.sp
                )
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerControlViewContainer(
    mediaPlayerManager: MediaPlayerManager,
    modifier: Modifier = Modifier,
) {
    var isVisible by remember { mutableStateOf(false) }
    var currentFileName by remember { mutableStateOf("") }
    // This state variable is the key to forcing a reset.
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
        // The key will force this entire Box to be recreated when the URI changes.
        key(currentUri) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // This single background provides a consistent look and opacity.
                    .background(Color.Black.copy(alpha = 0.2f))
            ) {
                // ConstraintLayout allows us to overlay views with precision.
                ConstraintLayout(modifier = Modifier.fillMaxWidth()) {
                    val (playerViewRef, fileNameRef) = createRefs()

                    // The PlayerControlView fills the entire space.
                    AndroidView(modifier = Modifier.constrainAs(playerViewRef) {
                        top.linkTo(parent.top)
                        start.linkTo(parent.start)
                        end.linkTo(parent.end)
                        bottom.linkTo(parent.bottom)
                    }, factory = { context ->
                        PlayerControlView(context).apply {
                            player = mediaPlayerManager.player
                            showTimeoutMs = 0 // Keep controls always visible
                        }
                    }, update = { view ->
                        // Make the view's own background transparent.
                        view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        view.player = mediaPlayerManager.player
                        view.show()
                    })

                    // The filename is constrained to the bottom of the layout,
                    // appearing just above the progress bar.
                    Text(
                        text = currentFileName,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.constrainAs(fileNameRef) {
                            start.linkTo(parent.start, margin = 16.dp)
                            end.linkTo(parent.end, margin = 16.dp)
                            // This pins the text to the bottom of the container,
                            // just above the progress bar's default location.
                            bottom.linkTo(parent.bottom, margin = 16.dp)
                        })
                }

                // The close button remains aligned to the top-right corner of the Box.
                IconButton(
                    onClick = {
                        mediaPlayerManager.closeMediaPlayer()
                        isVisible = false
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp) // Visual padding from the corner
                        .size(40.dp)   // Sets a consistent touch target
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


// --- Previews ---
@SuppressLint("UnrememberedMutableState")
@Preview(showBackground = true, device = "id:pixel_6")
@Composable
fun MainScreenPreview() {
    MaterialTheme {
        MainScreen(
            signInButtonText = remember { mutableStateOf("Sign Out") },
            onStartBufferingClick = {},
            onStopBufferingClick = {},
            onResetBufferClick = {},
            onSaveBufferClick = {},
            onPickAndPlayFileClick = {},
            showRecentFilesDialog = mutableStateOf(true),
            onFileSelected = {},
            onDonateClick = {},
            onSettingsClick = {},
            onSignInClick = {},
            onDirectoryAlertDismiss = {},
            onTrimFileClick = {},
            showTrimFileDialog = mutableStateOf(true),
            onTrimFileSelected = {},
            mediaPlayerManager = MediaPlayerManager(LocalContext.current) { _, _ -> },
            isRecordingFromService = mutableStateOf(true),
            isLoading = mutableStateOf(true),
            isPreview = true
        )
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
        180.dp,
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