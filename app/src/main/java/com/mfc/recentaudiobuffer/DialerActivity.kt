package com.mfc.recentaudiobuffer

import MyPhoneStateListener
import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class DialerActivity : ComponentActivity() {
    private var telecomManager: TelecomManager? = null
    private var mDPM: DevicePolicyManager? = null
    private var mAdminName: ComponentName? = null
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: MyPhoneStateListener? = null
    val logTag = "DialerActivity"

    companion object {
        private const val REQUEST_CODE = 0
    }

    @Inject
    lateinit var authenticationManager: AuthenticationManager

    private val requestCallPhonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i(logTag, "CALL_PHONE permission granted")
                // If a call was attempted before permission was granted, place it now.
                phoneNumberToCall?.let {
                    placeCall(it)
                    phoneNumberToCall = null // Clear the stored number
                }
            } else {
                Log.i(logTag, "CALL_PHONE permission denied")
                Toast.makeText(this, "Call permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private var phoneNumberToCall: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (OngoingCall.USE_TELEPHONY) {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            phoneStateListener = MyPhoneStateListener(this)
            OngoingCall.setMyPhoneStateListener(phoneStateListener!!)
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } else {
            telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            mDPM = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            mAdminName = ComponentName(this, MyDeviceAdminReceiver::class.java)

            if (mDPM?.isAdminActive(mAdminName!!) == false) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, mAdminName)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Click on Activate button to secure your application."
                    )
                }
                startActivityForResult(intent, REQUEST_CODE)
            } else {
                Log.d(logTag, "Admin already active")
                showDialerScreenOrStartCall()
            }
        }
        showDialerScreenOrStartCall()
    }

    @Deprecated("This method has been deprecated in favor of using the Activity Result API\n      which brings increased type safety via an {@link ActivityResultContract} and the prebuilt\n      contracts for common intents available in\n      {@link androidx.activity.result.contract.ActivityResultContracts}, provides hooks for\n      testing, and allow receiving results in separate, testable classes independent from your\n      activity. Use\n      {@link #registerForActivityResult(ActivityResultContract, ActivityResultCallback)}\n      with the appropriate {@link ActivityResultContract} and handling the result in the\n      {@link ActivityResultCallback#onActivityResult(Object) callback}.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (REQUEST_CODE == requestCode) {
            if (resultCode == RESULT_OK) {
                Log.d(logTag, "Admin activated")
                // Recreate the activity to show the DialerScreen
                recreate()
            } else {
                Log.w(logTag, "Admin activation failed")
                Toast.makeText(this, "Admin activation failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (OngoingCall.USE_TELEPHONY) {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the current intent
        showDialerScreenOrStartCall()
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

            Intent.ACTION_CALL -> {
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

    private fun placeCall(phoneNumber: String) {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                if (OngoingCall.USE_TELEPHONY) {
                    OngoingCall.onTelephonyCallStarted = { // Set the callback here
                        val callActivityIntent = Intent(this, CallActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(callActivityIntent)
                    }
                }
                val systemCallIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                }
                startActivity(systemCallIntent)
            } catch (e: SecurityException) {
                Timber.e(e, "SecurityException when placing call")
                Toast.makeText(this, "Cannot place call: permission denied.", Toast.LENGTH_SHORT)
                    .show()
            }
        } else {
            // Store the phone number to call after permission is granted
            phoneNumberToCall = phoneNumber
            requestCallPhonePermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private fun showDialerScreenOrStartCall() {
        val phoneNumber = getPhoneNumberFromIntent(intent)

        if (phoneNumber != null) {
            // Start a call directly
            placeCall(phoneNumber)
            finish() // Finish this activity after starting the call
        } else {
            // Show the DialerScreen
            setContent {
                val telecomManagerForDialer =
                    if (OngoingCall.USE_TELEPHONY) null else telecomManager
                DialerScreen(onNavigateToMain = { finish() }, // Navigate back to MainActivity by finishing this activity
                    onSignInClick = { authenticationManager.onSignInClick() },
                    signInButtonText = authenticationManager.signInButtonText,
                    telecomManager = telecomManagerForDialer,
                    onPlaceCall = { number ->
                        this.placeCall(number)
                    })
            }
        }
    }
}