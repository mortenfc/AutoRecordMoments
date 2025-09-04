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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber

// How to Use for Tuning:
//
// Enable Debug Mode: Toggle "Show Debug" in the UI to see all metrics
// Observe Current Behavior: Note which speakers appear and their confidence scores
// Identify Issues:
//
// - Too many speakers? → Decrease dbscanEps or increase minPts
// - Missing valid speakers? → Increase dbscanEps or decrease purityThreshold
// - Speakers with low confidence? → Adjust clusterPurityThreshold
// - Too much variation? → Lower maxClusterVariance
//
//
// Live Tune: Open settings dialog and adjust parameters while watching the logs
// Export Reports: Use exportDebugReport() to save configurations that work
//
// Key Parameters to Tune:
//
// - dbscanEps (0.3-1.0): Main clustering sensitivity
// - clusterPurityThreshold (0.3-0.8): How similar segments must be to centroid
// - maxClusterVariance (0.001-0.01): Maximum allowed spread in cluster
// - finalMergeThreshold (0.2-0.8): When to merge similar clusters
@Singleton
class SpeakerClusteringConfig @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // Create a data class for all tunable parameters
    data class Parameters(
        // DBSCAN parameters - Primary clustering
        val dbscanEps: Float = 0.65f,              // Lower = stricter clustering
        val dbscanMinPts: Int = 3,                 // Higher = fewer clusters

        // Noise re-clustering
        val noiseEps: Float = 0.45f,               // Stricter than primary
        val noiseMinPts: Int = 5,                  // Higher requirement for noise
        val minNoiseForReclustering: Int = 10,     // Min noise points to trigger reclustering
        val noiseRatioThreshold: Float = 0.3f,     // Noise ratio to trigger reclustering

        // Cluster merging
        val finalMergeThreshold: Float = 0.4f,     // Lower = fewer merges

        // Cluster quality filters
        val minClusterSize: Int = 2,               // Minimum segments per cluster
        val clusterPurityThreshold: Float = 0.5f,  // Min similarity to centroid
        val maxClusterVariance: Float = 0.003f,    // Max allowed variance

        // Sample generation
        val sampleMinDurationSec: Int = 7,
        val sampleMaxDurationSec: Int = 20,
        val sampleTargetSegments: Int = 15,
        val minChunkDurationSec: Float = 1.0f,
        val sampleSilenceDurationMs: Int = 500,

        // Diarization
        val minSegmentDurationSec: Float = 1.0f,
        val maxSegmentDurationSec: Float = 3.0f,
        val minSpeechEnergyRms: Float = 0.001f,

        // VAD
        val vadMergeGapMs: Int = 300,
        val vadPaddingMs: Int = 100,
        val vadSpeechThreshold: Float = 0.25f
    )

    private val _parameters = MutableStateFlow(Parameters())
    val parameters: StateFlow<Parameters> = _parameters.asStateFlow()

    init {
        // Load saved config on initialization
        loadFromPreferences()
    }

    fun updateParameters(update: Parameters.() -> Parameters) {
        _parameters.value = _parameters.value.update()
        saveToPreferences()
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
            |DBSCAN Primary:
            |  eps: ${p.dbscanEps}
            |  minPts: ${p.dbscanMinPts}
            |
            |Noise Reclustering:
            |  eps: ${p.noiseEps}
            |  minPts: ${p.noiseMinPts}
            |  minNoiseForReclustering: ${p.minNoiseForReclustering}
            |  noiseRatioThreshold: ${p.noiseRatioThreshold}
            |
            |Merging:
            |  finalMergeThreshold: ${p.finalMergeThreshold}
            |
            |Quality Filters:
            |  minClusterSize: ${p.minClusterSize}
            |  clusterPurityThreshold: ${p.clusterPurityThreshold}
            |  maxClusterVariance: ${p.maxClusterVariance}
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

// Add a Settings Dialog for live tuning
@Composable
fun ClusteringSettingsDialog(
    config: SpeakerClusteringConfig, onDismiss: () -> Unit, onApply: () -> Unit
) {
    val parameters by config.parameters.collectAsStateWithLifecycle()
    var tempParams by remember { mutableStateOf(parameters) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Clustering Parameters",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(16.dp))

                // DBSCAN Section
                SettingsSection("Primary DBSCAN") {
                    SliderSetting(
                        label = "Epsilon (eps)",
                        value = tempParams.dbscanEps,
                        onValueChange = { tempParams = tempParams.copy(dbscanEps = it) },
                        valueRange = 0.3f..1.0f,
                        steps = 70
                    )

                    SliderSetting(
                        label = "Min Points",
                        value = tempParams.dbscanMinPts.toFloat(),
                        onValueChange = { tempParams = tempParams.copy(dbscanMinPts = it.toInt()) },
                        valueRange = 2f..10f,
                        steps = 8
                    )
                }

                // Noise Reclustering Section
                SettingsSection("Noise Reclustering") {
                    SliderSetting(
                        label = "Noise Epsilon",
                        value = tempParams.noiseEps,
                        onValueChange = { tempParams = tempParams.copy(noiseEps = it) },
                        valueRange = 0.2f..0.8f,
                        steps = 60
                    )

                    SliderSetting(
                        label = "Noise Min Points",
                        value = tempParams.noiseMinPts.toFloat(),
                        onValueChange = { tempParams = tempParams.copy(noiseMinPts = it.toInt()) },
                        valueRange = 3f..15f,
                        steps = 12
                    )

                    SliderSetting(
                        label = "Noise Ratio Threshold",
                        value = tempParams.noiseRatioThreshold,
                        onValueChange = { tempParams = tempParams.copy(noiseRatioThreshold = it) },
                        valueRange = 0.1f..0.5f,
                        steps = 40
                    )
                }

                // Quality Filters Section
                SettingsSection("Quality Filters") {
                    SliderSetting(
                        label = "Purity Threshold",
                        value = tempParams.clusterPurityThreshold,
                        onValueChange = {
                            tempParams = tempParams.copy(clusterPurityThreshold = it)
                        },
                        valueRange = 0.3f..0.8f,
                        steps = 50
                    )

                    SliderSetting(
                        label = "Max Variance",
                        value = tempParams.maxClusterVariance * 1000, // Scale for UI
                        onValueChange = {
                            tempParams = tempParams.copy(maxClusterVariance = it / 1000)
                        },
                        valueRange = 1f..10f,
                        steps = 90,
                        displayFormatter = { "%.1f‰".format(it) })

                    SliderSetting(
                        label = "Merge Threshold",
                        value = tempParams.finalMergeThreshold,
                        onValueChange = { tempParams = tempParams.copy(finalMergeThreshold = it) },
                        valueRange = 0.2f..0.8f,
                        steps = 60
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(onClick = {
                        tempParams = SpeakerClusteringConfig.Parameters()
                    }) {
                        Text("Reset Defaults")
                    }

                    Button(
                        onClick = {
                            config.updateParameters { tempParams }
                            onApply()
                            onDismiss()
                        }) {
                        Text("Apply & Re-scan")
                    }

                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        }
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

@Composable
fun SliderSetting(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 100,
    displayFormatter: (Float) -> String = { "%.2f".format(it) }
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
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}