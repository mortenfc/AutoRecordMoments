package com.mfc.recentaudiobuffer

import android.content.Context
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class DialerActivity : ComponentActivity() {
    private lateinit var telecomManager: TelecomManager
    val logTag = "DialerActivity"

    @Inject
    lateinit var authenticationManager: AuthenticationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager

        setContent {
            DialerScreen(
                onNavigateToMain = { finish() }, // Navigate back to MainActivity by finishing this activity
                onSignInClick = { authenticationManager.onSignInClick() },
                signInButtonText = authenticationManager.signInButtonText,
                telecomManager = telecomManager
            )
        }
    }

    override fun onStart() {
        super.onStart()
        Timber.tag(logTag).d("onStart() called with Intent.action: ${intent.action}")
        authenticationManager.registerLauncher(this)
    }
}