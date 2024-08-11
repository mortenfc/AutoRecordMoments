package com.mfc.recentaudiobuffer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel
) {
    val config by settingsViewModel.config.collectAsState()
    var showSampleRateMenu by remember { mutableStateOf(false) }
    var showBitDepthMenu by remember { mutableStateOf(false) }
    var bufferTimeLength by remember { mutableStateOf(config.BUFFER_TIME_LENGTH_S.toString()) }

    LaunchedEffect(config) {
        bufferTimeLength = config.BUFFER_TIME_LENGTH_S.toString()
    }

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
            onDismissRequest = { showSampleRateMenu = false }
        ) {
            sampleRates.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        settingsViewModel.updateSampleRate(value)
                        showSampleRateMenu = false
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp)) // Add spacing between elements

        // Buffer Time Length
        OutlinedTextField(
            value = bufferTimeLength,
            onValueChange = {
                bufferTimeLength = it
                settingsViewModel.updateBufferTimeLength(it.toIntOrNull() ?: 120)
            },
            label = { Text("Buffer Time (seconds)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Bit Depth
        OutlinedButton(onClick = { showBitDepthMenu = true }) {
            Text("Bit Depth: ${config.BIT_DEPTH.bytes} bit")
        }
        DropdownMenu(
            expanded = showBitDepthMenu,
            onDismissRequest = { showBitDepthMenu = false }
        ) {
            bitDepths.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        settingsViewModel.updateBitDepth(value)
                        showBitDepthMenu = false
                    }
                )
            }
        }
    }
}
