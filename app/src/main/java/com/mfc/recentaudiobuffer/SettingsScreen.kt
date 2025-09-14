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

import android.annotation.SuppressLint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mfc.recentaudiobuffer.ui.theme.RecentAudioBufferTheme
import timber.log.Timber
import java.util.Locale

private data class SettingsSizing(
    val infoIconPadding: Dp,
    val infoIconSize: Dp,
    val landscapeHorizontalPadding: Dp,
    val landscapeVerticalPadding: Dp,
    val audioSettingsTextStyle: TextStyle,
    val audioSettingsButtonHeight: Dp,
    val aiTitleStyle: TextStyle,
    val aiBodyStyle: TextStyle,
    val aiSwitchScale: Float,
    val actionsButtonTextStyle: TextStyle,
    val actionsButtonIconSize: Dp,
    val actionsButtonPadding: Dp,
    val actionsButtonWidthFraction: Float,
    val actionsGroupTopSpacerHeight: Dp,
    val actionsButtonBottomPadding: Dp,
    val dangerZoneTextStyle: TextStyle,
    val dangerZoneSpacerHeight: Dp
)

/**
 * Stateful wrapper for the Settings Screen.
 * This is the component your app's navigation will call.
 * Its only job is to get the ViewModel and collect state.
 */
@Composable
fun SettingsScreen(
    state: MutableState<SettingsScreenState>,
    widthSizeClass: WindowWidthSizeClass,
    heightSizeClass: WindowHeightSizeClass,
    onDeleteAccountClick: () -> Unit,
    onSampleRateChanged: (Int) -> Unit,
    onBitDepthChanged: (BitDepth) -> Unit,
    onBufferTimeLengthChanged: (Int) -> Unit,
    onAiAutoClipChanged: (Boolean) -> Unit,
    onSubmit: (Int) -> Unit,
    justExit: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel() // Get the ViewModel
) {
    // Collect state from the ViewModel. This is the only place Hilt is touched.
    val signInButtonText by viewModel.signInButtonText
    val isUserSignedIn = signInButtonText == "Sign Out"

    // Call the stateless composable with the collected state
    SettingsScreenContent(
        state = state,
        widthSizeClass = widthSizeClass,
        heightSizeClass = heightSizeClass,
        isUserSignedIn = isUserSignedIn,
        onDeleteAccountClick = onDeleteAccountClick,
        onSampleRateChanged = onSampleRateChanged,
        onBitDepthChanged = onBitDepthChanged,
        onBufferTimeLengthChanged = onBufferTimeLengthChanged,
        onAiAutoClipChanged = onAiAutoClipChanged,
        onSubmit = onSubmit,
        justExit = justExit
    )
}

