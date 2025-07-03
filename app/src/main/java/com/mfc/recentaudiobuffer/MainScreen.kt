package com.mfc.recentaudiobuffer

import MediaPlayerManager
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.compose.animation.VectorConverter
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutSine
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Surface
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
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
    onDonateClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDirectoryAlertDismiss: () -> Unit,
    mediaPlayerManager: MediaPlayerManager,
    isPreview: Boolean = false
) {
    // State to manage if recording is active, controlling the main button's appearance and action
    var isRecording by remember { mutableStateOf(false) }
    var isInitialComposition by remember { mutableStateOf(true) }

    // This effect triggers whenever 'isRecording' changes.
    LaunchedEffect(isRecording) {
        // Don't run this logic the very first time the screen is composed
        if (isInitialComposition) {
            isInitialComposition = false
            return@LaunchedEffect
        }

        // ✅ Force the work onto a dedicated background thread
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
            alpha = 1f,
            red = 0.65f,
            blue = 0.3f
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(colorResource(id = R.color.teal_100))
            ) {
                // Main content area
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center)
                        .padding(bottom = 80.dp), // Pushed up to make space for the donate banner
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Large, central toggle button for starting/stopping recording
                    RecordingToggleButton(
                        isRecording = isRecording,
                        backgroundColor = recordingButtonBackgroundColor,
                        elementsColor = recordingButtonElementsColor,
                        onClick = {
                            isRecording = !isRecording
                        }
                    )

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
                    PlayerControlViewContainer(mediaPlayerManager = mediaPlayerManager)
                }
            }
        }
    }

    // --- Dialogs ---
    if (FileSavingUtils.showSavingDialog) {
        val context = LocalContext.current
        FileSavingUtils.currentGrantedDirectoryUri?.let { grantedDirectoryUri ->
            FileSavingUtils.currentData?.let { data ->
                FileSaveDialog(
                    onDismiss = { FileSavingUtils.showSavingDialog = false },
                    onSave = { filename ->
                        FileSavingUtils.fixBaseNameToSave(
                            context, grantedDirectoryUri, data, filename
                        )
                        FileSavingUtils.showSavingDialog = false
                    })
            }
        }
    }

    if (FileSavingUtils.showDirectoryPermissionDialog) {
        DirectoryPickerDialog(onDismiss = onDirectoryAlertDismiss)
    }
}

/**
 * The main, large, round button to start and stop recording.
 * It toggles its appearance and action based on the recording state.
 */
