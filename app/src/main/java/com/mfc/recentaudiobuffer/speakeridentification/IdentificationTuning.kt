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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit
import com.mfc.recentaudiobuffer.R
import com.mfc.recentaudiobuffer.appButtonColors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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
    @ApplicationContext private val context: Context,
    private val speakerRepository: SpeakerRepository? = null
) {
    data class Parameters(
        // DBSCAN parameters - Primary clustering
        val dbscanEps: Float = 0.62535214f,              // Lower = stricter clustering
        val dbscanMinPts: Int = 3,                 // Higher = fewer clusters

        // Noise re-clustering
        val noiseEps: Float = 0.3967213f,                // Stricter than primary
        val noiseMinPts: Int = 5,                  // Higher requirement for noise
        val minNoiseForReclustering: Int = 10,     // Min noise points to trigger reclustering
        val noiseRatioThreshold: Float = 0.3f,     // Noise ratio to trigger reclustering

        // Cluster merging
        val finalMergeThreshold: Float = 0.34754097f,     // Lower = more merges

        // Cluster quality filters
        val minClusterSize: Int = 2,               // Minimum segments per cluster
        val clusterPurityThreshold: Float = 0.5156863f,  // Min similarity to centroid
        val maxClusterVariance: Float = 0.0024835165f,    // Max allowed variance

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
        val vadMergeGapMs: Int = 200,
        val vadPaddingMs: Int = 100,
        val vadSpeechThreshold: Float = 0.25f
    )

    private val _parameters = MutableStateFlow(Parameters())
    val parameters: StateFlow<Parameters> = _parameters.asStateFlow()

    // Create a scope for this class
    private val configScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        // 1. Load from local SharedPreferences immediately for a fast startup
        loadFromPreferences()

        // 2. Listen for updates pulled from Firestore by the repository
        configScope.launch {
            speakerRepository?.clusteringConfig?.filterNotNull()?.collect { firestoreConfig ->
                val newParams = firestoreConfig.toParameters()
                if (newParams != _parameters.value) {
                    Timber.d("Firestore config received, updating local state.")
                    _parameters.value = newParams
                    saveToPreferences() // Keep local SharedPreferences in sync
                }
            }
        }
    }

    fun updateParameters(update: Parameters.() -> Parameters) {
        val newParams = _parameters.value.update()
        _parameters.value = newParams
        saveToPreferences() // Save locally

        // Also save to Firestore
        configScope.launch {
            speakerRepository?.updateClusteringConfig(newParams)
        }
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

@Composable
fun ClusteringSettingsDialog(
    config: SpeakerClusteringConfig, onDismiss: () -> Unit, onApply: () -> Unit
) {
    val parameters by config.parameters.collectAsStateWithLifecycle()
    var tempParams by remember { mutableStateOf(parameters) }
    var showInfoDialog by remember { mutableStateOf(false) }

    // Info Dialog
    if (showInfoDialog) {
        AlertDialog(onDismissRequest = { showInfoDialog = false }, title = {
            Text(
                "Understanding Clusters vs Speakers", style = MaterialTheme.typography.titleLarge
            )
        }, text = {
            Column {
                Text(
                    "What's a Cluster?",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "A cluster is a group of audio segments (1-3 second chunks) that the algorithm believes come from the same voice based on mathematical similarity. It's a temporary grouping during processing.",
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    "What's a Speaker?",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "A speaker is the final output after all processing - a cleaned, verified cluster that passed quality checks and represents a distinct person's voice profile.",
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    "The Process:",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "1. Audio segments are grouped into clusters\n" + "2. Clusters are filtered by purity/variance\n" + "3. Similar clusters may be merged\n" + "4. Final clusters become speakers\n\n" + "Low confidence scores or many discarded segments indicate mixed voices in a cluster.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }, confirmButton = {
            TextButton(onClick = { showInfoDialog = false }) {
                Text("Got it")
            }
        })
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())
            ) {
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

                    // Info button
                    IconButton(
                        onClick = { showInfoDialog = true }, modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "About clustering",
                            tint = colorResource(id = R.color.purple_accent)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // DBSCAN Section
                SettingsSection("Primary DBSCAN") {
                    Text(
                        "Controls initial speaker grouping",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    SliderSetting(
                        label = "Epsilon (eps)",
                        value = tempParams.dbscanEps,
                        onValueChange = { tempParams = tempParams.copy(dbscanEps = it) },
                        valueRange = 0.3f..1.0f,
                        steps = 70,
                        description = "Lower → Stricter (more speakers) | Higher → Looser (fewer speakers)"
                    )

                    SliderSetting(
                        label = "Min Points",
                        value = tempParams.dbscanMinPts.toFloat(),
                        onValueChange = { tempParams = tempParams.copy(dbscanMinPts = it.toInt()) },
                        valueRange = 2f..10f,
                        steps = 8,
                        description = "Lower → More clusters | Higher → Fewer, denser clusters"
                    )
                }

                // Noise Reclustering Section
                SettingsSection("Noise Reclustering") {
                    Text(
                        "Re-processes segments that didn't initially cluster",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    SliderSetting(
                        label = "Noise Epsilon",
                        value = tempParams.noiseEps,
                        onValueChange = { tempParams = tempParams.copy(noiseEps = it) },
                        valueRange = 0.2f..0.8f,
                        steps = 60,
                        description = "Lower → Very strict | Higher → More inclusive"
                    )

                    SliderSetting(
                        label = "Noise Min Points",
                        value = tempParams.noiseMinPts.toFloat(),
                        onValueChange = { tempParams = tempParams.copy(noiseMinPts = it.toInt()) },
                        valueRange = 3f..15f,
                        steps = 12,
                        description = "Higher → Requires more evidence for noise clusters"
                    )

                    SliderSetting(
                        label = "Noise Ratio Threshold",
                        value = tempParams.noiseRatioThreshold,
                        onValueChange = { tempParams = tempParams.copy(noiseRatioThreshold = it) },
                        valueRange = 0.1f..0.5f,
                        steps = 40,
                        description = "Min % of noise to trigger reclustering"
                    )
                }

                // Quality Filters Section
                SettingsSection("Quality Filters") {
                    Text(
                        "Filters out low-quality or mixed-speaker clusters",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    SliderSetting(
                        label = "Purity Threshold",
                        value = tempParams.clusterPurityThreshold,
                        onValueChange = {
                            tempParams = tempParams.copy(clusterPurityThreshold = it)
                        },
                        valueRange = 0.3f..0.8f,
                        steps = 50,
                        description = "Higher → Removes mixed/contaminated clusters"
                    )

                    SliderSetting(
                        label = "Max Variance",
                        value = tempParams.maxClusterVariance * 1000,
                        onValueChange = {
                            tempParams = tempParams.copy(maxClusterVariance = it / 1000)
                        },
                        valueRange = 1f..10f,
                        steps = 90,
                        displayFormatter = { "%.1f‰".format(it) },
                        description = "Lower → Tighter clusters | Higher → Allow more spread"
                    )

                    SliderSetting(
                        label = "Merge Threshold",
                        value = tempParams.finalMergeThreshold,
                        onValueChange = { tempParams = tempParams.copy(finalMergeThreshold = it) },
                        valueRange = 0.2f..0.8f,
                        steps = 60,
                        description = "Lower → Merge more | Higher → Merge less"
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(
                        onClick = {
                            tempParams = SpeakerClusteringConfig.Parameters()
                        }, colors = appButtonColors()
                    ) {
                        Text("Reset")
                    }

                    Button(
                        onClick = {
                            config.updateParameters { tempParams }
                            onDismiss()
                        }, colors = appButtonColors()
                    ) {
                        Text("Apply")
                    }

                    Button(
                        onClick = {
                            config.updateParameters { tempParams }
                            onApply()
                            onDismiss()
                        }, colors = appButtonColors()
                    ) {
                        Text("Apply & Scan")
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