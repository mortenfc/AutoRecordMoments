package com.mfc.recentaudiobuffer

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

object PhoneUtils {
    fun getPhoneAccountHandle(
        context: Context, telecomManager: TelecomManager?
    ): PhoneAccountHandle? {
        if (telecomManager == null) {
            Log.e("PhoneUtils", "TelecomManager is null")
            return null
        }

        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_PHONE_STATE
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

    fun placeCall(
        context: Context,
        telecomManager: TelecomManager?,
        phoneNumber: String,
        phoneAccountHandle: PhoneAccountHandle?
    ) {
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
}