/**
 * Stateless implementation of the Settings Screen.
 * Contains all the UI and is fully previewable because it has no reference to Hilt.
 */// Stateless UI (All visual changes are here)
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
private fun SettingsScreenContent(
    state: MutableState<SettingsScreenState>,
    widthSizeClass: WindowWidthSizeClass,
    heightSizeClass: WindowHeightSizeClass,
    isUserSignedIn: Boolean,
    onDeleteAccountClick: () -> Unit,
    onSampleRateChanged: (Int) -> Unit,
    onBitDepthChanged: (BitDepth) -> Unit,
    onBufferTimeLengthChanged: (Int) -> Unit,
    onAiAutoClipChanged: (Boolean) -> Unit,
    onSubmit: (Int) -> Unit,
    justExit: () -> Unit,
    isPreview: Boolean = false
) {
    var showHelpDialog by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Scaffold(
        containerColor = colorResource(id = R.color.teal_100),
        modifier = Modifier.pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
        topBar = {
            if (isPreview) {
                TopAppBarContent(
                    title = stringResource(id = R.string.settings),
                    onBackButtonClicked = { justExit() },
                    // Dummy values for preview-ability, the real TopAppBar will supply real ones
                    signInButtonText = if (isUserSignedIn) "Sign Out" else "Sign In",
                    isSigningIn = false,
                    authError = null,
                    onSignInClick = {},
                    onDismissErrorDialog = {},
                    onIconClick = { justExit() },
                    onSettingsClick = null
                )
            } else {
                TopAppBar(
                    title = stringResource(id = R.string.settings),
                    onBackButtonClicked = { justExit() })
            }
        }) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(12.dp))
                .border(
                    width = 3.dp,
                    color = colorResource(id = R.color.purple_accent),
                    shape = RoundedCornerShape(12.dp)
                )
                .background(
                    color = colorResource(id = R.color.teal_150), shape = RoundedCornerShape(12.dp)
                )
        ) {
            val isTabletLandscape =
                widthSizeClass == WindowWidthSizeClass.Expanded && heightSizeClass == WindowHeightSizeClass.Medium
            val isTabletPortrait =
                widthSizeClass == WindowWidthSizeClass.Medium && heightSizeClass == WindowHeightSizeClass.Expanded
            val isTablet = isTabletLandscape || isTabletPortrait
            val isLandscape =
                widthSizeClass == WindowWidthSizeClass.Expanded || heightSizeClass == WindowHeightSizeClass.Compact

            val phoneSizing = SettingsSizing(
                infoIconPadding = 5.dp,
                infoIconSize = 35.dp,
                landscapeHorizontalPadding = 20.dp,
                landscapeVerticalPadding = 10.dp,
                audioSettingsTextStyle = TextStyle(fontSize = 18.sp),
                audioSettingsButtonHeight = 52.dp,
                aiTitleStyle = TextStyle(fontSize = 18.sp),
                aiBodyStyle = TextStyle(fontSize = 15.sp),
                aiSwitchScale = 1.0f,
                actionsButtonTextStyle = TextStyle(fontSize = 19.sp).copy(fontWeight = FontWeight.SemiBold),
                actionsButtonIconSize = 26.dp,
                actionsButtonPadding = 12.dp,
                actionsButtonWidthFraction = 0.7f,
                actionsGroupTopSpacerHeight = 6.dp,
                actionsButtonBottomPadding = 12.dp,
                dangerZoneTextStyle = MaterialTheme.typography.titleMedium,
                dangerZoneSpacerHeight = 0.dp
            )

            val tabletSizing = SettingsSizing(
                infoIconPadding = 16.dp,
                infoIconSize = 70.dp,
                landscapeHorizontalPadding = 50.dp,
                landscapeVerticalPadding = 25.dp,
                audioSettingsTextStyle = TextStyle(fontSize = 26.sp),
                audioSettingsButtonHeight = 80.dp,
                aiTitleStyle = TextStyle(fontSize = 30.sp),
                aiBodyStyle = TextStyle(fontSize = 16.sp),
                aiSwitchScale = 1.2f,
                actionsButtonTextStyle = TextStyle(
                    fontSize = 26.sp, fontWeight = FontWeight.Medium
                ),
                actionsButtonIconSize = 34.dp,
                actionsButtonPadding = 14.dp,
                actionsButtonWidthFraction = 0.7f,
                actionsGroupTopSpacerHeight = 12.dp,
                actionsButtonBottomPadding = 12.dp,
                dangerZoneTextStyle = MaterialTheme.typography.titleLarge,
                dangerZoneSpacerHeight = 12.dp
            )

            val sizing = if (isTablet) tabletSizing else phoneSizing
            when (isLandscape) {
                false -> {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                            .padding(start = 12.dp, end = 12.dp)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.weight(0.1f))
                        AudioSettingsGroup(
                            state,
                            onSampleRateChanged,
                            onBitDepthChanged,
                            onBufferTimeLengthChanged,
                            sizing
                        )
                        Spacer(Modifier.weight(0.15f))
                        AISettingsGroup(state, onAiAutoClipChanged, sizing)
                        Spacer(Modifier.weight(0.15f))
                        ActionsGroup(state, onSubmit, isUserSignedIn, sizing)
                        if (isUserSignedIn) {
                            Spacer(Modifier.weight(0.15f))
                            DangerZoneGroup(onDeleteAccountClick = onDeleteAccountClick, sizing)
                        }
                    }
                }

                true -> { // Landscape Layout
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                horizontal = sizing.landscapeHorizontalPadding,
                                vertical = sizing.landscapeVerticalPadding
                            ),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically // Center both panes vertically
                    ) {
                        // --- Left Pane ---
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Spacer(Modifier.weight(0.1f))
                            AudioSettingsGroup(
                                state,
                                onSampleRateChanged,
                                onBitDepthChanged,
                                onBufferTimeLengthChanged,
                                sizing
                            )
                            Spacer(Modifier.weight(0.1f))
                            if (!isTablet && isUserSignedIn) {
                                DangerZoneGroup(onDeleteAccountClick = onDeleteAccountClick, sizing)
                                Spacer(Modifier.weight(0.1f))
                            }
                        }

                        // --- Right Pane ---
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Spacer(Modifier.weight(.2f))
                            AISettingsGroup(state, onAiAutoClipChanged, sizing)
                            Spacer(Modifier.weight(.2f))
                            ActionsGroup(state, onSubmit, isUserSignedIn, sizing)
                            Spacer(Modifier.weight(.2f))
                            if (isTablet && isUserSignedIn) {
                                DangerZoneGroup(onDeleteAccountClick = onDeleteAccountClick, sizing)
                                Spacer(Modifier.weight(.01f))
                            }
                        }
                    }
                }
            }

            IconButton(
                onClick = { showHelpDialog = true },
                modifier = Modifier
                    .align(if (isLandscape) Alignment.TopStart else Alignment.TopEnd)
                    .padding(sizing.infoIconPadding)
                    .size(sizing.infoIconSize)
            ) {
                Icon(
                    Icons.Default.Info,
                    "Show settings help",
                    tint = colorResource(id = R.color.purple_accent),
                    modifier = Modifier.size(sizing.infoIconSize)
                )
            }
        }
    }

    if (showHelpDialog) {
        ComprehensiveHelpDialog(
            sampleRate = state.value.sampleRateTemp.intValue,
            bitDepth = state.value.bitDepthTemp.value,
            bufferTimeLength = state.value.bufferTimeLengthTemp.intValue,
            isAiAutoClipEnabled = state.value.isAiAutoClipEnabled.value,
            onDismissRequest = { showHelpDialog = false },
            onPresetClick = { preset ->
                onSampleRateChanged(preset.sampleRate)
                onBitDepthChanged(preset.bitDepth)
                onBufferTimeLengthChanged(preset.bufferLength)
                onAiAutoClipChanged(preset.aiEnabled)
            })
    }
}

