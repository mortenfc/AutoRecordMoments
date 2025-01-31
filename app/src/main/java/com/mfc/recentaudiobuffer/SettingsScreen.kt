package com.mfc.recentaudiobuffer

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.input.KeyboardType

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun SettingsScreen(
    signInButtonText: MutableState<String>,
    onSignInClick: () -> Unit,
    sampleRate: Int,
    bitDepth: BitDepth,
    bufferTimeLengthTemp: MutableIntState,
    isMaxExceeded: MutableState<Boolean>,
    isBufferTimeLengthNull: MutableState<Boolean>,
    errorMessage: MutableState<String?>,
    isSubmitEnabled: MutableState<Boolean>,
    onSampleRateChanged: (Int) -> Unit,
    onBitDepthChanged: (BitDepth) -> Unit,
    onBufferTimeLengthChanged: (Int) -> Unit,
    onSubmit: (Int) -> Unit,
    justExit: () -> Unit,
    config: SettingsConfig
) {
    var showSampleRateMenu by remember { mutableStateOf(false) }
    var showBitDepthMenu by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Force recomposition when config changes
    LaunchedEffect(config) {
        Log.d("SettingsScreen", "LaunchedEffect config: $config")
    }

    Log.d("SettingsScreen", "recompose")

    Scaffold(containerColor = colorResource(id = R.color.teal_100),
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures {
                focusManager.clearFocus()
            }
        },
        topBar = {
            TopAppBar(
                title = stringResource(id = R.string.donate),
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
            SettingsButton(text = "Sample Rate: $sampleRate Hz",
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
            SettingsButton(text = "Bit Depth: ${bitDepth.bytes} bit",
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
                bufferTimeLength = bufferTimeLengthTemp,
                onValueChange = onBufferTimeLengthChanged,
                isMaxExceeded = isMaxExceeded,
                isNull = isBufferTimeLengthNull
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Error Message
            if (errorMessage.value != null) {
                Text(text = errorMessage.value!!, color = Color.Red)
                Spacer(modifier = Modifier.height(16.dp))
            }

            MainButton(
                text = stringResource(id = R.string.submit),
                icon = R.drawable.baseline_save_alt_24,
                onClick = {
                    if (isSubmitEnabled.value) {
                        onSubmit(bufferTimeLengthTemp.intValue)
                    }
                },
                iconTint = colorResource(id = R.color.purple_accent),
                width = 130.dp,
                enabled = isSubmitEnabled.value
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
    onValueChange: (Int) -> Unit,
    bufferTimeLength: MutableIntState,
    isMaxExceeded: MutableState<Boolean>,
    isNull: MutableState<Boolean>
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    Log.d("MyOutlinedBufferInputField", "recompose")

    BasicTextField(
        value = bufferTimeLength.intValue.toString(),
        singleLine = true,
        interactionSource = interactionSource,
        cursorBrush = SolidColor(Color.White),
        onValueChange = { userInput: String ->
            Log.d("MyOutlinedBufferInputField", "onValueChange to $userInput")
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
                    Log.d("MyOutlinedBufferInputField", "parsedValue: $parsedValue")
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
                    Log.v("SettingsScreen", "Focus lost")
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
        OutlinedTextFieldDefaults.DecorationBox(value = bufferTimeLength.intValue.toString(),
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
                        unfocusedContainerColor = colorResource(id = R.color.teal_350),
                        focusedContainerColor = colorResource(id = R.color.teal_200),
                        errorContainerColor = colorResource(id = R.color.teal_200),
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

@SuppressLint("UnrememberedMutableState")
@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    RecentAudioBufferTheme {
        SettingsScreen(
            signInButtonText = mutableStateOf("Sign In"),
            {},
            DEFAULT_SAMPLE_RATE,
            bitDepths[DEFAULT_BIT_DEPTH_KEY]!!,
            mutableIntStateOf(DEFAULT_BUFFER_TIME_LENGTH_S),
            mutableStateOf(false),
            mutableStateOf(false),
            mutableStateOf(""),
            mutableStateOf(true),
            {},
            {},
            {},
            {},
            {},
            SettingsConfig())
    }
}
