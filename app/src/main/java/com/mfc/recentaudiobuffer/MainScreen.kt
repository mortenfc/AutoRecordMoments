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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerControlView
import java.util.concurrent.TimeUnit

private data class LayoutSizing(
    val mainButtonTextStyle: TextStyle,
    val secondaryButtonTextStyle: TextStyle,
    val recordingButtonSize: Dp,
    val recordingInfoIconSize: Dp,
    val recordingMainIconSize: Dp,
    val secondaryActionButtonSize: Dp,
    val secondaryActionColumnWidth: Dp,
    val secondaryActionIconSize: Dp,
    val secondaryActionSpacing: Dp,
    val thankYouButtonSize: Dp,
    val thankYouIconSize: Dp,
    val thankYouTextStyle: TextStyle
)

/**
 * STATEFUL WRAPPER: The live app calls this composable.
 * It is responsible for connecting to ViewModels.
 */
@OptIn(UnstableApi::class)
@Composable
fun MainScreen(
    widthSizeClass: WindowWidthSizeClass,
    heightSizeClass: WindowHeightSizeClass,
    isRecordingFromService: Boolean,
    onStartBufferingClick: () -> Unit,
    onStopBufferingClick: () -> Unit,
    onResetBufferClick: () -> Unit,
    onSaveBufferClick: () -> Unit,
    onPickAndPlayFileClick: () -> Unit,
    showRecentFilesDialog: Boolean,
    onFileSelected: (Uri) -> Unit,
    onDonateClick: () -> Unit,
    hasDonated: Boolean,
    isRewardActive: Boolean,
    rewardExpiryTimestamp: Long,
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
    onDismissSaveDialog: () -> Unit
) {
    MainScreenContent(
        widthSizeClass = widthSizeClass,
        heightSizeClass = heightSizeClass,
        isRecordingFromService = isRecordingFromService,
        onStartBufferingClick = onStartBufferingClick,
        onStopBufferingClick = onStopBufferingClick,
        onResetBufferClick = onResetBufferClick,
        onSaveBufferClick = onSaveBufferClick,
        onPickAndPlayFileClick = onPickAndPlayFileClick,
        showRecentFilesDialog = showRecentFilesDialog,
        onFileSelected = onFileSelected,
        onDonateClick = onDonateClick,
        hasDonated = hasDonated,
        isRewardActive = isRewardActive,
        rewardExpiryTimestamp = rewardExpiryTimestamp,
        onSettingsClick = onSettingsClick,
        showDirectoryPermissionDialog = showDirectoryPermissionDialog,
        onDirectoryAlertDismiss = onDirectoryAlertDismiss,
        onTrimFileClick = onTrimFileClick,
        showTrimFileDialog = showTrimFileDialog,
        onTrimFileSelected = onTrimFileSelected,
        mediaPlayerManager = mediaPlayerManager,
        isLoading = isLoading,
        showSaveDialog = showSaveDialog,
        suggestedFileName = suggestedFileName,
        onConfirmSave = onConfirmSave,
        onDismissSaveDialog = onDismissSaveDialog,
        useLiveViewModel = true
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun MainScreenContent(
    widthSizeClass: WindowWidthSizeClass,
    heightSizeClass: WindowHeightSizeClass,
    isRecordingFromService: Boolean,
    onStartBufferingClick: () -> Unit,
    onStopBufferingClick: () -> Unit,
    onResetBufferClick: () -> Unit,
    onSaveBufferClick: () -> Unit,
    onPickAndPlayFileClick: () -> Unit,
    showRecentFilesDialog: Boolean,
    onFileSelected: (Uri) -> Unit,
    onDonateClick: () -> Unit,
    hasDonated: Boolean,
    isRewardActive: Boolean,
    rewardExpiryTimestamp: Long,
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
    useLiveViewModel: Boolean
) {
    var showPrivacyInfoDialog by remember { mutableStateOf(false) }

    val recordingButtonBackgroundColor by animateColorAsState(
        targetValue = if (isRecordingFromService) colorResource(id = R.color.red_pause).copy(
            alpha = 1f, red = 0.65f, blue = 0.3f
        )
        else colorResource(id = R.color.green_start).copy(alpha = 1f, green = 0.95f),
        animationSpec = tween(durationMillis = 400, easing = EaseInOutCubic),
        label = "recordingButtonBackgroundColor"
    )

    val recordingButtonElementsColor by animateColorAsState(
        targetValue = if (isRecordingFromService) Color.White else colorResource(id = R.color.teal_900),
        animationSpec = tween(durationMillis = 400, delayMillis = 50, easing = EaseInOutCubic),
        label = "recordingButtonElementsColor"
    )

    Scaffold(
        topBar = {
            if (useLiveViewModel) {
                TopAppBar(
                    title = stringResource(id = R.string.main), onSettingsClick = onSettingsClick
                )
            } else {
                TopAppBarContent(
                    title = stringResource(id = R.string.main),
                    signInButtonText = "Sign In",
                    isSigningIn = false,
                    authError = null,
                    onSignInClick = {},
                    onDismissErrorDialog = {},
                    onBackButtonClicked = null,
                    onIconClick = {},
                    onSettingsClick = onSettingsClick
                )
            }
        }) { innerPadding ->

        val isTabletLandscape =
            widthSizeClass == WindowWidthSizeClass.Expanded && heightSizeClass == WindowHeightSizeClass.Medium
        val isTabletPortrait =
            widthSizeClass == WindowWidthSizeClass.Medium && heightSizeClass == WindowHeightSizeClass.Expanded
        val isTablet = isTabletLandscape || isTabletPortrait
        val isLandscape =
            widthSizeClass == WindowWidthSizeClass.Expanded || heightSizeClass == WindowHeightSizeClass.Compact

        // --- Centralized Sizing Configuration ---
        val tabletSizing = LayoutSizing(
            mainButtonTextStyle = TextStyle(
                fontWeight = FontWeight.Bold, fontSize = 30.sp, textAlign = TextAlign.Center
            ),
            secondaryButtonTextStyle = TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 26.sp,
                color = colorResource(id = R.color.teal_900),
                textAlign = TextAlign.Center,
                lineHeight = 32.sp
            ),
            recordingButtonSize = 340.dp,
            recordingInfoIconSize = 60.dp,
            recordingMainIconSize = 100.dp,
            secondaryActionButtonSize = 150.dp,
            secondaryActionColumnWidth = 210.dp,
            secondaryActionIconSize = 70.dp,
            secondaryActionSpacing = 50.dp,
            thankYouButtonSize = 120.dp,
            thankYouIconSize = 60.dp,
            thankYouTextStyle = TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 26.sp,
                color = colorResource(id = R.color.teal_900),
                textAlign = TextAlign.Center
            )
        )

        val phoneSizing = LayoutSizing(
            mainButtonTextStyle = TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = if (!isLandscape) 20.sp else 16.sp,
                textAlign = TextAlign.Center
            ),
            secondaryButtonTextStyle = TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = colorResource(id = R.color.teal_900),
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            ),
            recordingButtonSize = if (!isLandscape) 180.dp else 160.dp,
            recordingInfoIconSize = 28.dp,
            recordingMainIconSize = 64.dp,
            secondaryActionButtonSize = 72.dp,
            secondaryActionColumnWidth = 102.dp,
            secondaryActionIconSize = 42.dp,
            secondaryActionSpacing = 10.dp,
            thankYouButtonSize = 72.dp,
            thankYouIconSize = 32.dp,
            thankYouTextStyle = TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = colorResource(id = R.color.teal_900),
                textAlign = TextAlign.Center
            )
        )

        val currentSizing = if (isTablet) tabletSizing else phoneSizing


        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(colorResource(id = R.color.teal_100))
        ) {
            when (isLandscape) {
                true -> {
                    // --- DEDICATED LANDSCAPE LAYOUT ---
                    LandscapeLayout(
                        isTablet = isTablet,
                        sizing = currentSizing,
                        isRecordingFromService = isRecordingFromService,
                        recordingButtonBackgroundColor = recordingButtonBackgroundColor,
                        recordingButtonElementsColor = recordingButtonElementsColor,
                        onToggleRecordingClick = { if (isRecordingFromService) onStopBufferingClick() else onStartBufferingClick() },
                        onPrivacyInfoClick = { showPrivacyInfoDialog = true },
                        onSaveBufferClick = onSaveBufferClick,
                        onTrimFileClick = onTrimFileClick,
                        onPickAndPlayFileClick = onPickAndPlayFileClick,
                        onResetBufferClick = onResetBufferClick,
                        hasDonated = hasDonated,
                        onDonateClick = onDonateClick,
                        mediaPlayerManager = mediaPlayerManager,
                        useLiveViewModel = useLiveViewModel
                    )
                }

                false -> {
                    // --- PORTRAIT / COMPACT LAYOUT ---
                    PortraitLayout(
                        sizing = currentSizing,
                        useLiveViewModel = useLiveViewModel,
                        hasDonated = hasDonated,
                        isRewardActive = isRewardActive,
                        rewardExpiryTimestamp = rewardExpiryTimestamp,
                        isRecordingFromService = isRecordingFromService,
                        recordingButtonBackgroundColor = recordingButtonBackgroundColor,
                        recordingButtonElementsColor = recordingButtonElementsColor,
                        onToggleRecordingClick = { if (isRecordingFromService) onStopBufferingClick() else onStartBufferingClick() },
                        onPrivacyInfoClick = { showPrivacyInfoDialog = true },
                        onSaveBufferClick = onSaveBufferClick,
                        onTrimFileClick = onTrimFileClick,
                        onPickAndPlayFileClick = onPickAndPlayFileClick,
                        onResetBufferClick = onResetBufferClick,
                        onDonateClick = onDonateClick,
                        mediaPlayerManager = mediaPlayerManager
                    )
                }
            }
        }
    }

    // --- DIALOGS & OVERLAYS ---
    if (isLoading) {
        LoadingIndicator()
    }

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