// --- Reusable Group Components ---

@Composable
private fun ColumnScope.AudioSettingsGroup(
    state: MutableState<SettingsScreenState>,
    onSampleRateChanged: (Int) -> Unit,
    onBitDepthChanged: (BitDepth) -> Unit,
    onBufferTimeLengthChanged: (Int) -> Unit,
    sizing: SettingsSizing
) {
    var showSampleRateMenu by remember { mutableStateOf(false) }
    var showBitDepthMenu by remember { mutableStateOf(false) }

    SettingsButton(
        text = "Sample Rate: ${state.value.sampleRateTemp.intValue} Hz",
        icon = Icons.Filled.ArrowDropDown,
        onClick = { showSampleRateMenu = true },
        modifier = Modifier.height(sizing.audioSettingsButtonHeight),
        textStyle = sizing.audioSettingsTextStyle
    )
    DropdownMenu(
        expanded = showSampleRateMenu,
        onDismissRequest = { showSampleRateMenu = false },
        modifier = Modifier.background(colorResource(id = R.color.teal_100))
    ) {
        sampleRates.forEach { (label, value) ->
            StyledDropdownMenuItem(text = "$label Hz", onClick = {
                onSampleRateChanged(value)
                showSampleRateMenu = false
            })
        }
    }
    Spacer(Modifier.weight(0.2f))
    SettingsButton(
        text = "Bit Depth: ${state.value.bitDepthTemp.value.bits} bit",
        icon = Icons.Filled.ArrowDropDown,
        onClick = { showBitDepthMenu = true },
        modifier = Modifier.height(sizing.audioSettingsButtonHeight),
        textStyle = sizing.audioSettingsTextStyle
    )
    DropdownMenu(
        expanded = showBitDepthMenu,
        onDismissRequest = { showBitDepthMenu = false },
        modifier = Modifier.background(colorResource(id = R.color.teal_100))
    ) {
        bitDepths.forEach { (label, value) ->
            StyledDropdownMenuItem(text = "$label bit", onClick = {
                onBitDepthChanged(value)
                showBitDepthMenu = false
            })
        }
    }
    Spacer(Modifier.weight(0.2f))
    MyOutlinedBufferInputField(
        bufferTimeLength = state.value.bufferTimeLengthTemp,
        onValueChange = onBufferTimeLengthChanged,
        isMaxExceeded = state.value.isMaxExceeded,
        isNull = state.value.isBufferTimeLengthNull,
        modifier = Modifier.height(sizing.audioSettingsButtonHeight),
        textStyle = sizing.audioSettingsTextStyle
    )
}

