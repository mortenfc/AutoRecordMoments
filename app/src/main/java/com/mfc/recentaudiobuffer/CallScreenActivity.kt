package com.mfc.recentaudiobuffer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// This is used for incoming calls when it's the default call app
@AndroidEntryPoint
class CallScreenActivity : ComponentActivity() {
    val logTag = "CallScreenActivity"

    @Inject
    lateinit var authenticationManager: AuthenticationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.action == Intent.ACTION_DIAL) {
            Log.i(logTag, "Received DIAL intent")
            // You can extract data from the intent here if needed
            // For example, if there's a phone number:
            // val phoneNumber = intent.data?.schemeSpecificPart
        }

        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

        setContent {
            CallScreen(
                onNavigateToMain = { finish() }, // Navigate back to MainActivity by finishing this activity
                onSignInClick = { authenticationManager.onSignInClick() },
                signInButtonText = authenticationManager.signInButtonText,
                telecomManager = telecomManager
            )
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(
            logTag, "onStart() called with Intent.action: ${intent.action}"
        )
        authenticationManager.registerLauncher(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(logTag, "onNewIntent() called")
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_DIAL) {
            Log.i(logTag, "Received DIAL intent")
            val phoneNumber = intent.data?.schemeSpecificPart
            if (phoneNumber != null) {
                Log.i(logTag, "Received DIAL intent with phone number: $phoneNumber")
            }
        }
    }
}