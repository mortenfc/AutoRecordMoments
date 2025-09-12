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

package com.mfc.recentaudiobuffer.speakeridentification

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import com.mfc.recentaudiobuffer.R
import com.mfc.recentaudiobuffer.appButtonColors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeakerClusteringConfig @Inject constructor(
    @ApplicationContext private val context: Context,
    private val speakerRepository: SpeakerRepository? = null
) {
    data class Parameters(
        // --- Agglomerative Hierarchical Clustering (AHC) ---
        val ahcDistanceThreshold: Float = 0.65f,

        // --- Cluster Quality Filters ---
        val minClusterSize: Int = 2,
        val clusterPurityThreshold: Float = 0.52f,
        val minPurityForSmallCluster: Float = 0.85f,
        val baseMaxClusterVariance: Float = 0.0025f,
        val varianceSizeFactor: Float = 0.5f, // Logarithmic factor for dynamic variance

        // --- Sample Generation ---
        val sampleMinDurationSec: Int = 7,
        val sampleMaxDurationSec: Int = 20,
        val sampleTargetSegments: Int = 15,
        val minChunkDurationSec: Float = 1.0f,
        val sampleSilenceDurationMs: Int = 500,

        // --- Diarization ---
        val minSegmentDurationSec: Float = 1.0f,
        val maxSegmentDurationSec: Float = 3.0f,
        val minSpeechEnergyRms: Float = 0.001f,

        // --- VAD ---
        val vadMergeGapMs: Int = 200,
        val vadPaddingMs: Int = 100,
        val vadSpeechThreshold: Float = 0.25f
    )

    private val _parameters = MutableStateFlow(Parameters())
    val parameters: StateFlow<Parameters> = _parameters.asStateFlow()

    private val configScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        loadFromPreferences()
        configScope.launch {
            speakerRepository?.clusteringConfig?.filterNotNull()?.collect { firestoreConfig ->
                val newParams = firestoreConfig.toParameters()
                if (newParams != _parameters.value) {
                    Timber.d("Firestore config received, updating local state.")
                    _parameters.value = newParams
                    saveToPreferences()
                }
            }
        }
    }

    fun updateParameters(update: Parameters.() -> Parameters) {
        val newParams = _parameters.value.update()
        _parameters.value = newParams
        saveToPreferences()
        configScope.launch { speakerRepository?.updateClusteringConfig(newParams) }
    }

    fun resetToDefaults() {
        _parameters.value = Parameters()
        saveToPreferences()
    }

    fun exportCurrentConfig(): String {
        val p = _parameters.value
        return """
            |=== CURRENT CLUSTERING CONFIGURATION ===
            |
            |Agglomerative Clustering:
            |  distanceThreshold: ${p.ahcDistanceThreshold}
            |
            |Quality Filters:
            |  minClusterSize: ${p.minClusterSize}
            |  clusterPurityThreshold: ${p.clusterPurityThreshold}
            |  minPurityForSmallCluster: ${p.minPurityForSmallCluster}
            |  baseMaxClusterVariance: ${p.baseMaxClusterVariance}
            |  varianceSizeFactor: ${p.varianceSizeFactor}
            |
            |Sample Generation:
            |  duration: ${p.sampleMinDurationSec}-${p.sampleMaxDurationSec}s
            |  targetSegments: ${p.sampleTargetSegments}
            |  minChunkDuration: ${p.minChunkDurationSec}s
            |
            |Diarization:
            |  segmentDuration: ${p.minSegmentDurationSec}-${p.maxSegmentDurationSec}s
            |  minEnergyRMS: ${p.minSpeechEnergyRms}
            |
            |VAD:
            |  mergeGap: ${p.vadMergeGapMs}ms
            |  padding: ${p.vadPaddingMs}ms
            |  threshold: ${p.vadSpeechThreshold}
            |========================================
        """.trimMargin()
    }

    private fun saveToPreferences() {
        val prefs = context.getSharedPreferences("clustering_config", Context.MODE_PRIVATE)
        val json = Gson().toJson(_parameters.value)
        prefs.edit { putString("parameters", json) }
    }

    private fun loadFromPreferences() {
        val prefs = context.getSharedPreferences("clustering_config", Context.MODE_PRIVATE)
        val json = prefs.getString("parameters", null)
        json?.let {
            try {
                _parameters.value = Gson().fromJson(it, Parameters::class.java)
                Timber.d("Loaded saved clustering configuration")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load config, using defaults")
            }
        }
    }
}

