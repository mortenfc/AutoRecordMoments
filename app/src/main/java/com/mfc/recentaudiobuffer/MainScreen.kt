package com.mfc.recentaudiobuffer

import MediaPlayerManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.compose.Dimension
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerControlView
import arte.programar.materialfile.ui.FilePickerActivity

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onStartBufferingClick: () -> Unit,
    onStopBufferingClick: () -> Unit,
    onResetBufferClick: () -> Unit,
    onSaveBufferClick: () -> Unit,
    onPickAndPlayFileClick: () -> Unit,
    onDonateClick: () -> Unit,
    onSettingsClick: () -> Unit,
    mediaPlayerManager: MediaPlayerManager
) {
    val toolbarOutlineColor = colorResource(id = R.color.purple_accent)
    val toolbarBackgroundColor = colorResource(id = R.color.teal_350)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.app_name),
                        color = colorResource(id = R.color.teal_900)
                    )
                },
                modifier = Modifier
                    .drawBehind {
                        val paint = Paint().apply {
                            color = toolbarOutlineColor
                            strokeWidth = 8.dp.toPx()
                            style = PaintingStyle.Stroke
                        }
                        drawIntoCanvas { canvas ->
                            canvas.drawRoundRect(
                                left = 0f,
                                top = 0f,
                                right = size.width,
                                bottom = size.height,
                                radiusX = 0.dp.toPx(),
                                radiusY = 0.dp.toPx(),
                                paint = paint
                            )
                        }
                        drawRoundRect(
                            color = toolbarBackgroundColor,
                            topLeft = Offset(0f, 0f),
                            size = size,
                            style = androidx.compose.ui.graphics.drawscope.Fill,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                0.dp.toPx(),
                                0.dp.toPx()
                            )
                        )
                    },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                actions = {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape) // Clip to a circle shape
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(
                                    bounded = true,
                                    radius = 24.dp
                                ), // Circular ripple
                                onClick = {
                                    onSettingsClick()
                                }
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.baseline_settings_24),
                            contentDescription = stringResource(id = R.string.settings),
                            tint = Color.White
                        )
                    }
                }
            )
        },
        content = { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(colorResource(id = R.color.teal_100)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                MainButton(
                    text = stringResource(id = R.string.start_buffering_in_the_background),
                    icon = R.drawable.baseline_mic_24,
                    onClick = onStartBufferingClick
                )
                MainButton(
                    text = stringResource(id = R.string.pause_buffering_in_the_background),
                    icon = R.drawable.baseline_mic_off_24,
                    onClick = onStopBufferingClick
                )
                MainButton(
                    text = stringResource(id = R.string.clear_the_buffer),
                    icon = R.drawable.baseline_delete_outline_24,
                    onClick = onResetBufferClick
                )
                MainButton(
                    text = stringResource(id = R.string.save_the_buffer_as_a_recording),
                    icon = R.drawable.baseline_save_alt_24,
                    onClick = onSaveBufferClick
                )
                MainButton(
                    text = stringResource(id = R.string.play_a_recording),
                    icon = R.drawable.baseline_play_circle_outline_24,
                    onClick = onPickAndPlayFileClick
                )
                Spacer(modifier = Modifier.height(60.dp))
                MainButton(
                    text = stringResource(id = R.string.donate_and_remove_ads),
                    icon = R.drawable.dollar,
                    onClick = onDonateClick,
                    iconTint = Color.Red,
                    width = 260.dp
                )
            }

            PlayerControlViewContainer(mediaPlayerManager = mediaPlayerManager)
        }
    )
}