@OptIn(UnstableApi::class)
@Composable
private fun PortraitLayout(
    sizing: LayoutSizing,
    useLiveViewModel: Boolean,
    hasDonated: Boolean,
    isRewardActive: Boolean,
    rewardExpiryTimestamp: Long,
    isRecordingFromService: Boolean,
    recordingButtonBackgroundColor: Color,
    recordingButtonElementsColor: Color,
    onToggleRecordingClick: () -> Unit,
    onPrivacyInfoClick: () -> Unit,
    onSaveBufferClick: () -> Unit,
    onTrimFileClick: () -> Unit,
    onPickAndPlayFileClick: () -> Unit,
    onResetBufferClick: () -> Unit,
    onDonateClick: () -> Unit,
    mediaPlayerManager: MediaPlayerManager
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // --- HEADER SECTION ---
        if (useLiveViewModel && !hasDonated && !isRewardActive) {
            AdMobBanner(modifier = Modifier.padding(top = 8.dp))
        } else if (isRewardActive) {
            Spacer(Modifier.height(10.dp))
            RewardStatusCard(modifier = Modifier, expiryTimestamp = rewardExpiryTimestamp)
        }

        // --- CONTENT SECTION ---
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Spacer(Modifier.weight(0.3f))

            RecordingButtonWithInfo(
                isRecording = isRecordingFromService,
                backgroundColor = recordingButtonBackgroundColor,
                elementsColor = recordingButtonElementsColor,
                onToggleRecordingClick = onToggleRecordingClick,
                onPrivacyInfoClick = onPrivacyInfoClick,
                buttonSize = sizing.recordingButtonSize,
                infoIconSize = sizing.recordingInfoIconSize,
                mainIconSize = sizing.recordingMainIconSize,
                textStyle = sizing.mainButtonTextStyle
            )
            Spacer(Modifier.weight(0.2f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(sizing.secondaryActionSpacing),
                verticalAlignment = Alignment.Top
            ) {
                SecondaryActionButton(
                    text = stringResource(R.string.save_the_buffer_as_a_recording),
                    icon = R.drawable.baseline_save_alt_24,
                    onClick = onSaveBufferClick,
                    buttonSize = sizing.secondaryActionButtonSize,
                    columnWidth = sizing.secondaryActionColumnWidth,
                    iconSize = sizing.secondaryActionIconSize,
                    textStyle = sizing.secondaryButtonTextStyle
                )
                SecondaryActionButton(
                    text = "Remove All Non-\nSpeech From File",
                    icon = R.drawable.outline_content_cut_24,
                    onClick = onTrimFileClick,
                    buttonSize = sizing.secondaryActionButtonSize,
                    columnWidth = sizing.secondaryActionColumnWidth,
                    iconSize = sizing.secondaryActionIconSize,
                    textStyle = sizing.secondaryButtonTextStyle
                )
                SecondaryActionButton(
                    text = stringResource(R.string.play_a_recording),
                    icon = R.drawable.baseline_play_circle_outline_24,
                    onClick = onPickAndPlayFileClick,
                    buttonSize = sizing.secondaryActionButtonSize,
                    columnWidth = sizing.secondaryActionColumnWidth,
                    iconSize = sizing.secondaryActionIconSize,
                    textStyle = sizing.secondaryButtonTextStyle
                )
            }
            Spacer(Modifier.weight(0.1f))
            SecondaryActionButton(
                text = stringResource(R.string.clear_the_buffer),
                icon = R.drawable.baseline_delete_outline_24,
                onClick = onResetBufferClick,
                buttonSize = sizing.secondaryActionButtonSize,
                columnWidth = sizing.secondaryActionColumnWidth,
                iconSize = sizing.secondaryActionIconSize,
                textStyle = sizing.secondaryButtonTextStyle
            )
            Spacer(Modifier.weight(0.2f))
        }

        // --- FOOTER SECTION ---
        Box(
            modifier = Modifier
                .padding(bottom = 12.dp)
                .fillMaxWidth()
        ) {
            if (hasDonated) {
                ThankYouButton(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp),
                    onClick = onDonateClick,
                    buttonSize = sizing.thankYouButtonSize,
                    iconSize = sizing.thankYouIconSize,
                    textStyle = sizing.thankYouTextStyle
                )
            } else {
                DonateBanner(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 16.dp),
                    onClick = onDonateClick
                )
            }
        }
        if (useLiveViewModel) {
            PlayerControlViewContainer(mediaPlayerManager = mediaPlayerManager)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LandscapeLayout(
    isTablet: Boolean,
    sizing: LayoutSizing,
    isRecordingFromService: Boolean,
    recordingButtonBackgroundColor: Color,
    recordingButtonElementsColor: Color,
    onToggleRecordingClick: () -> Unit,
    onPrivacyInfoClick: () -> Unit,
    onSaveBufferClick: () -> Unit,
    onTrimFileClick: () -> Unit,
    onPickAndPlayFileClick: () -> Unit,
    onResetBufferClick: () -> Unit,
    hasDonated: Boolean,
    onDonateClick: () -> Unit,
    mediaPlayerManager: MediaPlayerManager,
    useLiveViewModel: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // --- Left Pane: Main Recording Button & Donation ---
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceAround // Evenly space the items
        ) {
            RecordingButtonWithInfo(
                isRecording = isRecordingFromService,
                backgroundColor = recordingButtonBackgroundColor,
                elementsColor = recordingButtonElementsColor,
                onToggleRecordingClick = onToggleRecordingClick,
                onPrivacyInfoClick = onPrivacyInfoClick,
                buttonSize = sizing.recordingButtonSize,
                infoIconSize = sizing.recordingInfoIconSize,
                mainIconSize = sizing.recordingMainIconSize,
                textStyle = sizing.mainButtonTextStyle
            )

            if (hasDonated) {
                ThankYouButton(
                    onClick = onDonateClick,
                    buttonSize = sizing.thankYouButtonSize,
                    iconSize = sizing.thankYouIconSize,
                    textStyle = sizing.thankYouTextStyle
                )
            } else {
                DonateBanner(onClick = onDonateClick, fontSize = 16.sp)
            }
        }

        // --- Right Pane: Secondary Actions & Player ---
        Column(
            modifier = Modifier
                .weight(if (isTablet) 1.2f else 1f) // Give right pane more space on tablet
                .fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // This column groups the buttons, is weighted, and centers its content
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // This inner column just keeps the two rows of buttons together
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(sizing.secondaryActionSpacing)) {
                        SecondaryActionButton(
                            text = stringResource(R.string.save_the_buffer_as_a_recording),
                            icon = R.drawable.baseline_save_alt_24,
                            onClick = onSaveBufferClick,
                            buttonSize = sizing.secondaryActionButtonSize,
                            columnWidth = sizing.secondaryActionColumnWidth,
                            iconSize = sizing.secondaryActionIconSize,
                            textStyle = sizing.secondaryButtonTextStyle
                        )
                        SecondaryActionButton(
                            text = stringResource(R.string.play_a_recording),
                            icon = R.drawable.baseline_play_circle_outline_24,
                            onClick = onPickAndPlayFileClick,
                            buttonSize = sizing.secondaryActionButtonSize,
                            columnWidth = sizing.secondaryActionColumnWidth,
                            iconSize = sizing.secondaryActionIconSize,
                            textStyle = sizing.secondaryButtonTextStyle
                        )
                    }
                    Spacer(Modifier.height(sizing.secondaryActionSpacing))
                    Row(horizontalArrangement = Arrangement.spacedBy(sizing.secondaryActionSpacing)) {
                        SecondaryActionButton(
                            text = "Remove All Non-\nSpeech From File",
                            icon = R.drawable.outline_content_cut_24,
                            onClick = onTrimFileClick,
                            buttonSize = sizing.secondaryActionButtonSize,
                            columnWidth = sizing.secondaryActionColumnWidth,
                            iconSize = sizing.secondaryActionIconSize,
                            textStyle = sizing.secondaryButtonTextStyle
                        )
                        SecondaryActionButton(
                            text = stringResource(R.string.clear_the_buffer),
                            icon = R.drawable.baseline_delete_outline_24,
                            onClick = onResetBufferClick,
                            buttonSize = sizing.secondaryActionButtonSize,
                            columnWidth = sizing.secondaryActionColumnWidth,
                            iconSize = sizing.secondaryActionIconSize,
                            textStyle = sizing.secondaryButtonTextStyle
                        )
                    }
                }
            }
            // Player appears at the bottom of the pane, outside the weighted content
            if (useLiveViewModel) {
                PlayerControlViewContainer(mediaPlayerManager = mediaPlayerManager)
            }
        }
    }
}


