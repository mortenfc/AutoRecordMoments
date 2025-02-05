package com.mfc.recentaudiobuffer

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat

object PhoneUtils {
    fun getPhoneAccountHandle(context: Context, telecomManager: TelecomManager?): PhoneAccountHandle? {
        if (telecomManager == null) {
            Log.e("PhoneUtils", "TelecomManager is null")
            return null
        }

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("PhoneUtils", "READ_PHONE_STATE permission not granted")
            return null
        }

        val phoneAccountHandles = telecomManager.callCapablePhoneAccounts
        if (phoneAccountHandles.isNotEmpty()) {
            return phoneAccountHandles[0]
        }

        Log.e("PhoneUtils", "No call-capable phone accounts found")
        return null
    }

    fun placeCall(context: Context, telecomManager: TelecomManager?, phoneNumber: String, phoneAccountHandle: PhoneAccountHandle?) {
        if (telecomManager == null) {
            Log.e("PhoneUtils", "TelecomManager is null, cannot make a call")
            return
        }
        if (phoneAccountHandle == null) {
            Log.e("PhoneUtils", "Phone account handle is null")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
            }
            context.startActivity(intent)
        } catch (e: SecurityException) {
            Log.e("PhoneUtils", "SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.e("PhoneUtils", "Exception: ${e.message}")
        }
    }

    fun getTelecomManager(context: Context): TelecomManager? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        } else {
            null
        }
    }

    fun isDefaultDialer(context: Context, telecomManager: TelecomManager): Boolean {
        val isDefault = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val result = telecomManager.defaultDialerPackage == context.packageName
            Log.d(
                "isDefaultDialer",
                "Q and above: defaultDialerPackage = ${telecomManager.defaultDialerPackage}, packageName = ${context.packageName}, result = $result"
            )
            result
        } else {
            val intent = Intent(Intent.ACTION_DIAL)
            val componentName = intent.resolveActivity(context.packageManager)
            val result = componentName?.packageName == context.packageName
            Log.d(
                "isDefaultDialer",
                "Pre-Q: componentName = $componentName, packageName = ${context.packageName}, result = $result"
            )
            result
        }
        return isDefault
    }

    @Composable
    fun MakeCallButton(telecomManager: TelecomManager?, phoneNumber: String) {
        val context = LocalContext.current
        var callAttempted by remember { mutableStateOf(false) }

        val phoneAccountHandle = getPhoneAccountHandle(context, telecomManager)

        val requestPermissionLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
                if (isGranted) {
                    Log.i("PhoneUtils", "Permission granted")
                    if (callAttempted) {
                        placeCall(context, telecomManager, phoneNumber, phoneAccountHandle)
                    }
                } else {
                    Log.i("PhoneUtils", "Permission denied")
                }
            }

        Button(onClick = {
            callAttempted = true
            if (telecomManager != null) {
                if (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.CALL_PHONE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    placeCall(context, telecomManager, phoneNumber, phoneAccountHandle)
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        requestPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                    }
                }
            } else {
                Log.e("PhoneUtils", "TelecomManager is null, cannot make a call in preview")
            }
        }) {
            Text(text = stringResource(id = R.string.make_a_call))
        }
    }

    @Composable
    fun SetDefaultDialerButton() {
        val context = LocalContext.current
        val requestRoleLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            // Handle the result if needed
            Log.i("PhoneUtils", "Result: ${result.resultCode}")
        }

        Button(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
                if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    if (!roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                        val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                        requestRoleLauncher.launch(intent)
                    } else {
                        Log.i("PhoneUtils", "Already default dialer")
                    }
                } else {
                    Log.e("PhoneUtils", "Dialer role not available")
                }
            } else {
                val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                intent.putExtra(
                    TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                    context.packageName
                )
                context.startActivity(intent)
            }
        }) {
            Text(text = stringResource(id = R.string.set_as_default_dialer))
        }
    }
}