@Composable
fun MainButton(
    text: String,
    icon: Int,
    onClick: () -> Unit,
    iconTint: Color = Color.White,
    width: Dp = 180.dp,
    enabled: Boolean = true
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
                    this
                        .asFrameworkPaint()
                        .setShadowLayer(
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
            disabledContainerColor = colorResource(id = R.color.grey),
            disabledContentColor = colorResource(id = R.color.teal_100)
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(16.dp),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                colorResource(id = R.color.purple_accent)
            ), width = 2.dp
        ),
        enabled = enabled
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = text,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp
            )
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
    val currentFileNameState by rememberUpdatedState(newValue = currentFileName)

    DisposableEffect(mediaPlayerManager) {
        Log.d("PlayerControlViewContainer", "DisposableEffect mediaPlayerManager")
        mediaPlayerManager.onPlayerReady = { fileName ->
            Log.i("PlayerControlViewContainer", "Player is ready with filename: $fileName")
            currentFileName = fileName
        }
        onDispose {
            mediaPlayerManager.onPlayerReady = {}
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.BottomCenter) {
        AndroidView(
            factory = {
                ConstraintLayout(context).apply {
                    id = View.generateViewId()
                    val playerControlView = PlayerControlView(context).apply {
                        id = View.generateViewId()
                        setShowFastForwardButton(true)
                        setShowPlayButtonIfPlaybackIsSuppressed(true)
                        setShowRewindButton(true)
                        isAnimationEnabled = true
                        setTimeBarMinUpdateInterval(100)
                        showTimeoutMs = 0
                        hide()
                        hideImmediately()
                        player = mediaPlayerManager.player
                    }
                    mediaPlayerManager.playerControlView = playerControlView
                    val layoutParamsIn = ConstraintLayout.LayoutParams(
                        ConstraintLayout.LayoutParams.MATCH_PARENT,
                        ConstraintLayout.LayoutParams.MATCH_PARENT
                    )
                    playerControlView.layoutParams = layoutParamsIn
                    Log.d(
                        "PlayerControlViewContainer",
                        "PlayerControlView created with ID: ${playerControlView.id}"
                    )
                    addView(playerControlView)
                    val closeButton = setCloseButton(playerControlView, context, mediaPlayerManager)
                    val constraintSet = ConstraintSet().apply {
                        clone(this) // Clone the ConstraintLayout
                        // Constrain PlayerControlView to the bottom
                        connect(
                            playerControlView.id,
                            ConstraintSet.BOTTOM,
                            ConstraintSet.PARENT_ID,
                            ConstraintSet.BOTTOM
                        )
                        // Constrain PlayerControlView to the start
                        connect(
                            playerControlView.id,
                            ConstraintSet.START,
                            ConstraintSet.PARENT_ID,
                            ConstraintSet.START
                        )
                        // Constrain PlayerControlView to the end
                        connect(
                            playerControlView.id,
                            ConstraintSet.END,
                            ConstraintSet.PARENT_ID,
                            ConstraintSet.END
                        )
                        // Set height to 20% of the parent
                        constrainPercentHeight(playerControlView.id, 0.20f)
                        // Set width to 100% of the parent
                        constrainPercentWidth(playerControlView.id, 1.0f)
                        // Constrain closeButton to the top
                        connect(
                            closeButton.id,
                            ConstraintSet.TOP,
                            playerControlView.id,
                            ConstraintSet.TOP
                        )
                        // Constrain closeButton to the end
                        connect(
                            closeButton.id,
                            ConstraintSet.END,
                            playerControlView.id,
                            ConstraintSet.END
                        )
                    }
                    // Apply constraints
                    constraintSet.applyTo(this@apply)
                }
            },
            update = {
                Log.d("PlayerControlViewContainer", "AndroidView Update")
                mediaPlayerManager.playerControlView?.player = mediaPlayerManager.player
            },
            modifier = Modifier.fillMaxSize()
        )
        LaunchedEffect(currentFileNameState) {
            Log.d("PlayerControlViewContainer", "LaunchedEffect currentFileNameState update")
            val fileNameTextView =
                mediaPlayerManager.playerControlView?.findViewById<TextView>(R.id.exo_file_name)
            fileNameTextView?.text = currentFileNameState
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun setCloseButton(
    playerControlView: PlayerControlView,
    context: android.content.Context,
    mediaPlayerManager: MediaPlayerManager
): FrameLayout {
    val layoutInflater = LayoutInflater.from(context)
    val closeButtonContainer =
        layoutInflater.inflate(R.layout.exo_close_button, playerControlView, false) as FrameLayout
    closeButtonContainer.id = View.generateViewId()
    val closeButton = closeButtonContainer.findViewById<ImageButton>(R.id.exo_close)
    closeButton.setOnClickListener {
        mediaPlayerManager.player?.stop()
        mediaPlayerManager.playerControlView?.hide()
        mediaPlayerManager.closeMediaPlayer()
    }
    playerControlView.addView(closeButtonContainer)
    return closeButtonContainer
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MainScreen(
        onStartBufferingClick = {},
        onStopBufferingClick = {},
        onResetBufferClick = {},
        onSaveBufferClick = {},
        onPickAndPlayFileClick = {},
        onDonateClick = {},
        onSettingsClick = {},
        mediaPlayerManager = MediaPlayerManager(LocalContext.current) {}
    )
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