@Composable
private fun AISettingsGroup(
    state: MutableState<SettingsScreenState>,
    onAiAutoClipChanged: (Boolean) -> Unit,
    sizing: SettingsSizing
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (sizing.aiSwitchScale > 1.2f) 12.dp else 2.dp)
    ) {
        HorizontalDivider(
            color = colorResource(id = R.color.purple_accent).copy(
                alpha = 0.5f,
            ), thickness = 2.dp
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "AI Auto-Trimming",
                style = sizing.aiTitleStyle,
                fontWeight = FontWeight.SemiBold,
                color = colorResource(id = R.color.teal_900)
            )
            Switch(
                checked = state.value.isAiAutoClipEnabled.value,
                onCheckedChange = onAiAutoClipChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colorResource(id = R.color.purple_accent),
                    checkedBorderColor = colorResource(id = R.color.teal_350),
                    checkedTrackColor = colorResource(id = R.color.teal_350),
                    uncheckedThumbColor = colorResource(id = R.color.purple_accent),
                    uncheckedTrackColor = colorResource(id = R.color.teal_100),
                    uncheckedBorderColor = colorResource(id = R.color.teal_350),
                ),
                modifier = Modifier.scale(sizing.aiSwitchScale)
            )
        }
        Text(
            text = buildAnnotatedString {
                append("When enabled, saving the buffer will automatically trim away all non-speech (including music!). It uses a local model stored on your device, no internet is used. \n")
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                    append("ATTENTION:")
                }
                append(" This resamples down to 16 kHz and can take some time to run for long buffers.")
            }, style = sizing.aiBodyStyle.copy(
                textAlign = TextAlign.Justify,
                hyphens = Hyphens.Auto,
                lineBreak = LineBreak.Paragraph,
            ), color = colorResource(id = R.color.teal_700),

            modifier = Modifier.padding(0.dp)
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(
            color = colorResource(id = R.color.purple_accent).copy(
                alpha = 0.5f
            ), thickness = 2.dp
        )
    }
}

@Composable
private fun ActionsGroup(
    state: MutableState<SettingsScreenState>,
    onSubmit: (Int) -> Unit,
    isUserSignedIn: Boolean,
    sizing: SettingsSizing
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Spacer(modifier = Modifier.height(sizing.actionsGroupTopSpacerHeight))
        if (state.value.errorMessage.value != null) {
            Text(text = state.value.errorMessage.value!!, color = Color.Red)
            Spacer(modifier = Modifier.height(4.dp))
        }
        MainButton(
            text = "Apply Settings",
            textStyle = sizing.actionsButtonTextStyle,
            icon = R.drawable.outline_restore_page_24,
            iconSize = sizing.actionsButtonIconSize,
            onClick = {
                if (state.value.isSubmitEnabled.value) {
                    onSubmit(state.value.bufferTimeLengthTemp.intValue)
                }
            },
            bottomPadding = sizing.actionsButtonBottomPadding,
            iconTint = colorResource(id = R.color.purple_accent),
            enabled = state.value.isSubmitEnabled.value,
            modifier = Modifier.fillMaxWidth(fraction = sizing.actionsButtonWidthFraction),
            contentPadding = sizing.actionsButtonPadding
        )
        if (!isUserSignedIn) {
            Text(
                text = "Sign In to sync settings with cloud",
                color = colorResource(id = R.color.purple_accent),
                style = sizing.audioSettingsTextStyle,
                textAlign = TextAlign.Center,
                fontStyle = FontStyle.Italic
            )
        }
    }
}

@Composable
private fun DangerZoneGroup(onDeleteAccountClick: () -> Unit, sizing: SettingsSizing) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        HorizontalDivider(
            color = colorResource(id = R.color.red).copy(alpha = 0.7f), thickness = 4.dp
        )
        Spacer(Modifier.height(sizing.dangerZoneSpacerHeight))
        Button(
            onClick = onDeleteAccountClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = colorResource(id = R.color.white).copy(alpha = 0.3f),
                contentColor = colorResource(id = R.color.red),
                disabledContainerColor = colorResource(id = R.color.teal_100).copy(alpha = 0.3f),
                disabledContentColor = colorResource(id = R.color.red).copy(alpha = 0.3f)
            ),
            modifier = Modifier.padding(0.dp),
            border = BorderStroke(2.dp, colorResource(id = R.color.red))
        ) {
            Text(
                "Delete Account Permanently",
                style = sizing.dangerZoneTextStyle,
                color = colorResource(id = R.color.red)
            )
        }
        Spacer(Modifier.height(sizing.dangerZoneSpacerHeight))
        HorizontalDivider(
            color = colorResource(id = R.color.red).copy(alpha = 0.7f), thickness = 4.dp
        )
    }
}

