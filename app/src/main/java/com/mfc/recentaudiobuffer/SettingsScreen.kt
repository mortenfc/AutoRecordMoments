package com.mfc.recentaudiobuffer

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mfc.recentaudiobuffer.ui.theme.RecentAudioBufferTheme
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import timber.log.Timber

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun SettingsScreen(
    state: MutableState<SettingsScreenState>,
    signInButtonText: MutableState<String>,
    onSignInClick: () -> Unit,
    onSampleRateChanged: (Int) -> Unit,
    onBitDepthChanged: (BitDepth) -> Unit,
    onBufferTimeLengthChanged: (Int) -> Unit,
    onAiAutoClipChanged: (Boolean) -> Unit,
    onSubmit: (Int) -> Unit,
    justExit: () -> Unit
) {
    var showSampleRateMenu by remember { mutableStateOf(false) }
    var showBitDepthMenu by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current

    val sampleRate = state.value.sampleRateTemp
    val bitDepth = state.value.bitDepthTemp
    val bufferTimeLengthTemp = state.value.bufferTimeLengthTemp
    val isMaxExceeded = state.value.isMaxExceeded
    val isBufferTimeLengthNull = state.value.isBufferTimeLengthNull
    val errorMessage = state.value.errorMessage
    val isSubmitEnabled = state.value.isSubmitEnabled

    Timber.d("Recompose")

    Scaffold(
        containerColor = colorResource(id = R.color.teal_100),
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures {
                focusManager.clearFocus()
            }
        },
        topBar = {
            TopAppBar(
                title = stringResource(id = R.string.settings),
                signInButtonText = signInButtonText,
                onSignInClick = onSignInClick,
                onBackButtonClicked = {
                    if (isSubmitEnabled.value) {
                        onSubmit(bufferTimeLengthTemp.intValue)
                    } else {
                        justExit()
                    }
                })
        }) { innerPadding ->
        // The Box is now the single, styled container. It acts as our "card".
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding) // 1. Apply padding from Scaffold (for the TopAppBar)
                .padding(16.dp)       // 2. Apply margin around the card
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
            // The Column ONLY organizes the content inside the Box. It has no style of its own.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp) // 3. Apply padding for the content INSIDE the card
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { focusManager.clearFocus() }),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Sample Rate
                SettingsButton(
                    text = "Sample Rate: ${sampleRate.value} Hz",
                    icon = Icons.Filled.ArrowDropDown,
                    onClick = { showSampleRateMenu = true })
                DropdownMenu(
                    expanded = showSampleRateMenu,
                    onDismissRequest = { showSampleRateMenu = false },
                    modifier = Modifier
                        .background(colorResource(id = R.color.teal_100))
                        .border(
                            width = 2.dp,
                            color = colorResource(id = R.color.purple_accent),
                            shape = RoundedCornerShape(8.dp)
                        )
                ) {
                    sampleRates.forEach { (label, value) ->
                        StyledDropdownMenuItem(text = "$label Hz", onClick = {
                            Timber.i(
                                "Clicked SampleRate $label with value: $value"
                            )
                            onSampleRateChanged(value)
                            showSampleRateMenu = false
                        })
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bit Depth
                SettingsButton(
                    text = "Bit Depth: ${bitDepth.value.bits} bit",
                    icon = Icons.Filled.ArrowDropDown,
                    onClick = { showBitDepthMenu = true })
                DropdownMenu(
                    expanded = showBitDepthMenu,
                    onDismissRequest = { showBitDepthMenu = false },
                    modifier = Modifier
                        .background(colorResource(id = R.color.teal_100))
                        .border(
                            width = 2.dp,
                            color = colorResource(id = R.color.purple_accent),
                            shape = RoundedCornerShape(8.dp)
                        )
                ) {
                    bitDepths.forEach { (label, value) ->
                        StyledDropdownMenuItem(text = "$label bit", onClick = {
                            Timber.i(
                                "Clicked BitDepth $label with value: $value"
                            )
                            onBitDepthChanged(value)
                            showBitDepthMenu = false
                        })
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                MyOutlinedBufferInputField(
                    bufferTimeLength = bufferTimeLengthTemp,
                    onValueChange = onBufferTimeLengthChanged,
                    isMaxExceeded = isMaxExceeded,
                    isNull = isBufferTimeLengthNull
                )

                Spacer(modifier = Modifier.height(10.dp))

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = colorResource(id = R.color.purple_accent).copy(alpha = 0.5f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(fraction = 0.9f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "AI Auto-Trimming",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colorResource(id = R.color.teal_900)
                    )
                    Switch(
                        checked = state.value.isAiAutoClipEnabled.value, // Get value from state
                        onCheckedChange = { isEnabled ->
                            onAiAutoClipChanged(isEnabled) // New callback
                        }, colors = SwitchDefaults.colors(
                            checkedThumbColor = colorResource(id = R.color.purple_accent),
                            checkedBorderColor = colorResource(id = R.color.teal_350),
                            checkedTrackColor = colorResource(id = R.color.teal_350),
                            uncheckedThumbColor = colorResource(id = R.color.purple_accent),
                            uncheckedTrackColor = colorResource(id = R.color.teal_100),
                            uncheckedBorderColor = colorResource(id = R.color.teal_350),
                        ),
                    )
                }
                Text(
                    text = "When enabled, saving the buffer will automatically trim away all non-speech (including music).\n" + "WARNING: This resamples down to 16 kHz and can take some time to run for really long buffers.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        textAlign = TextAlign.Justify,
                        hyphens = Hyphens.Auto,
                        lineBreak = LineBreak.Paragraph,
                        fontStyle = FontStyle.Italic
                    ),
                    color = colorResource(id = R.color.teal_700),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth(fraction = 0.9f),
                )
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = colorResource(id = R.color.purple_accent).copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Error Message
                if (errorMessage.value != null) {
                    Text(text = errorMessage.value!!, color = Color.Red)
                    Spacer(modifier = Modifier.height(16.dp))
                }

                MainButton(
                    text = stringResource(id = R.string.submit),
                    icon = R.drawable.outline_restore_page_24,
                    onClick = {
                        if (isSubmitEnabled.value) {
                            onSubmit(bufferTimeLengthTemp.intValue)
                        }
                    },
                    iconTint = colorResource(id = R.color.purple_accent),
                    enabled = isSubmitEnabled.value,
                    width = 200.dp,
                    maxLines = 2,
                    iconSize = 40.dp
                )

                Text(
                    text = "Sign In to sync settings with cloud",
                    color = colorResource(id = R.color.purple_accent),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    fontStyle = FontStyle.Italic
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            IconButton(
                onClick = { showHelpDialog = true },
                modifier = Modifier
                    .align(Alignment.TopEnd) // Positions it at the top-right
                    .padding(top = 4.dp, end = 4.dp) // Adds padding so it's not on the edge
                    .zIndex(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Show settings help",
                    tint = colorResource(id = R.color.purple_accent)
                )
            }
        }
    }

    if (showHelpDialog) {
        ComprehensiveHelpDialog(
            sampleRate = sampleRate.value,
            bitDepth = bitDepth.value,
            bufferTimeLength = bufferTimeLengthTemp.intValue,
            onDismissRequest = { showHelpDialog = false })
    }
}

@Composable
fun SettingsButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick, colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent
        ), modifier = Modifier
            .border(
                2.dp, colorResource(id = R.color.purple_accent), RoundedCornerShape(8.dp)
            )
            .background(
                colorResource(id = R.color.teal_100), RoundedCornerShape(8.dp)
            )
    ) {
        Text(
            text = text,
            color = colorResource(id = R.color.teal_900),
            style = MaterialTheme.typography.bodyLarge
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
    isNull: MutableState<Boolean>
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    Timber.d("recompose")

    BasicTextField(
        value = bufferTimeLength.intValue.toString(),
        singleLine = true,
        interactionSource = interactionSource,
        cursorBrush = SolidColor(Color.White),
        onValueChange = { userInput: String ->
            Timber.d("onValueChange to $userInput")
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
                    Timber.d("parsedValue: $parsedValue")
                    onValueChange(parsedValue)
                } else {
                    onValueChange(0)
                }
            }
        },
        modifier = Modifier
            .width(220.dp)
            .padding(start = 20.dp, top = 0.dp, end = 20.dp, bottom = 0.dp)
            .onFocusChanged {
                if (!it.isFocused) {
                    Timber.v("Focus lost")
                }
            },
        textStyle = TextStyle(
            color = colorResource(id = R.color.teal_900), fontWeight = FontWeight.Medium
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
                    color = colorResource(id = R.color.purple_accent)
                )
            },
            container = {
                OutlinedTextFieldDefaults.Container(
                    enabled = true,
                    isError = isNull.value || isMaxExceeded.value,
                    interactionSource = interactionSource,
                    colors = TextFieldDefaults.colors(
                        unfocusedIndicatorColor = colorResource(id = R.color.purple_accent),
                        focusedIndicatorColor = colorResource(id = R.color.purple_accent),
                        unfocusedContainerColor = colorResource(id = R.color.teal_100),
                        focusedContainerColor = colorResource(id = R.color.teal_150),
                        errorContainerColor = colorResource(id = R.color.teal_150),
                        errorIndicatorColor = Color.Red
                    ),
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
        },
        onClick = onClick,
        colors = MenuDefaults.itemColors(
            textColor = colorResource(id = R.color.teal_900),
        ),
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
 *
 * @return An [ImpactEstimate] object containing separate labels for battery and quality.
 */
private fun estimateAudioImpact(
    sampleRate: Int, bitDepth: BitDepth, bufferTimeLength: Int
): ImpactEstimate {
    // Calculate RAM usage once, as it's a key factor for battery drain.
    val ramUsageMb = (sampleRate.toLong() * bufferTimeLength * (bitDepth.bits / 8)) / (1024 * 1024)

    // 1. Get the estimated battery impact, which includes a label and a color.
    val batteryImpact = getBatteryImpact(sampleRate, bitDepth, ramUsageMb)

    // 2. Get the estimated sound quality label, calculated independently.
    val qualityEstimate = getQualityLabel(sampleRate, bitDepth)

    // 3. Combine the results into the final data class for the UI.
    return ImpactEstimate(
        impactLabel = batteryImpact.label,
        impactColor = batteryImpact.color,
        qualityLabel = qualityEstimate.label,
        qualityColor = qualityEstimate.color
    )
}

/**
 * Calculates the estimated battery drain based on hardware usage.
 * - Sample Rate: High impact (CPU/mic usage).
 * - RAM/Processing: Medium impact (memory bus and processing overhead).
 * - Bit Depth: Low impact (minor increase in processing).
 */
private fun getBatteryImpact(sampleRate: Int, bitDepth: BitDepth, ramUsageMb: Long): BatteryImpact {
    val sampleRateScore = when {
        sampleRate <= 16000 -> 1.0f // Low
        sampleRate <= 22050 -> 2.0f // Medium
        sampleRate <= 48000 -> 4.0f // High
        else -> 6.0f              // Very High
    }
    // Bit depth has a much smaller effect on battery than on quality.
    val bitDepthScore = if (bitDepth.bits == 8) 0.5f else 1.0f

    val ramScore = when {
        ramUsageMb <= 50 -> 1.0f  // Low
        ramUsageMb <= 150 -> 2.5f // Medium
        else -> 4.0f              // High
    }

    return when (sampleRateScore + bitDepthScore + ramScore) {
        in 0f..<4.5f -> BatteryImpact("Low", Color(0xFF388E3C))        // Green
        in 4.5f..<7.5f -> BatteryImpact("Medium", Color(0xFFF57C00))   // Orange
        else -> BatteryImpact("High", Color(0xFFD32F2F))                  // Red
    }
}

/**
 * Calculates the estimated sound quality, returning a label and a corresponding color.
 */
private fun getQualityLabel(sampleRate: Int, bitDepth: BitDepth): QualityEstimate {
    // Define colors for quality levels
    val highQualityColor = Color(0xFF388E3C)   // Same green as low battery impact
    val decentQualityColor = Color(0xFF7B8E38) // Yellow-Green
    val poorQualityColor = Color(0xFFF57C00)   // Orange

    // Scoring logic
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

@Composable
fun ComprehensiveHelpDialog(
    sampleRate: Int, bitDepth: BitDepth, bufferTimeLength: Int, onDismissRequest: () -> Unit
) {
    // --- Calculations ---
    val (formattedRamUsage, estimate) = remember(sampleRate, bitDepth, bufferTimeLength) {
        val bytesPerSample = bitDepth.bits / 8
        val totalBytes = sampleRate.toLong() * bufferTimeLength * bytesPerSample
        val megabytes = totalBytes / (1024.0 * 1024.0)
        val ram = String.format("~%.0f", megabytes)

        // ✅ Return a Pair containing the RAM string and the full estimate object
        Pair(ram, estimateAudioImpact(sampleRate, bitDepth, bufferTimeLength))
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = colorResource(id = R.color.teal_100), // Light background
        title = {
            Text(
                "Settings Explained",
                fontWeight = FontWeight.Bold,
                color = colorResource(id = R.color.teal_900) // Dark text
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

                // --- Section 2: Detailed Explanations ---
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
                    "Bit Depth (bit)",
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

                // In ComprehensiveHelpDialog, after the last Text(...)
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = colorResource(id = R.color.purple_accent).copy(alpha = 0.5f)
                )

                Text(
                    "Example Presets",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = textColor
                )

                Spacer(modifier = Modifier.height(12.dp))

                // --- Battery Saver Preset ---
                Text(
                    "🔋 Battery Saver (Voice Notes)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = headingColor
                )
                Text(
                    "Best for recording lectures or voice memos with maximum efficiency.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        hyphens = Hyphens.Auto,
                        textAlign = TextAlign.Justify,
                        lineBreak = LineBreak.Paragraph,
                    ),
                    color = textColor
                )
                Text(
                    " • Sample Rate: 16000 Hz\n • Bit Depth: 16-bit\n • Buffer Length: 600s (10 min)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    modifier = Modifier.padding(start = 8.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // --- Balanced Preset ---
                Text(
                    "⚖️ Balanced (Everyday Use)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = headingColor
                )
                Text(
                    "The recommended setting for great quality and performance. Perfect for capturing unexpected moments clearly.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        hyphens = Hyphens.Auto,
                        textAlign = TextAlign.Justify,
                        lineBreak = LineBreak.Paragraph,
                    ),
                    color = textColor
                )
                Text(
                    " • Sample Rate: 22050 Hz\n • Bit Depth: 16-bit\n • Buffer Length: 300s (5 min)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    modifier = Modifier.padding(start = 8.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // --- High Quality Preset ---
                Text(
                    "🎧 High Quality (Music / Detail)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = headingColor
                )
                Text(
                    "For capturing music or detailed environmental sounds with the highest fidelity. Note: this uses significantly more resources.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        hyphens = Hyphens.Auto,
                        textAlign = TextAlign.Justify,
                        lineBreak = LineBreak.Paragraph,
                    ),
                    color = textColor
                )
                Text(
                    " • Sample Rate: 48000 Hz\n • Bit Depth: 16-bit\n • Buffer Length: 180s (3 min)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    modifier = Modifier.padding(start = 8.dp)
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

@SuppressLint("UnrememberedMutableState")
@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    RecentAudioBufferTheme {
        SettingsScreen(
            mutableStateOf(SettingsScreenState(SettingsConfig())),
            signInButtonText = mutableStateOf("Sign In"),
            {},
            {},
            {},
            {},
            {},
            {},
            {})
    }
}
