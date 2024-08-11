package com.mfc.recentaudiobuffer

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.core.content.ContextCompat


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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Sample Rate
        OutlinedButton(onClick = { showSampleRateMenu = true }) {
            Text("Sample Rate: ${config.SAMPLE_RATE_HZ} Hz")
        }
        DropdownMenu(
            expanded = showSampleRateMenu,
            onDismissRequest = { showSampleRateMenu = false }) {
            sampleRates.forEach { (label, value) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
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
        OutlinedButton(onClick = { showBitDepthMenu = true }) {
            Text("Bit Depth: ${config.BIT_DEPTH.bytes} bit")
        }
        DropdownMenu(expanded = showBitDepthMenu, onDismissRequest = { showBitDepthMenu = false }) {
            bitDepths.forEach { (label, value) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
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
                val parsedValue = userInput.trim().toIntOrNull()
                if (parsedValue != null) {
                    bufferTimeLength = parsedValue
                } else {
                    // Handle invalid input (e.g., show an error message)
                }
            },
            label = { Text("Buffer Length (seconds)") },
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.5.dp,
                    Color(ContextCompat.getColor(context, R.color.purple_accent)),
                    RoundedCornerShape(8.dp)
                )
                .padding(8.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                Log.i("SettingsScreen", "onDone triggered")
                settingsViewModel.updateBufferTimeLength(bufferTimeLength)
                val intent = Intent(context, MyBufferService::class.java)
                intent.action = "com.mfc.recentaudiobuffer.SETTINGS_UPDATED"
                context.sendBroadcast(intent)
            }),
            textStyle = TextStyle(color = Color.Black)
        )

        Spacer(modifier = Modifier.height(16.dp))

        SubmitButton(onClick = {
            // Save the values (including bufferTimeLength) and send broadcast
            settingsViewModel.updateBufferTimeLength(bufferTimeLength)
            val intent = Intent(context, MyBufferService::class.java)
            intent.action = "com.mfc.recentaudiobuffer.SETTINGS_UPDATED"
            context.sendBroadcast(intent)
            activity?.finish()
        })

        LaunchedEffect(config) {
            bufferTimeLength = config.BUFFER_TIME_LENGTH_S
        }
    }
}

// Define SubmitButton composable function outside of SettingsScreen
@Composable
fun SubmitButton(onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text("Submit")
    }
}