@Composable
fun SettingsButton(
    text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier, textStyle: TextStyle
) {
    Button(
        onClick = onClick, colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent
        ), modifier = modifier
            .fillMaxWidth(0.85f)
            .heightIn(min = 48.dp)
            .border(
                2.dp, colorResource(id = R.color.purple_accent), RoundedCornerShape(8.dp)
            )
            .background(
                Color.White.copy(alpha = 0.35f), RoundedCornerShape(8.dp)
            )
    ) {
        Text(
            text = text, color = colorResource(id = R.color.teal_900), style = textStyle
        )
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Icon(
            imageVector = icon,
            contentDescription = text,
            modifier = Modifier.size(ButtonDefaults.IconSize + 8.dp),
            tint = colorResource(id = R.color.purple_accent)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyOutlinedBufferInputField(
    onValueChange: (Int) -> Unit,
    bufferTimeLength: MutableIntState,
    isMaxExceeded: MutableState<Boolean>,
    isNull: MutableState<Boolean>,
    modifier: Modifier,
    textStyle: TextStyle
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current

    BasicTextField(
        value = bufferTimeLength.intValue.toString(),
        singleLine = true,
        interactionSource = interactionSource,
        cursorBrush = SolidColor(Color.White),
        onValueChange = { userInput: String ->
            val filteredInput = userInput.filter { it.isDigit() }
            if (filteredInput.isEmpty()) {
                onValueChange(0)
            } else {
                val parsedValue = filteredInput.toIntOrNull()
                if (parsedValue != null) {
                    if (parsedValue > 1_000_000) {
                        isMaxExceeded.value = true
                        return@BasicTextField
                    }
                    onValueChange(parsedValue)
                } else {
                    onValueChange(0)
                }
            }
        },
        modifier = modifier
            .fillMaxWidth(0.85f)
            .padding(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 0.dp)
            .onFocusChanged {
                if (!it.isFocused) {
                    Timber.v("Focus lost")
                }
            },
        textStyle = TextStyle(
            color = colorResource(id = R.color.teal_900),
            fontWeight = FontWeight.Medium,
            fontSize = textStyle.fontSize
        ),
        keyboardOptions = KeyboardOptions.Default.copy(
            imeAction = ImeAction.Done, keyboardType = KeyboardType.Number
        ),
        keyboardActions = KeyboardActions(onDone = {
            focusManager.clearFocus()
        }),
    ) { innerTextField ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = bufferTimeLength.intValue.toString(),
            innerTextField = innerTextField,
            enabled = true,
            singleLine = true,
            interactionSource = interactionSource,
            visualTransformation = VisualTransformation.None,
            label = {
                Text(
                    stringResource(id = R.string.buffer_length_seconds),
                    color = colorResource(id = R.color.purple_accent),
                    fontSize = textStyle.fontSize * 0.8f
                )
            },
            container = {
                OutlinedTextFieldDefaults.Container(
                    enabled = true,
                    isError = isNull.value || isMaxExceeded.value,
                    interactionSource = interactionSource,
                    colors = appTextFieldColors(),
                    shape = RoundedCornerShape(8.dp),
                    focusedBorderThickness = 4.dp,
                    unfocusedBorderThickness = 2.dp
                )
            })
    }
}

@Composable
fun StyledDropdownMenuItem(
    text: String, onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                color = colorResource(id = R.color.teal_900),
                style = MaterialTheme.typography.bodyLarge
            )
        }, onClick = onClick, modifier = Modifier.background(colorResource(id = R.color.teal_100))
    )
}

// Data class for the final combined estimate
data class ImpactEstimate(
    val impactLabel: String,
    val impactColor: Color,
    val qualityLabel: String,
    val qualityColor: Color
)

// Data classes for the intermediate calculations
private data class BatteryImpact(val label: String, val color: Color)
private data class QualityEstimate(val label: String, val color: Color)

/**
 * Estimates the impact of audio settings on both battery life and sound quality.
 */
private fun estimateAudioImpact(
    sampleRate: Int, bitDepth: BitDepth, bufferTimeLength: Int, isAiAutoClipEnabled: Boolean
): ImpactEstimate {
    var vadUsageFactor = 1f
    if (isAiAutoClipEnabled) {
        vadUsageFactor = AI_ENABLED_EXTRA_MEMORY_USAGE_FRACTION
    }
    val ramUsageMB =
        (sampleRate.toLong() * bufferTimeLength * (bitDepth.bits / 8)) * vadUsageFactor / (1_000_000)
    val batteryImpact = getBatteryImpact(sampleRate, bitDepth, ramUsageMB.toLong())
    val qualityEstimate = getQualityLabel(sampleRate, bitDepth)
    return ImpactEstimate(
        impactLabel = batteryImpact.label,
        impactColor = batteryImpact.color,
        qualityLabel = qualityEstimate.label,
        qualityColor = qualityEstimate.color
    )
}

/**
 * Calculates the estimated battery drain based on hardware usage.
 */