@Composable
private fun RecordingButtonWithInfo(
    isRecording: Boolean,
    backgroundColor: Color,
    elementsColor: Color,
    onToggleRecordingClick: () -> Unit,
    onPrivacyInfoClick: () -> Unit,
    buttonSize: Dp,
    infoIconSize: Dp,
    mainIconSize: Dp,
    textStyle: TextStyle
) {
    // ConstraintLayout is the perfect tool for positioning one item relative to another.
    ConstraintLayout {
        // Create references for the button and the icon
        val (buttonRef, iconRef) = createRefs()

        RecordingToggleButton(
            isRecording = isRecording,
            backgroundColor = backgroundColor,
            elementsColor = elementsColor,
            onClick = onToggleRecordingClick,
            modifier = Modifier
                .size(buttonSize)
                .constrainAs(buttonRef) {
                    // Place the button in the center of the layout.
                    // This ConstraintLayout will wrap to fit the button and icon.
                    top.linkTo(parent.top)
                    bottom.linkTo(parent.bottom)
                    start.linkTo(parent.start)
                    end.linkTo(parent.end)
                },
            iconSize = mainIconSize,
            textStyle = textStyle
        )

        IconButton(
            onClick = onPrivacyInfoClick,
            modifier = Modifier
                .size(infoIconSize)
                .constrainAs(iconRef) {
                    // 1. Center the icon vertically on the button's top edge.
                    top.linkTo(buttonRef.top)
                    bottom.linkTo(buttonRef.top)

                    // 2. Center the icon horizontally on the button's end (right) edge.
                    start.linkTo(buttonRef.end)
                    end.linkTo(buttonRef.end)
                }) {
            Icon(
                Icons.Default.Info,
                "Show privacy info",
                tint = colorResource(id = R.color.purple_accent),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun RecordingToggleButton(
    modifier: Modifier = Modifier,
    isRecording: Boolean,
    backgroundColor: Color,
    elementsColor: Color,
    onClick: () -> Unit,
    iconSize: Dp,
    textStyle: TextStyle
) {
    val buttonText = if (isRecording) "Pause\nRecording" else "Start\nRecording"
    val iconRes = if (isRecording) R.drawable.baseline_mic_off_24 else R.drawable.baseline_mic_24

    Button(
        onClick = onClick,
        modifier = modifier.circularShadow(radius = 5.dp, offsetY = 5.dp),
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
                modifier = Modifier.size(iconSize),
                tint = elementsColor
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = buttonText,
                color = elementsColor,
                style = textStyle,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
fun SecondaryActionButton(
    modifier: Modifier = Modifier,
    buttonSize: Dp,
    columnWidth: Dp,
    iconSize: Dp,
    text: String,
    icon: Int,
    onClick: () -> Unit,
    textStyle: TextStyle
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.width(columnWidth)
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .size(buttonSize)
                .roundedRectShadow(
                    shadowRadius = 4.dp, offsetY = 4.dp, cornerRadius = 24.dp
                ),
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
                tint = colorResource(id = R.color.teal_900),
                modifier = Modifier.size(iconSize)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = text, style = textStyle
        )
    }
}

/**
 * A golden reward card
 */
@Composable
fun RewardStatusCard(
    expiryTimestamp: Long, modifier: Modifier
) {
    val remainingMillis = expiryTimestamp - System.currentTimeMillis()
    val days = TimeUnit.MILLISECONDS.toDays(remainingMillis)
    val hours = TimeUnit.MILLISECONDS.toHours(remainingMillis) % 24

    val expiryText = when {
        days > 0 -> "Reward: No ads for ${days}d ${hours}h"
        hours > 0 -> "Reward: No ads for ${hours}h"
        remainingMillis > 0 -> "Reward: No ads for <1h"
        else -> "Reward Expired"
    }

    Row(
        // Increased padding to give the text more room
        modifier = modifier
            .padding(vertical = 0.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.roundedRectShadow(
                4.dp, 4.dp, 16.dp, colorResource(id = R.color.gold_premium_border)
            ),
            border = BorderStroke(2.dp, colorResource(id = R.color.gold_premium_border)),
            colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.green_start))
        ) {
            Row(
                // Increased padding to give the text more room
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ad_off_24),
                    contentDescription = "Reward Active",
                    tint = colorResource(id = R.color.purple_accent),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = expiryText,
                    color = colorResource(id = R.color.teal_900),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
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
    bottomPadding: Dp = 30.dp,
    iconSize: Dp = 22.dp,
    maxLines: Int = 1,
    textStyle: TextStyle = TextStyle(
        fontWeight = FontWeight.Normal, fontSize = 14.sp
    )
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .padding(bottom = bottomPadding)
            .roundedRectShadow(
                shadowRadius = 5.dp,
                offsetY = 5.dp,
                cornerRadius = 8.dp,
                insetX = (16.dp - contentPadding) / 20f,
                insetY = if (contentPadding < 16.dp) {
                    (16.dp - contentPadding) / 1.9f
                } else 0.dp
            ),
        colors = ButtonDefaults.buttonColors(
            containerColor = colorResource(id = R.color.teal_350),
            contentColor = colorResource(id = R.color.teal_900),
            disabledContainerColor = colorResource(id = R.color.light_grey),
            disabledContentColor = colorResource(id = R.color.black)
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(contentPadding),
        border = ButtonDefaults.outlinedButtonBorder().copy(
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
                style = textStyle,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
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
fun DonateBanner(modifier: Modifier = Modifier, onClick: () -> Unit, fontSize: TextUnit = 20.sp) {
    Card(
        onClick = onClick,
        modifier = modifier.roundedRectShadow(5.dp, 5.dp, 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.teal_350)),
        border = BorderStroke(2.dp, colorResource(id = R.color.purple_accent))
    ) {
        Row(
            // Padding is applied inside to keep content snug
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
                fontSize = fontSize
            )
        }
    }
}

@Composable
fun ThankYouButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    buttonSize: Dp = 72.dp,
    iconSize: Dp = 32.dp,
    textStyle: TextStyle = MaterialTheme.typography.bodySmall
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .size(buttonSize)
                .roundedRectShadow(
                    shadowRadius = 4.dp, offsetY = 4.dp, cornerRadius = buttonSize / 2
                ),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colorResource(id = R.color.teal_350),
                contentColor = colorResource(id = R.color.teal_900)
            ),
            border = BorderStroke(2.dp, colorResource(id = R.color.purple_accent)),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.volunteer_activism_24),
                contentDescription = "Thank you for your support",
                modifier = Modifier.size(iconSize),
                tint = colorResource(id = R.color.gold)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Supporter",
            style = textStyle,
            color = colorResource(id = R.color.teal_900),
            textAlign = TextAlign.Center
        )
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
                    .background(Color.Black.copy(alpha = 0.3f))
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

// --- PREVIEWS ---

@OptIn(UnstableApi::class)
@Preview(
    showBackground = true,
    name = "Phone Portrait (Compact)",
    device = "spec:width=360dp,height=615dp,dpi=480"
)
@Composable
fun MainScreen9x16PhonePortraitPreview() {
    MaterialTheme {
        MainScreenContent(
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Medium,
            isRecordingFromService = false,
            onStartBufferingClick = {},
            onStopBufferingClick = {},
            onResetBufferClick = {},
            onSaveBufferClick = {},
            onPickAndPlayFileClick = {},
            showRecentFilesDialog = false,
            onFileSelected = {},
            onDonateClick = {},
            hasDonated = false,
            isRewardActive = true,
            rewardExpiryTimestamp = 0,
            onSettingsClick = {},
            showDirectoryPermissionDialog = false,
            onDirectoryAlertDismiss = {},
            onTrimFileClick = {},
            showTrimFileDialog = false,
            onTrimFileSelected = {},
            mediaPlayerManager = MediaPlayerManager(LocalContext.current) { _, _ -> },
            isLoading = false,
            showSaveDialog = false,
            suggestedFileName = "preview_file.wav",
            onConfirmSave = {},
            onDismissSaveDialog = {},
            useLiveViewModel = false
        )
    }
}

@OptIn(UnstableApi::class)
@Preview(
    showBackground = true
)
@Composable
fun MainScreenTypicalPhonePortraitPreview() {
    MaterialTheme {
        MainScreenContent(
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Medium,
            isRecordingFromService = false,
            onStartBufferingClick = {},
            onStopBufferingClick = {},
            onResetBufferClick = {},
            onSaveBufferClick = {},
            onPickAndPlayFileClick = {},
            showRecentFilesDialog = false,
            onFileSelected = {},
            onDonateClick = {},
            hasDonated = false,
            isRewardActive = true,
            rewardExpiryTimestamp = 0,
            onSettingsClick = {},
            showDirectoryPermissionDialog = false,
            onDirectoryAlertDismiss = {},
            onTrimFileClick = {},
            showTrimFileDialog = false,
            onTrimFileSelected = {},
            mediaPlayerManager = MediaPlayerManager(LocalContext.current) { _, _ -> },
            isLoading = false,
            showSaveDialog = false,
            suggestedFileName = "preview_file.wav",
            onConfirmSave = {},
            onDismissSaveDialog = {},
            useLiveViewModel = false
        )
    }
}

@OptIn(UnstableApi::class)
@Preview(
    showBackground = true,
    name = "Phone Landscape (Compact Height)",
    device = "spec:width=640dp,height=335dp,dpi=480"
)
@Composable
fun MainScreenPhoneLandscapePreview() {
    MaterialTheme {
        MainScreenContent(
            widthSizeClass = WindowWidthSizeClass.Medium,
            heightSizeClass = WindowHeightSizeClass.Compact,
            isRecordingFromService = false,
            onStartBufferingClick = {},
            onStopBufferingClick = {},
            onResetBufferClick = {},
            onSaveBufferClick = {},
            onPickAndPlayFileClick = {},
            showRecentFilesDialog = false,
            onFileSelected = {},
            onDonateClick = {},
            hasDonated = false,
            isRewardActive = false,
            rewardExpiryTimestamp = 0,
            onSettingsClick = {},
            showDirectoryPermissionDialog = false,
            onDirectoryAlertDismiss = {},
            onTrimFileClick = {},
            showTrimFileDialog = false,
            onTrimFileSelected = {},
            mediaPlayerManager = MediaPlayerManager(LocalContext.current) { _, _ -> },
            isLoading = false,
            showSaveDialog = false,
            suggestedFileName = "preview_file.wav",
            onConfirmSave = {},
            onDismissSaveDialog = {},
            useLiveViewModel = false
        )
    }
}

@OptIn(UnstableApi::class)
@Preview(
    showBackground = true,
    name = "Tablet Portrait (Medium Width)",
    device = "spec:width=800dp,height=1280dp,dpi=240"
)
@Composable
fun MainScreenTabletPortraitPreview() {
    MaterialTheme {
        MainScreenContent(
            widthSizeClass = WindowWidthSizeClass.Medium,
            heightSizeClass = WindowHeightSizeClass.Expanded,
            isRecordingFromService = true,
            onStartBufferingClick = {},
            onStopBufferingClick = {},
            onResetBufferClick = {},
            onSaveBufferClick = {},
            onPickAndPlayFileClick = {},
            showRecentFilesDialog = false,
            onFileSelected = {},
            onDonateClick = {},
            hasDonated = true,
            isRewardActive = false,
            rewardExpiryTimestamp = 0,
            onSettingsClick = {},
            showDirectoryPermissionDialog = false,
            onDirectoryAlertDismiss = {},
            onTrimFileClick = {},
            showTrimFileDialog = false,
            onTrimFileSelected = {},
            mediaPlayerManager = MediaPlayerManager(LocalContext.current) { _, _ -> },
            isLoading = false,
            showSaveDialog = false,
            suggestedFileName = "preview_file.wav",
            onConfirmSave = {},
            onDismissSaveDialog = {},
            useLiveViewModel = false
        )
    }
}


@OptIn(UnstableApi::class)
@Preview(
    showBackground = true,
    name = "Tablet Landscape (Expanded Width)",
    device = "spec:width=1280dp,height=800dp,dpi=240"
)
@Composable
fun MainScreenTabletLandscapePreview() {
    MaterialTheme {
        MainScreenContent(
            widthSizeClass = WindowWidthSizeClass.Expanded,
            heightSizeClass = WindowHeightSizeClass.Medium,
            isRecordingFromService = true,
            onStartBufferingClick = {},
            onStopBufferingClick = {},
            onResetBufferClick = {},
            onSaveBufferClick = {},
            onPickAndPlayFileClick = {},
            showRecentFilesDialog = false,
            onFileSelected = {},
            onDonateClick = {},
            hasDonated = true,
            isRewardActive = false,
            rewardExpiryTimestamp = 0,
            onSettingsClick = {},
            showDirectoryPermissionDialog = false,
            onDirectoryAlertDismiss = {},
            onTrimFileClick = {},
            showTrimFileDialog = false,
            onTrimFileSelected = {},
            mediaPlayerManager = MediaPlayerManager(LocalContext.current) { _, _ -> },
            isLoading = false,
            showSaveDialog = false,
            suggestedFileName = "preview_file.wav",
            onConfirmSave = {},
            onDismissSaveDialog = {},
            useLiveViewModel = false
        )
    }
}