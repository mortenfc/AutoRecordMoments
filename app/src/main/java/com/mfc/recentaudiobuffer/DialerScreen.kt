package com.mfc.recentaudiobuffer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CallLogEntry(
    val number: String, val name: String?, val date: String, val type: String, val duration: String
)

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun DialerScreen(
    onNavigateToMain: () -> Unit,
    onSignInClick: () -> Unit,
    signInButtonText: MutableState<String>,
    telecomManager: TelecomManager? = null
) {
    val context = LocalContext.current
    var phoneNumberTextFieldValue by remember {
        mutableStateOf(TextFieldValue("")) // Initialize with an empty TextFieldValue
    }
    val recentCalls = remember { mutableStateListOf<CallLogEntry>() }
    var showCallLogDialog by remember { mutableStateOf(false) }
    val isDefaultDialer = telecomManager?.let { PhoneUtils.isDefaultDialer(context, it) } ?: false

    val requestCallLogPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i("CallScreen", "Call Log Permission granted")
                recentCalls.clear()
                recentCalls.addAll(getCallLog(context))
            } else {
                Log.i("CallScreen", "Call Log Permission denied")
            }
        }

    Scaffold(containerColor = colorResource(id = R.color.teal_100), topBar = {
        TopAppBar(
            title = stringResource(id = R.string.call_screen),
            signInButtonText = signInButtonText,
            onSignInClick = onSignInClick,
            onIconClick = onNavigateToMain
        )
    }) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(start = 40.dp, end = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center // Center content initially
        ) {
            item {
                Spacer(modifier = Modifier.height(5.dp))

                OutlinedTextField(
                    value = phoneNumberTextFieldValue,
                    onValueChange = { phoneNumberTextFieldValue = it },
                    label = { Text("Enter Phone Number") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colorResource(id = R.color.purple_accent),
                        unfocusedBorderColor = colorResource(id = R.color.purple_accent),
                        focusedLabelColor = colorResource(id = R.color.teal_700),
                        unfocusedLabelColor = colorResource(id = R.color.teal_900),
                        cursorColor = colorResource(id = R.color.black)
                    ),
                )

                Spacer(modifier = Modifier.height(16.dp))

                DigitSelector(onDigitClick = { digit ->
                    val newText = phoneNumberTextFieldValue.text + digit
                    phoneNumberTextFieldValue = TextFieldValue(
                        text = newText,
                        selection = TextRange(newText.length) // Set cursor to the end
                    )
                })

                Spacer(modifier = Modifier.height(16.dp))

                MakeCallButton(
                    phoneNumber = phoneNumberTextFieldValue.text
                )

                Spacer(modifier = Modifier.height(26.dp))

                CallScreenButton(text = "Load Recent Calls", onClick = {
                    showCallLogDialog = true
                    if (ContextCompat.checkSelfPermission(
                            context, Manifest.permission.READ_CALL_LOG
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        recentCalls.clear()
                        recentCalls.addAll(getCallLog(context))
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            requestCallLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
                        }
                    }
                }, icon = R.drawable.baseline_checklist_24, iconTint = Color.White)

                Spacer(modifier = Modifier.height(16.dp))

                Log.d("CallScreen", "telecomManager = $telecomManager")
                if (!isDefaultDialer) {
                    SetDefaultDialerButton()
                } else {
                    Text(text = "Is the default dialer")
                }
                Spacer(modifier = Modifier.height(8.dp))
            } // End of the "item" block
        }
    }

    if (showCallLogDialog) {
        AlertDialog(onDismissRequest = { showCallLogDialog = false }, title = {
            Text(text = "Recent Calls")
        }, text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(recentCalls) { call ->
                    CallLogItem(call = call, onCallClick = { selectedNumber ->
                        phoneNumberTextFieldValue = TextFieldValue(
                            text = selectedNumber,
                            selection = TextRange(selectedNumber.length) // Cursor at the end
                        )
                        showCallLogDialog = false
                    })
                }
            }
        }, confirmButton = {
            CallScreenButton(
                text = "Close",
                onClick = { showCallLogDialog = false },
                icon = R.drawable.exo_icon_close
            )
        })
    }
}


@Composable
fun CallLogItem(call: CallLogEntry, onCallClick: (String) -> Unit) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .clickable { onCallClick(call.number) }
        .padding(8.dp)) {
        Column {
            Text(text = "${call.name ?: "Unknown"}: ${call.number}")
            Text(text = "${call.date}, Type: ${call.type}, Duration: ${call.duration}")
        }
    }
}