// --- CHANGE: Tuned the battery impact scoring for a more linear scale ---
private fun getBatteryImpact(sampleRate: Int, bitDepth: BitDepth, ramUsageMb: Long): BatteryImpact {
    // Sample rate is the biggest factor. Scale it relative to a baseline (e.g., 8kHz).
    val sampleRateScore = (sampleRate / 8000f).coerceIn(1f, 12f) // Cap at 12x for 96kHz+

    // RAM usage is the next biggest factor. Scale it relative to a baseline (e.g., 25MB).
    val ramScore = (ramUsageMb / 25f).coerceIn(1f, 6f) // Cap at 6x for 150MB+

    // Bit depth has a minor, almost negligible effect.
    val bitDepthScore = if (bitDepth.bits == 8) 0.8f else 1.0f

    val totalScore = sampleRateScore * ramScore * bitDepthScore

    return when {
        totalScore < 5f -> BatteryImpact("Low", Color(0xFF388E3C))        // Green
        totalScore < 15f -> BatteryImpact("Medium", Color(0xFFF57C00))   // Orange
        else -> BatteryImpact("High", Color(0xFFD32F2F))                  // Red
    }
}

/**
 * Calculates the estimated sound quality, returning a label and a corresponding color.
 */
private fun getQualityLabel(sampleRate: Int, bitDepth: BitDepth): QualityEstimate {
    val highQualityColor = Color(0xFF388E3C)
    val decentQualityColor = Color(0xFF7B8E38)
    val poorQualityColor = Color(0xFFF57C00)

    val bitDepthScore = if (bitDepth.bits == 8) 2f else 10f
    val sampleRateScore = when {
        sampleRate <= 16000 -> 2f
        sampleRate <= 22050 -> 5f
        sampleRate <= 48000 -> 10f
        else -> 12f
    }

    return when (bitDepthScore + sampleRateScore) {
        in 0f..<6f -> QualityEstimate("Poor\n(Phone)", poorQualityColor)
        in 6f..<15f -> QualityEstimate("Decent\n(Radio)", decentQualityColor)
        in 15f..<22f -> QualityEstimate("Great\n(CD)", highQualityColor)
        else -> QualityEstimate("Studio\n", highQualityColor)
    }
}

private data class Preset(
    val title: String,
    val description: String,
    val sampleRate: Int,
    val bitDepth: BitDepth,
    val bufferLength: Int,
    val aiEnabled: Boolean
)

private val commonPresets = listOf(
    Preset(
        title = "🔋 Battery Saver (Voice Notes)",
        description = "Best for recording lectures or voice memos with maximum efficiency.",
        sampleRate = 8000,
        bitDepth = bitDepths["16"]!!,
        bufferLength = 300,
        aiEnabled = true
    ), Preset(
        title = "⚖️ Balanced (Everyday Use)",
        description = "The recommended setting for great quality and performance. Perfect for capturing unexpected moments clearly.",
        sampleRate = 16000,
        bitDepth = bitDepths["16"]!!,
        bufferLength = 1800,
        aiEnabled = true
    ), Preset(
        title = "🎧 High Quality (Music / Detail)",
        description = "For capturing music or detailed environmental sounds with the highest fidelity. Note: this uses significantly more resources.",
        sampleRate = 48000,
        bitDepth = bitDepths["16"]!!,
        bufferLength = 600,
        aiEnabled = false
    )
)

