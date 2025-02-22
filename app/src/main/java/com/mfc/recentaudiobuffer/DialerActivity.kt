package com.mfc.recentaudiobuffer

import android.content.Context
import android.content.Intent
import android.net.Uri
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

        val phoneNumber = getPhoneNumberFromIntent(intent)

        if (phoneNumber != null) {
            // Start a call directly
            startCall(phoneNumber)
            finish() // Finish this activity after starting the call
        } else {
            // Show the DialerScreen
            setContent {
                DialerScreen(
                    onNavigateToMain = { finish() }, // Navigate back to MainActivity by finishing this activity
                    onSignInClick = { authenticationManager.onSignInClick() },
                    signInButtonText = authenticationManager.signInButtonText,
                    telecomManager = telecomManager
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val phoneNumber = getPhoneNumberFromIntent(intent)
        if (phoneNumber != null) {
            // Start a call directly
            startCall(phoneNumber)
            finish() // Finish this activity after starting the call
        }
    }

    override fun onStart() {
        super.onStart()
        Timber.tag(logTag).d("onStart() called with Intent.action: ${intent.action}")
        authenticationManager.registerLauncher(this)
    }

    private fun getPhoneNumberFromIntent(intent: Intent): String? {
        return when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_DIAL -> {
                val uri = intent.data
                if (uri != null && uri.scheme == "tel") {
                    uri.schemeSpecificPart
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun startCall(phoneNumber: String) {
        val uri = Uri.fromParts("tel", phoneNumber, null)
        val intent = Intent(Intent.ACTION_CALL, uri)
        if (telecomManager.getDefaultDialerPackage() != packageName) {
            Timber.tag(logTag).d("Not the default dialer, cannot start call")
            return
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Timber.tag(logTag).d("No activity found to handle ACTION_CALL intent")
        }
    }
}