@Composable
fun DigitSelector(onDigitClick: (String) -> Unit) {
    val digits = listOf(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxWidth()
            .height(270.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        contentPadding = PaddingValues(top = 0.dp, bottom = 0.dp),
    ) {
        items(digits) { digit ->
            CallScreenButton(
                text = digit,
                onClick = { onDigitClick(digit) },
                widthModifier = Modifier.fillMaxWidth(),
                roundedCornerRadius = 18.dp,
                bottomPadding = 10.dp,
            )
        }
    }
}

fun getCallLog(context: Context): List<CallLogEntry> {
    val callLogEntries = mutableListOf<CallLogEntry>()
    if (ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALL_LOG
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        Log.e("CallScreen", "READ_CALL_LOG permission not granted")
        return callLogEntries
    }
    val projection = arrayOf(
        CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION
    )
    val cursor: Cursor? = context.contentResolver.query(
        CallLog.Calls.CONTENT_URI, projection, null, null, CallLog.Calls.DATE + " DESC"
    )
    cursor?.use {
        val numberIndex = it.getColumnIndex(CallLog.Calls.NUMBER)
        val dateIndex = it.getColumnIndex(CallLog.Calls.DATE)
        val typeIndex = it.getColumnIndex(CallLog.Calls.TYPE)
        val durationIndex = it.getColumnIndex(CallLog.Calls.DURATION)

        while (it.moveToNext()) {
            val number = it.getString(numberIndex)
            val name = getContactName(context, number)
            val date = it.getLong(dateIndex)
            val type = it.getInt(typeIndex)
            val duration = it.getString(durationIndex)

            val formattedDate =
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(date))
            val callType = when (type) {
                CallLog.Calls.INCOMING_TYPE -> "Incoming"
                CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                CallLog.Calls.MISSED_TYPE -> "Missed"
                else -> "Unknown"
            }
            callLogEntries.add(CallLogEntry(number, name, formattedDate, callType, duration))
        }
    }
    return callLogEntries
}

fun getContactName(context: Context, phoneNumber: String): String? {
    val uri = Uri.withAppendedPath(
        ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber)
    )
    val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
    var contactName: String? = null
    val cursor = context.contentResolver.query(uri, projection, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
            contactName = it.getString(nameIndex)
        }
    }
    return contactName
}

@Composable
fun MakeCallButton(phoneNumber: String) {
    val context = LocalContext.current
    var callAttempted by remember { mutableStateOf(false) }

    val requestPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i("CallScreen", "Permission granted")
                if (callAttempted) {
                    PhoneUtils.placeCall(
                        phoneNumber, context
                    )
                }
            } else {
                Log.i("CallScreen", "Permission denied")
            }
        }

    CallScreenButton(
        text = stringResource(id = R.string.make_a_call),
        onClick = {
            callAttempted = true
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CALL_PHONE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                PhoneUtils.placeCall(
                    phoneNumber, context
                )
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
            }
        },
        icon = R.drawable.baseline_call_24,
        iconTint = colorResource(id = R.color.black),
        width = 100.dp,
        roundedCornerRadius = 60.dp,
        contentPadding = 18.dp
    )
}

@Composable
fun SetDefaultDialerButton() {
    val context = LocalContext.current
    val requestRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.i("CallScreen", "Successfully set as default dialer")
        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            Log.i("CallScreen", "User canceled setting as default dialer")
        } else {
            Log.e("CallScreen", "Failed to set as default dialer")
        }
    }

    CallScreenButton(
        text = stringResource(id = R.string.set_as_default_dialer),
        onClick = {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
            if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    requestRoleLauncher.launch(intent)
                } else {
                    Log.i("CallScreen", "Already default dialer")
                }
            } else {
                Log.e("CallScreen", "Dialer role not available")
            }
        },
        widthModifier = Modifier.wrapContentWidth(),
        contentPadding = 16.dp,
        icon = R.drawable.baseline_check_circle_24,
        iconTint = Color.Green
    )
}

@Composable
fun CallScreenButton(
    text: String,
    onClick: () -> Unit,
    icon: Int? = null,
    iconTint: Color = colorResource(id = R.color.teal_900),
    width: Dp = 180.dp,
    widthModifier: Modifier? = null,
    enabled: Boolean = true,
    contentPadding: Dp = 16.dp,
    roundedCornerRadius: Dp = 8.dp,
    bottomPadding: Dp = 30.dp
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .then(
                widthModifier ?: Modifier.width(width)
            )
            .padding(bottom = bottomPadding, top = 0.dp)
            .drawBehind {
                val shadowColor = Color.Black
                val transparentColor = Color.Transparent
                val shadowRadius = 8.dp.toPx()
                val offset = shadowRadius / 2 // Offset downwards

                val paint = Paint().apply {
                    this.color = transparentColor
                    this.isAntiAlias = true
                    this.asFrameworkPaint().setShadowLayer(
                        shadowRadius, // Half of height
                        0f, // No horizontal offset
                        offset, // Vertical offset
                        shadowColor.toArgb()
                    )
                }
                drawIntoCanvas { canvas ->
                    canvas.drawRoundRect(
                        left = 0f,
                        top = offset, // Anchor is 0
                        right = size.width,
                        bottom = size.height, // Draw to the bottom of the button
                        radiusX = roundedCornerRadius.toPx(),
                        radiusY = roundedCornerRadius.toPx(),
                        paint = paint

                    )
                }
            },
        colors = ButtonDefaults.buttonColors(
            containerColor = colorResource(id = R.color.teal_350),
            contentColor = colorResource(id = R.color.teal_900),
            disabledContainerColor = colorResource(id = R.color.light_grey),
            disabledContentColor = colorResource(id = R.color.black)
        ),
        shape = RoundedCornerShape(roundedCornerRadius),
        enabled = enabled,
        contentPadding = PaddingValues(contentPadding),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                colorResource(id = R.color.purple_accent)
            ), width = 2.dp
        ),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(text = text)
    }
}

@SuppressLint("UnrememberedMutableState")
@Composable
@Preview
fun CallScreenPreview() {
    DialerScreen(
        onNavigateToMain = { },
        telecomManager = null,
        signInButtonText = mutableStateOf("Sign In"),
        onSignInClick = {},
    )
}