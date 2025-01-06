package com.mfc.recentaudiobuffer

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.focus.FocusDirection

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun SettingsScreen(
    config: AudioConfig,
    onSampleRateChanged: (Int) -> Unit,
    onBitDepthChanged: (BitDepth) -> Unit,
    onBufferTimeLengthChanged: (Int) -> Unit,
    onSubmit: () -> Unit
) {
    var showSampleRateMenu by remember { mutableStateOf(false) }
    var showBitDepthMenu by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    var isFirstTime by remember { mutableStateOf(true) } // Because collectAsState is async the screen can render before it finishes
    var bufferTimeLength by remember { mutableIntStateOf(config.BUFFER_TIME_LENGTH_S) }

    LaunchedEffect(config.BUFFER_TIME_LENGTH_S) {
        if (!isFirstTime) {
            bufferTimeLength = config.BUFFER_TIME_LENGTH_S
        } else {
            isFirstTime = false
        }
    }

    Scaffold(containerColor = colorResource(id = R.color.teal_100),
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures {
                focusManager.clearFocus()
            }
        }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .padding(16.dp)
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(12.dp))
                .border(
                    4.dp, colorResource(id = R.color.purple_accent), RoundedCornerShape(12.dp)
                )
                .background(
                    colorResource(id = R.color.teal_200), RoundedCornerShape(12.dp)
                )
                .padding(16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null
                ) {
                    focusManager.clearFocus()
                }, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Sample Rate
            SettingsButton(text = "Sample Rate: ${config.SAMPLE_RATE_HZ} Hz",
                icon = Icons.Filled.ArrowDropDown,
                onClick = { showSampleRateMenu = true })
            DropdownMenu(
                expanded = showSampleRateMenu,
                onDismissRequest = { showSampleRateMenu = false },
                modifier = Modifier
                    .background(colorResource(id = R.color.teal_350))
                    .border(
                        width = 2.dp,
                        color = colorResource(id = R.color.purple_accent),
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                sampleRates.forEach { (label, value) ->
                    StyledDropdownMenuItem(text = "$label Hz", onClick = {
                        Log.i(
                            "SettingsScreen", "Clicked SampleRate $label with value: $value"
                        )
                        onSampleRateChanged(value)
                        showSampleRateMenu = false
                    })
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bit Depth
            SettingsButton(text = "Bit Depth: ${config.BIT_DEPTH.bytes} bit",
                icon = Icons.Filled.ArrowDropDown,
                onClick = { showBitDepthMenu = true })
            DropdownMenu(
                expanded = showBitDepthMenu,
                onDismissRequest = { showBitDepthMenu = false },
                modifier = Modifier
                    .background(colorResource(id = R.color.teal_350))
                    .border(
                        width = 2.dp,
                        color = colorResource(id = R.color.purple_accent),
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                bitDepths.forEach { (label, value) ->
                    StyledDropdownMenuItem(text = "$label bit", onClick = {
                        Log.i(
                            "SettingsScreen", "Clicked BitDepth $label with value: $value"
                        )
                        onBitDepthChanged(value)
                        showBitDepthMenu = false
                    })
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            MyOutlinedBufferInputField(
                bufferTimeLength = bufferTimeLength,
                onBufferTimeLengthChanged = onBufferTimeLengthChanged
            )

            Spacer(modifier = Modifier.height(16.dp))

            MainButton(
                text = stringResource(id = R.string.submit),
                icon = R.drawable.baseline_save_alt_24,
                onClick = {
                    onSubmit()
                },
                iconTint = colorResource(id = R.color.purple_accent),
                width = 130.dp
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
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
                colorResource(id = R.color.teal_350), RoundedCornerShape(8.dp)
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
    bufferTimeLength: Int, onBufferTimeLengthChanged: (Int) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current

    BasicTextField(
        value = bufferTimeLength.toString(),
        singleLine = true,
        interactionSource = interactionSource,
        cursorBrush = SolidColor(Color.White),
        onValueChange = { userInput: String ->
            if (userInput.isEmpty()) {
                // Allow complete deletion
                onBufferTimeLengthChanged(1)
            } else {
                val parsedValue = userInput.trim().toIntOrNull()
                if (parsedValue != null) {
                    onBufferTimeLengthChanged(parsedValue)
                }
                // Consider adding an error message if the input is invalid
            }
        },
        modifier = Modifier
            .width(220.dp)
            .padding(start = 20.dp, top = 0.dp, end = 20.dp, bottom = 0.dp)
            .onFocusChanged {
                if (!it.isFocused) {
                }
            },
        textStyle = TextStyle(
            color = colorResource(id = R.color.teal_900), fontWeight = FontWeight.Medium
        ),
        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            focusManager.moveFocus(FocusDirection.Down)
        }),
    ) { innerTextField ->
        OutlinedTextFieldDefaults.DecorationBox(value = bufferTimeLength.toString(),
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
                OutlinedTextFieldDefaults.ContainerBox(
                    enabled = true,
                    isError = false,
                    interactionSource = interactionSource,
                    colors = TextFieldDefaults.colors(
                        unfocusedIndicatorColor = colorResource(id = R.color.purple_accent),
                        focusedIndicatorColor = colorResource(id = R.color.purple_accent),
                        unfocusedContainerColor = colorResource(id = R.color.teal_350),
                        focusedContainerColor = colorResource(id = R.color.teal_200),
                        errorContainerColor = Color.Red,
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

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    RecentAudioBufferTheme {
        SettingsScreen(AudioConfig(44100, 10, bitDepths["16"]!!), {}, {}, {}, {})
    }
}