@Composable
fun RecordingToggleButton(
    isRecording: Boolean,
    backgroundColor: Color,
    elementsColor: Color,
    onClick: () -> Unit
) {
    val buttonText =
        if (isRecording) stringResource(R.string.pause_buffering_in_the_background) else stringResource(
            R.string.start_buffering_in_the_background
        )
    val iconRes = if (isRecording) R.drawable.baseline_mic_off_24 else R.drawable.baseline_mic_24

    Button(
        onClick = onClick,
        modifier = Modifier
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
                        shadowRadius,
                        0f,
                        offset,
                        shadowColor.toArgb()
                    )
                }
                drawIntoCanvas { canvas ->
                    canvas.drawCircle(
                        radius = size.width / 2,
                        center = center,
                        paint = paint
                    )
                }
            },
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = Color.White
        ),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                colorResource(id = R.color.purple_accent)
            ), width = 2.dp
        ),
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
    text: String,
    icon: Int,
    onClick: () -> Unit
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
                            shadowRadius,
                            0f,
                            offset,
                            shadowColor.toArgb()
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
    contentPadding: Dp = 16.dp
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
                val offset = 3.dp.toPx() // Offset downwards

                val paint = Paint().apply {
                    this.color = transparentColor
                    this.isAntiAlias = true
                    this.asFrameworkPaint().setShadowLayer(
                        shadowRadius, // Half of height
                        0f, // No horizontal offset
                        offset, // Vertical offset
                        shadowColor.toArgb()
                    )
                }
                drawIntoCanvas { canvas ->
                    canvas.drawRoundRect(
                        left = 0f,
                        top = offset, // Anchor is 0
                        right = size.width,
                        bottom = size.height, // Draw to the bottom of the button
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
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (enabled) text else "Disabled",
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * A banner at the bottom of the screen for the donation/ad-removal action.
 */
@Composable
fun DonateBanner(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
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
            // ✅ This is the correct and robust way to create an even icon outline.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(28.dp)
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
    val context = LocalContext.current
    var currentFileName by remember { mutableStateOf("") }
    var isContainerVisible by remember { mutableStateOf(false) }
    val currentFileNameState by rememberUpdatedState(newValue = currentFileName)

    DisposableEffect(mediaPlayerManager) {
        mediaPlayerManager.onPlayerReady = { fileName ->
            isContainerVisible = true
            currentFileName = fileName
        }
        onDispose {
            mediaPlayerManager.onPlayerReady = {}
        }
    }

    if (isContainerVisible) {
        AndroidView(
            factory = {
                val constraintLayout = ConstraintLayout(context).apply {
                    id = View.generateViewId()
                    isClickable = true
                }

                val layoutInflater = LayoutInflater.from(context)
                val closeButtonContainer =
                    layoutInflater.inflate(
                        R.layout.exo_close_button,
                        constraintLayout,
                        false
                    ) as FrameLayout
                closeButtonContainer.id = View.generateViewId()
                closeButtonContainer.findViewById<ImageButton>(R.id.exo_close).setOnClickListener {
                    mediaPlayerManager.player?.stop()
                    mediaPlayerManager.playerControlView?.hide()
                    mediaPlayerManager.closeMediaPlayer()
                }

                val playerControlView = PlayerControlView(it).apply {
                    id = View.generateViewId()
                    setShowFastForwardButton(true)
                    setShowRewindButton(true)
                    isAnimationEnabled = true
                    showTimeoutMs = 0
                    player = mediaPlayerManager.player
                    hide()
                }
                mediaPlayerManager.playerControlView = playerControlView

                constraintLayout.addView(playerControlView)
                constraintLayout.addView(closeButtonContainer)

                // --- Start of Corrected Code ---
                val constraintSet = ConstraintSet().apply {
                    clone(constraintLayout)

                    // Constrain PlayerControlView to the bottom of the screen
                    connect(
                        playerControlView.id,
                        ConstraintSet.BOTTOM,
                        ConstraintSet.PARENT_ID,
                        ConstraintSet.BOTTOM
                    )
                    connect(
                        playerControlView.id,
                        ConstraintSet.START,
                        ConstraintSet.PARENT_ID,
                        ConstraintSet.START
                    )
                    connect(
                        playerControlView.id,
                        ConstraintSet.END,
                        ConstraintSet.PARENT_ID,
                        ConstraintSet.END
                    )
                    constrainPercentHeight(playerControlView.id, 0.25f)

                    // ✅ Align the button's top with the media overlay's top
                    connect(
                        closeButtonContainer.id,
                        ConstraintSet.TOP,
                        playerControlView.id,
                        ConstraintSet.TOP
                    )

                    // ✅ Align the button's end with the parent's end for top-right
                    connect(
                        closeButtonContainer.id,
                        ConstraintSet.END,
                        ConstraintSet.PARENT_ID,
                        ConstraintSet.END
                    )
                }
                // --- End of Corrected Code ---
                constraintSet.applyTo(constraintLayout)

                constraintLayout
            },
            update = { view ->
                mediaPlayerManager.playerControlView?.player = mediaPlayerManager.player
                val fileNameTextView = view.findViewById<TextView>(R.id.exo_file_name)
                fileNameTextView?.text = currentFileNameState
                mediaPlayerManager.playerControlView?.show()
            },
            modifier = modifier.fillMaxSize()
        )
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
            onDonateClick = {},
            onSettingsClick = {},
            onSignInClick = {},
            onDirectoryAlertDismiss = {},
            mediaPlayerManager = MediaPlayerManager(LocalContext.current) {},
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
        false
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
        onClick = {}
    )
}

@Preview(showBackground = true)
@Composable
fun SecondaryButtonPreview() {
    SecondaryActionButton(
        text = stringResource(R.string.play_a_recording),
        icon = R.drawable.baseline_play_circle_outline_24,
        onClick = {}
    )
}

@Preview(showBackground = true)
@Composable
fun DonateBannerPreview() {
    DonateBanner(
        modifier = Modifier
            .padding(16.dp),
        onClick = {}
    )
}