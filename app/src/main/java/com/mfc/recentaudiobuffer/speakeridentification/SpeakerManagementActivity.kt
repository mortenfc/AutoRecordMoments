package com.mfc.recentaudiobuffer.speakeridentification

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.hilt.navigation.compose.hiltViewModel
import com.mfc.recentaudiobuffer.ui.theme.RecentAudioBufferTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SpeakerManagementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RecentAudioBufferTheme {
                val viewModel: SpeakersViewModel = hiltViewModel()
                SpeakersScreen(
                    viewModel = viewModel,
                    onNavigateBack = { finish() }
                )
            }
        }
    }
}