@Composable
private fun ComprehensiveHelpDialog(
    sampleRate: Int,
    bitDepth: BitDepth,
    bufferTimeLength: Int,
    isAiAutoClipEnabled: Boolean,
    onDismissRequest: () -> Unit,
    onPresetClick: (Preset) -> Unit
) {
    val (formattedRamUsage, estimate) = remember(
        sampleRate, bitDepth, bufferTimeLength, isAiAutoClipEnabled
    ) {
        val bytesPerSample = bitDepth.bits / 8
        val totalBytes = sampleRate.toLong() * bufferTimeLength * bytesPerSample
        val megabytes = totalBytes / (1024.0 * 1024.0)
        val ram = String.format(Locale.getDefault(), "~%.0f", megabytes)
        Pair(ram, estimateAudioImpact(sampleRate, bitDepth, bufferTimeLength, isAiAutoClipEnabled))
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = colorResource(id = R.color.teal_100),
        title = {
            Text(
                "Settings Explained",
                fontWeight = FontWeight.Bold,
                color = colorResource(id = R.color.teal_900)
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // --- Section 1: Estimates ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top, // Align content to the top
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    // --- Column 1: RAM Usage ---
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "RAM Usage [MB]",
                            style = MaterialTheme.typography.labelMedium,
                            color = colorResource(id = R.color.teal_900).copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = formattedRamUsage,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = colorResource(id = R.color.teal_900),
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // --- Column 2: Battery Impact ---
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Battery Impact",
                            style = MaterialTheme.typography.labelMedium,
                            color = colorResource(id = R.color.teal_900).copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = estimate.impactLabel,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = estimate.impactColor,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // --- Column 3: Sound Quality ---
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Sound Quality",
                            style = MaterialTheme.typography.labelMedium,
                            color = colorResource(id = R.color.teal_900).copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = estimate.qualityLabel,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = estimate.qualityColor,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Text(
                    text = "*Estimates are relative. Actual device performance will vary.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontStyle = FontStyle.Italic,
                        hyphens = Hyphens.Auto,
                        textAlign = TextAlign.Justify,
                        lineBreak = LineBreak.Paragraph,
                    ),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(),
                    color = colorResource(id = R.color.teal_900).copy(alpha = 0.7f)
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = colorResource(id = R.color.purple_accent).copy(alpha = 0.5f)
                )
                // --- Section 2, Common Presets ---

                Text(
                    "Common Presets",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = colorResource(id = R.color.teal_900)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Loop through presets and display them as clickable cards
                commonPresets.forEach { preset ->
                    val isSelected = preset.sampleRate == sampleRate &&
                            preset.bitDepth == bitDepth &&
                            preset.bufferLength == bufferTimeLength &&
                            preset.aiEnabled == isAiAutoClipEnabled

                    PresetItem(
                        preset = preset,
                        isSelected = isSelected,
                        onClick = { onPresetClick(preset) }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = colorResource(id = R.color.purple_accent).copy(alpha = 0.5f)
                )

                // --- Section 3: Detailed Explanations ---
                Text(
                    "Explanation of values",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = colorResource(id = R.color.teal_900)
                )
                Spacer(modifier = Modifier.height(12.dp))
                val textColor = colorResource(id = R.color.teal_900)
                val headingColor = colorResource(id = R.color.purple_accent)

                Text(
                    "Sample Rate (Hz)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = headingColor
                )
                Text(
                    "How many times per second the microphone listens. Higher values mean clearer, higher-fidelity sound (like more frames-per-second in a video). Lower values use less battery.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        hyphens = Hyphens.Auto,
                        textAlign = TextAlign.Justify,
                        lineBreak = LineBreak.Paragraph,
                    ),
                    color = textColor
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Bit Depth (bits)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = headingColor
                )
                Text(
                    "The amount of detail in each audio sample. Higher bit depth provides a wider dynamic range (difference between loud and quiet sounds) and a cleaner recording.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        hyphens = Hyphens.Auto,
                        textAlign = TextAlign.Justify,
                        lineBreak = LineBreak.Paragraph,
                    ),
                    color = textColor
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Buffer Length (seconds)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = headingColor
                )
                Text(
                    "The total duration of audio the app keeps in memory (RAM). A longer buffer requires significantly more RAM and can increase battery drain.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        hyphens = Hyphens.Auto,
                        textAlign = TextAlign.Justify,
                        lineBreak = LineBreak.Paragraph,
                    ),
                    color = textColor
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = colorResource(id = R.color.purple_accent).copy(alpha = 0.5f)
                )
            }
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

// A composable for a single, clickable preset item
@Composable
private fun PresetItem(preset: Preset, isSelected: Boolean, onClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        label = "PresetScaleAnimation"
    )

    val selectedBorderColor = Color(0xFF388E3C) // A nice, complimentary green
    val defaultBorderColor = colorResource(id = R.color.purple_accent)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        try {
                            awaitRelease()
                        } finally {
                            // This always runs, ensuring the button returns to its normal state
                            isPressed = false
                        }
                    },
                    onTap = { onClick() }
                )
            },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.teal_150)),
        border = BorderStroke(
            width = if (isSelected) 2.5.dp else 1.dp,
            color = if (isSelected) selectedBorderColor else defaultBorderColor
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = preset.title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = colorResource(id = R.color.purple_accent)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = preset.description,
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(id = R.color.teal_900)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• Sample Rate: ${preset.sampleRate} Hz\n" + "• Bit Depth: ${preset.bitDepth.bits}-bit\n" + "• Buffer Length: ${preset.bufferLength}s (${preset.bufferLength / 60} min)\n" + "• AI Trimming Enabled: ${if (preset.aiEnabled) "Yes" else "No"}",
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(id = R.color.teal_700),
                modifier = Modifier.padding(start = 8.dp),
                lineHeight = 20.sp
            )
        }
    }
}

@SuppressLint("UnrememberedMutableState")
@Preview(
    showBackground = true,
    name = "Typical Phone Portrait - Signed In",
)
@Composable
fun SettingsScreenTypicalPhonePortraitSignedInPreview() {
    RecentAudioBufferTheme {
        SettingsScreenContent(
            state = mutableStateOf(SettingsScreenState(SettingsConfig())),
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Medium,
            isUserSignedIn = true,
            isPreview = true,
            onDeleteAccountClick = {},
            onSampleRateChanged = {},
            onBitDepthChanged = {},
            onBufferTimeLengthChanged = {},
            onAiAutoClipChanged = {},
            onSubmit = {},
            justExit = {})
    }
}

