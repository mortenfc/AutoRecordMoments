package com.mfc.recentaudiobuffer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.util.Log
import android.util.TypedValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight.Companion.Bold
import androidx.compose.ui.text.font.FontWeight.Companion.W500
import androidx.compose.ui.text.font.FontWeight.Companion.W700
import androidx.compose.ui.text.font.FontWeight.Companion.W900
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.content.ContextCompat

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val activity = (LocalContext.current as? Activity) // Get the Activity

    val config by settingsViewModel.config.collectAsState()
    var showSampleRateMenu by remember { mutableStateOf(false) }
    var showBitDepthMenu by remember { mutableStateOf(false) }

    var bufferTimeLength by remember { mutableIntStateOf(config.BUFFER_TIME_LENGTH_S) }


    Scaffold(
        containerColor = Color(
            ContextCompat.getColor(
                context, R.color.teal_200
            )
        ) // Set page background color
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .border(
                    1.5.dp,
                    Color(ContextCompat.getColor(context, R.color.purple_accent)),
                    RoundedCornerShape(12.dp)
                )
                .background(
                    Color(ContextCompat.getColor(context, R.color.teal_100)),
                    RoundedCornerShape(12.dp)
                )
                .padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(modifier = Modifier.height(12.dp))

            // Sample Rate
            Button(
                onClick = { showSampleRateMenu = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent
                ),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .border(
                        1.5.dp,
                        Color(ContextCompat.getColor(context, R.color.purple_accent)),
                        RoundedCornerShape(20.dp)
                    )
                    .background(
                        Color(ContextCompat.getColor(context, R.color.teal_200)),
                        RoundedCornerShape(20.dp)
                    )
            ) {
                Text(
                    "Sample Rate: ${config.SAMPLE_RATE_HZ} Hz",
                    color = Color(ContextCompat.getColor(context, R.color.purple_accent))
                )

                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "Sample Rate",
                    modifier = Modifier.size(ButtonDefaults.IconSize + 8.dp)

                )
            }
            DropdownMenu(
                expanded = showSampleRateMenu,
                onDismissRequest = { showSampleRateMenu = false },
            ) {
                sampleRates.forEach { (label, value) ->
                    StyledDropdownMenuItem(text = "$label Hz", onClick = {
                        Log.i("SettingsScreen", "Clicked SampleRate $label with value: $value")
                        settingsViewModel.updateSampleRate(value)
                        showSampleRateMenu = false
                        val intent = Intent(context, MyBufferService::class.java)
                        intent.action = "com.mfc.recentaudiobuffer.SETTINGS_UPDATED"
                        context.sendBroadcast(intent)
                    })
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bit Depth
            Button(
                onClick = { showBitDepthMenu = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent
                ),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .border(
                        1.5.dp,
                        Color(ContextCompat.getColor(context, R.color.purple_accent)),
                        RoundedCornerShape(20.dp)
                    )
                    .background(
                        Color(ContextCompat.getColor(context, R.color.teal_200)),
                        RoundedCornerShape(20.dp)
                    )
            ) {
                Text(
                    "Bit Depth: ${config.BIT_DEPTH.bytes} bit",
                    color = Color(ContextCompat.getColor(context, R.color.purple_accent))
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "Sample Rate",
                    modifier = Modifier.size(ButtonDefaults.IconSize + 8.dp)
                )
            }
            DropdownMenu(
                expanded = showBitDepthMenu,
                onDismissRequest = { showBitDepthMenu = false }) {
                bitDepths.forEach { (label, value) ->
                    StyledDropdownMenuItem(text = "$label bit", onClick = {
                        Log.i("SettingsScreen", "Clicked BitDepth $label with value: $value")
                        settingsViewModel.updateBitDepth(value)
                        showBitDepthMenu = false
                        val intent = Intent(context, MyBufferService::class.java)
                        intent.action = "com.mfc.recentaudiobuffer.SETTINGS_UPDATED"
                        context.sendBroadcast(intent)
                    })
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = bufferTimeLength.toString(),
                onValueChange = { userInput: String ->
                    if (userInput.isEmpty()) {
                        // Allow complete deletion
                        bufferTimeLength = 1
                    } else {
                        val parsedValue = userInput.trim().toIntOrNull()
                        if (parsedValue != null) {
                            bufferTimeLength = parsedValue
                        }
                        // Consider adding an error message if the input is invalid
                    }
                },
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = Color(
                        ContextCompat.getColor(
                            context, R.color.teal_100
                        )
                    ), focusedContainerColor = Color(
                        ContextCompat.getColor(
                            context, R.color.teal_200
                        )
                    ),
                    errorContainerColor = Color.Red
                ),
                shape = RoundedCornerShape(20.dp),
                label = {
                    Text("Buffer Length (seconds)")
                    Modifier.weight(900F)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 0.dp, end = 20.dp, bottom = 0.dp),
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    Log.i("SettingsScreen", "onDone triggered")
                    settingsViewModel.updateBufferTimeLength(bufferTimeLength)
                    val intent = Intent(context, MyBufferService::class.java)
                    intent.action = "com.mfc.recentaudiobuffer.SETTINGS_UPDATED"
                    context.sendBroadcast(intent)
                }),
                textStyle = TextStyle(
                    color = Color(
                        ContextCompat.getColor(
                            context, R.color.purple_accent
                        )
                    ),
                    fontWeight = W500
                ),
            )

            Spacer(modifier = Modifier.height(16.dp))

            StyledSubmitButton(onClick = {
                // Save the values (including bufferTimeLength) and send broadcast
                settingsViewModel.updateBufferTimeLength(bufferTimeLength)
                val intent = Intent(context, MyBufferService::class.java)
                intent.action = "com.mfc.recentaudiobuffer.SETTINGS_UPDATED"
                context.sendBroadcast(intent)
                activity?.finish()
            }, icon = ImageVector.vectorResource(id = R.drawable.baseline_save_alt_24))

            LaunchedEffect(config) {
                bufferTimeLength = config.BUFFER_TIME_LENGTH_S
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        innerPadding.calculateTopPadding()
        innerPadding.calculateBottomPadding()
    }
}

// Helper function to convert dp to px
private fun Int.dpToPx(context: Context): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), context.resources.displayMetrics
    )
}

@Composable
fun StyledSubmitButton(onClick: () -> Unit, icon: ImageVector) {
    val context = LocalContext.current

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent
        ),
        modifier = Modifier
            .padding(top = 8.dp)
            .border(
                1.5.dp,
                Color(ContextCompat.getColor(context, R.color.purple_accent)),
                RoundedCornerShape(20.dp)
            )
            .background(
                Color(ContextCompat.getColor(context, R.color.teal_200)),
                RoundedCornerShape(20.dp)
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Submit",
            modifier = Modifier.size(ButtonDefaults.IconSize + 8.dp)
        )
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(
            "Submit", color = Color(
                ContextCompat.getColor(
                    context, R.color.purple_accent
                )
            )
        )
    }
}

@Composable
fun StyledDropdownMenuItem(
    text: String, onClick: () -> Unit
) {
    val context = LocalContext.current

    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        colors = MenuDefaults.itemColors(
            textColor = Color(
                ContextCompat.getColor(
                    context, R.color.purple_accent
                )
            ),
        ),
    )
}