@Composable
fun ClusteringSettingsDialog(
    config: SpeakerClusteringConfig, onDismiss: () -> Unit, onApply: () -> Unit
) {
    val parameters by config.parameters.collectAsStateWithLifecycle()
    var tempParams by remember { mutableStateOf(parameters) }
    var showInfoDialog by remember { mutableStateOf(false) }

    if (showInfoDialog) {
        AlertDialog(onDismissRequest = { showInfoDialog = false }, title = {
            Text("About Agglomerative Clustering", style = MaterialTheme.typography.titleLarge)
        }, text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "This algorithm builds speakers from the ground up.",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "1. Each audio segment starts as its own speaker.\n" +
                            "2. The algorithm finds the two most similar speakers and merges them.\n" +
                            "3. This repeats until the most similar pair of speakers are still more different than the 'Distance Threshold'.",
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Text(
                    "This method is more computationally intensive but avoids many of the complex tuning parameters of other algorithms, relying on a single, intuitive distance setting.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }, confirmButton = {
            TextButton(onClick = { showInfoDialog = false }) { Text("Got it") }
        })
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(max = 700.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Clustering Parameters",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { showInfoDialog = true }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "About clustering",
                            tint = colorResource(id = R.color.purple_accent)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                SettingsSection("Agglomerative Clustering") {
                    SliderSetting(
                        label = "Distance Threshold",
                        value = tempParams.ahcDistanceThreshold,
                        onValueChange = { tempParams = tempParams.copy(ahcDistanceThreshold = it) },
                        valueRange = 0.4f..1.0f,
                        steps = 60,
                        description = "Higher = fewer speakers (more merging)"
                    )
                }

                SettingsSection("Quality Filters") {
                    SliderSetting(
                        label = "Purity Threshold",
                        value = tempParams.clusterPurityThreshold,
                        onValueChange = { tempParams = tempParams.copy(clusterPurityThreshold = it) },
                        valueRange = 0.3f..0.8f,
                        steps = 50,
                        description = "General filter for all clusters."
                    )
                    SliderSetting(
                        label = "Small Cluster Purity",
                        value = tempParams.minPurityForSmallCluster,
                        onValueChange = { tempParams = tempParams.copy(minPurityForSmallCluster = it) },
                        valueRange = 0.7f..0.98f,
                        steps = 28,
                        displayFormatter = { "%.0f%%".format(it * 100) },
                        description = "Stricter filter for clusters with ≤ ${tempParams.minClusterSize} segments."
                    )
                    SliderSetting(
                        label = "Base Max Variance",
                        value = tempParams.baseMaxClusterVariance * 1000,
                        onValueChange = { tempParams = tempParams.copy(baseMaxClusterVariance = it / 1000) },
                        valueRange = 1f..10f,
                        steps = 90,
                        displayFormatter = { "%.1f‰".format(it) },
                        description = "Base variance allowed for a cluster."
                    )
                    SliderSetting(
                        label = "Variance Size Factor",
                        value = tempParams.varianceSizeFactor,
                        onValueChange = { tempParams = tempParams.copy(varianceSizeFactor = it) },
                        valueRange = 0.1f..1.0f,
                        steps = 18,
                        description = "Allows larger clusters more variance."
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { tempParams = SpeakerClusteringConfig.Parameters() },
                        colors = appButtonColors()
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = "Reset")
                        Spacer(Modifier.width(8.dp))
                        Text("Reset")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            config.updateParameters { tempParams }
                            onApply()
                        },
                        colors = appButtonColors()
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                        Spacer(Modifier.width(8.dp))
                        Text("Save & Scan")
                    }
                }
            }
        }
    }
}

@Composable
fun SliderSetting(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 100,
    displayFormatter: (Float) -> String = { "%.2f".format(it) },
    description: String? = null
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                displayFormatter(value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun SettingsSection(
    title: String, content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        content()
        Spacer(Modifier.height(8.dp))
    }
}