@SuppressLint("UnrememberedMutableState")
@Preview(
    showBackground = true,
    name = "Phone Portrait - Signed In",
    device = "spec:width=360dp,height=625dp,dpi=480"
)
@Composable
fun SettingsScreenPhonePortraitSignedInPreview() {
    RecentAudioBufferTheme {
        SettingsScreenContent(
            state = mutableStateOf(SettingsScreenState(SettingsConfig())),
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Medium,
            isUserSignedIn = true,
            isPreview = true,
            onDeleteAccountClick = {},
            onSampleRateChanged = {},
            onBitDepthChanged = {},
            onBufferTimeLengthChanged = {},
            onAiAutoClipChanged = {},
            onSubmit = {},
            justExit = {})
    }
}

@SuppressLint("UnrememberedMutableState")
@Preview(
    showBackground = true,
    name = "Phone Portrait - Signed Out",
    device = "spec:width=360dp,height=625dp,dpi=480"
)
@Composable
fun SettingsScreenPhonePortraitSignedOutPreview() {
    RecentAudioBufferTheme {
        SettingsScreenContent(
            state = mutableStateOf(SettingsScreenState(SettingsConfig())),
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Medium,
            isUserSignedIn = false,
            isPreview = true,
            onDeleteAccountClick = {},
            onSampleRateChanged = {},
            onBitDepthChanged = {},
            onBufferTimeLengthChanged = {},
            onAiAutoClipChanged = {},
            onSubmit = {},
            justExit = {})
    }
}

@SuppressLint("UnrememberedMutableState")
@Preview(
    showBackground = true,
    name = "Landscape (Tablet)",
    device = "spec:width=1920dp,height=1080dp,dpi=320"
)
@Composable
fun SettingsScreenLandscapeTabletPreview() {
    RecentAudioBufferTheme {
        SettingsScreenContent(
            state = mutableStateOf(SettingsScreenState(SettingsConfig())),
            widthSizeClass = WindowWidthSizeClass.Expanded,
            heightSizeClass = WindowHeightSizeClass.Medium,
            isUserSignedIn = true,
            onDeleteAccountClick = {},
            onSampleRateChanged = {},
            isPreview = true,
            onBitDepthChanged = {},
            onBufferTimeLengthChanged = {},
            onAiAutoClipChanged = {},
            onSubmit = {},
            justExit = {})
    }
}

@SuppressLint("UnrememberedMutableState")
@Preview(
    showBackground = true,
    name = "Portrait (Tablet)",
    device = "spec:width=800dp,height=1280dp,dpi=480"
)
@Composable
fun SettingsScreenPortaitTabletPreview() {
    RecentAudioBufferTheme {
        SettingsScreenContent(
            state = mutableStateOf(SettingsScreenState(SettingsConfig())),
            widthSizeClass = WindowWidthSizeClass.Medium,
            heightSizeClass = WindowHeightSizeClass.Expanded,
            isUserSignedIn = true,
            onDeleteAccountClick = {},
            onSampleRateChanged = {},
            isPreview = true,
            onBitDepthChanged = {},
            onBufferTimeLengthChanged = {},
            onAiAutoClipChanged = {},
            onSubmit = {},
            justExit = {})
    }
}

@SuppressLint("UnrememberedMutableState")
@Preview(
    showBackground = true,
    name = "Phone Landscape (16:9)",
    device = "spec:width=640dp,height=360dp,dpi=480" // 16:9 aspect ratio for a phone
)
@Composable
fun SettingsScreenPhoneLandscapePreview() {
    RecentAudioBufferTheme {
        SettingsScreenContent(
            state = mutableStateOf(SettingsScreenState(SettingsConfig())),
            widthSizeClass = WindowWidthSizeClass.Medium,
            heightSizeClass = WindowHeightSizeClass.Compact,
            isUserSignedIn = true,
            onDeleteAccountClick = {},
            onSampleRateChanged = {},
            isPreview = true,
            onBitDepthChanged = {},
            onBufferTimeLengthChanged = {},
            onAiAutoClipChanged = {},
            onSubmit = {},
            justExit = {})
    }
}

@SuppressLint("UnrememberedMutableState")
@Preview(
    showBackground = true,
    device = "spec:width=340dp,height=6360dp,dpi=480" // 16:9 aspect ratio for a phone
)
@Composable
fun ComprehensiveHelpDialogPreview() {
    ComprehensiveHelpDialog(22050, bitDepths["8"]!!, 2000, true, {}, {})
}