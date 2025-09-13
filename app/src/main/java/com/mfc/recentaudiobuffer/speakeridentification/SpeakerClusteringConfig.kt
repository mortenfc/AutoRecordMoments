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
import androidx.compose.material3.HorizontalDivider
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
        // --- Primary Clustering (DBSCAN for prominent speakers) ---
        val dbscanEps: Float = 0.64f,
        val highConfidenceMinPts: Int = 6,

        // --- Leftover Clustering (AHC for sparse speakers) ---
        val leftoverAhcThreshold: Float = 0.76f,

        // --- Cluster Quality Filters ---
        val minClusterSize: Int = 2,
        val smallClusterSizeThreshold: Int = 3,
        val clusterPurityThreshold: Float = 0.43f,
        val minPurityForSmallCluster: Float = 0.81f,
        val baseMaxClusterVariance: Float = 0.0012f,
        val varianceGrowthFactor: Float = 1f,

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
        val vadMergeGapMs: Int = 125,
        val vadPaddingMs: Int = 50,
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
            |Primary Clustering (DBSCAN):
            |  eps: ${p.dbscanEps}
            |  minPts: ${p.highConfidenceMinPts}
            |
            |Leftover Clustering (AHC):
            |  distanceThreshold: ${p.leftoverAhcThreshold}
            |
            |Quality Filters:
            |  minClusterSize: ${p.minClusterSize}
            |  smallClusterSizeThreshold: ${p.smallClusterSizeThreshold}
            |  clusterPurityThreshold: ${p.clusterPurityThreshold}
            |  minPurityForSmallCluster: ${p.minPurityForSmallCluster}
            |  baseMaxClusterVariance: ${p.baseMaxClusterVariance}
            |  varianceGrowthFactor: ${p.varianceGrowthFactor}
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
            Text("About Hybrid Clustering", style = MaterialTheme.typography.titleLarge)
        }, text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Key Terms:",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "• Segment: A short (1-3s) chunk of audio.\n" +
                            "• Cluster: A group of segments the algorithm thinks belong to the same person.\n" +
                            "• Speaker: A final, quality-checked cluster that is presented as a result.",
                    modifier = Modifier.padding(bottom = 16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))

                Text(
                    "This uses a two-stage process to get the best of both worlds: speed and accuracy.",
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Text(
                    "1. DBSCAN Pass",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "A fast scan finds the obvious, prominent speakers who talk a lot.",
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Text(
                    "2. AHC Pass",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "A slower, more precise scan is run on the 'leftover' segments to find rarer speakers without getting confused by the main ones.",
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

                SettingsSection("Primary Clustering (DBSCAN)") {
                    SliderSetting(
                        label = "Epsilon (eps)",
                        value = tempParams.dbscanEps,
                        onValueChange = { tempParams = tempParams.copy(dbscanEps = it) },
                        valueRange = 0.4f..0.8f,
                        steps = 40,
                        description = "Similarity needed to group segments. Higher: More grouping (fewer, larger clusters). Lower: Stricter (more, smaller clusters)."
                    )
                    SliderSetting(
                        label = "Min Points",
                        value = tempParams.highConfidenceMinPts.toFloat(),
                        onValueChange = { tempParams = tempParams.copy(highConfidenceMinPts = it.toInt()) },
                        valueRange = 3f..10f,
                        steps = 7,
                        displayFormatter = { "%.0f".format(it) },
                        description = "Minimum segments to form a prominent speaker cluster. Higher: Requires more speech. Lower: Finds smaller groups."
                    )
                }

                SettingsSection("Leftover Clustering (AHC)") {
                    SliderSetting(
                        label = "Distance Threshold",
                        value = tempParams.leftoverAhcThreshold,
                        onValueChange = { tempParams = tempParams.copy(leftoverAhcThreshold = it) },
                        valueRange = 0.4f..1.0f,
                        steps = 60,
                        description = "Max 'difference' allowed for merging sparse clusters. Higher: Merges more (fewer speakers). Lower: Merges less (more speakers)."
                    )
                }

                SettingsSection("Quality Filters") {
                    SliderSetting(
                        label = "Purity Threshold",
                        value = tempParams.clusterPurityThreshold,
                        onValueChange = { tempParams = tempParams.copy(clusterPurityThreshold = it) },
                        valueRange = 0.3f..0.8f,
                        steps = 50,
                        description = "Min average similarity for a cluster to be valid. Higher: Stricter, rejects more. Lower: More lenient."
                    )
                    SliderSetting(
                        label = "Threshold for a cluster to be considered small",
                        value = tempParams.smallClusterSizeThreshold.toFloat(),
                        onValueChange = { tempParams = tempParams.copy(smallClusterSizeThreshold = it.toInt()) },
                        valueRange = 2f..6f,
                        steps = 4,
                        displayFormatter = { "%.0f".format(it) },
                        description = "Small cluster segment size threshold to apply Small Cluster Purity instead of Purity Threshold. Higher: Rejects more. Lower: Accepts more."
                    )
                    SliderSetting(
                        label = "Small Cluster Purity",
                        value = tempParams.minPurityForSmallCluster,
                        onValueChange = { tempParams = tempParams.copy(minPurityForSmallCluster = it) },
                        valueRange = 0.7f..0.98f,
                        steps = 28,
                        displayFormatter = { "%.0f%%".format(it * 100) },
                        description = "Stricter purity for small clusters (≤ ${tempParams.smallClusterSizeThreshold} segments). Higher: Rejects more. Lower: Accepts more."
                    )
                    SliderSetting(
                        label = "Max Variance (Base)",
                        value = tempParams.baseMaxClusterVariance * 1000,
                        onValueChange = { tempParams = tempParams.copy(baseMaxClusterVariance = it / 1000) },
                        valueRange = 1f..10f,
                        steps = 90,
                        displayFormatter = { "%.1f‰".format(it) },
                        description = "The starting variance limit for the smallest clusters. This is the strictest the filter will be."
                    )
                    SliderSetting(
                        label = "Variance Growth Factor",
                        value = tempParams.varianceGrowthFactor,
                        onValueChange = { tempParams = tempParams.copy(varianceGrowthFactor = it) },
                        valueRange = 0.1f..1.0f,
                        steps = 18,
                        description = "Controls how much the variance limit grows for larger clusters. Higher: More lenient. Lower: Stricter."
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

