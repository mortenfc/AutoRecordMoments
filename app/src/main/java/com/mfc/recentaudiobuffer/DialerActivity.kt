package com.mfc.recentaudiobuffer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mfc.recentaudiobuffer.ui.theme.RecentAudioBufferTheme

class DialerActivity : ComponentActivity() {
    private lateinit var phoneNumberState: MutableState<TextFieldValue>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RecentAudioBufferTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    DialerScreen()
                }
            }
        }
    }

    private val requestCallPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Permission is granted. Place the call.
                // Get the phone number from the text field
                val phoneNumber = phoneNumberState.value.text
                val telecomManager = PhoneUtils.getTelecomManager(this)
                PhoneUtils.placeCall(
                    this,
                    telecomManager,
                    phoneNumber,
                    PhoneUtils.getPhoneAccountHandle(this, telecomManager)
                )
            } else {
                // Permission is denied. Handle the error.
                // You can show a message to the user or disable the call button.
            }
        }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun DialerScreen() {
        phoneNumberState = remember { mutableStateOf(TextFieldValue("")) }
        val context = LocalContext.current
        val telecomManager = PhoneUtils.getTelecomManager(context)

        Scaffold(topBar = {
            TopAppBar(title = { Text(text = stringResource(id = R.string.app_name)) })
        }) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                PhoneNumberTextField(phoneNumber = phoneNumberState.value,
                    onPhoneNumberChange = { phoneNumberState.value = it })
                DialPad(onDigitClick = { digit ->
                    phoneNumberState.value = TextFieldValue(phoneNumberState.value.text + digit)
                })
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.CALL_PHONE
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            // Request the permission
                            requestCallPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                            return@Button
                        }
                        val phoneNumber = phoneNumberState.value.text
                        PhoneUtils.placeCall(
                            context,
                            telecomManager,
                            phoneNumber,
                            PhoneUtils.getPhoneAccountHandle(context, telecomManager)
                        )
                    }) {
                        Text(text = stringResource(id = R.string.call))
                    }
                    PhoneUtils.MakeCallButton(telecomManager, phoneNumberState.value.text)
                }
                val isDefaultDialer =
                    telecomManager?.let { PhoneUtils.isDefaultDialer(context, it) } ?: false

                Log.d("CallScreen", "telecomManager = $telecomManager")
                if (telecomManager != null && !isDefaultDialer) {
                    PhoneUtils.SetDefaultDialerButton()
                } else {
                    if (isDefaultDialer) {
                        Text(text = "Is the default dialer")
                    }
                }
            }
        }
    }

    @Composable
    fun PhoneNumberTextField(
        phoneNumber: TextFieldValue, onPhoneNumberChange: (TextFieldValue) -> Unit
    ) {
        TextField(value = phoneNumber, onValueChange = {
            onPhoneNumberChange(it)
        }, label = {
            Text(text = stringResource(id = R.string.phone_number))
        }, modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
        )
    }

    @Composable
    fun DialPad(onDigitClick: (String) -> Unit) {
        Column(
            modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                DialButton(digit = "1", onDigitClick = onDigitClick)
                DialButton(digit = "2", onDigitClick = onDigitClick)
                DialButton(digit = "3", onDigitClick = onDigitClick)
            }
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                DialButton(digit = "4", onDigitClick = onDigitClick)
                DialButton(digit = "5", onDigitClick = onDigitClick)
                DialButton(digit = "6", onDigitClick = onDigitClick)
            }
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                DialButton(digit = "7", onDigitClick = onDigitClick)
                DialButton(digit = "8", onDigitClick = onDigitClick)
                DialButton(digit = "9", onDigitClick = onDigitClick)
            }
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                DialButton(digit = "*", onDigitClick = onDigitClick)
                DialButton(digit = "0", onDigitClick = onDigitClick)
                DialButton(digit = "#", onDigitClick = onDigitClick)
            }
        }
    }

    @Composable
    fun DialButton(digit: String, onDigitClick: (String) -> Unit) {
        Button(onClick = { onDigitClick(digit) }, modifier = Modifier.padding(8.dp)) {
            Text(text = digit)
        }
    }
}