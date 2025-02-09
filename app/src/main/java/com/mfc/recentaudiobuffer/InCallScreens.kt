package com.mfc.recentaudiobuffer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.TimeUnit

class IncomingCallFullScreenActivity : ComponentActivity() {
    private var callerName by mutableStateOf<String?>(null)
    private var phoneNumber by mutableStateOf<String?>(null)
    private var callHandle by mutableStateOf<Uri?>(null)
    private var isIncomingCall by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
            var callDuration by remember { mutableLongStateOf(0L) }
            val callStartTime =
                if (callHandle != null) RecentAudioBufferApplication.getSharedViewModel().myInCallService?.getCallStartTime(
                    callHandle!!
                ) else null
            LaunchedEffect(key1 = callStartTime) {
                if (callStartTime != null) {
                    while (true) {
                        delay(1000L)
                        callDuration = System.currentTimeMillis() - callStartTime
                    }
                }
            }
            if (isIncomingCall) {
                IncomingCallScreen(callerName = callerName ?: "Unknown",
                    phoneNumber = phoneNumber ?: "Unknown",
                    callDuration = callDuration,
                    onAnswer = {
                        // Handle answer action here
                        if (callHandle != null) {
                            RecentAudioBufferApplication.getSharedViewModel().myInCallService?.answerCall(
                                callHandle!!
                            )
                        }
                        finish()
                    },
                    onReject = {
                        // Handle reject action here
                        if (callHandle != null) {
                            RecentAudioBufferApplication.getSharedViewModel().myInCallService?.rejectCall(
                                callHandle!!
                            )
                        }
                        finish()
                    })
            } else {
                OutgoingCallScreen(name = callerName ?: "Unknown",
                    phoneNumber = phoneNumber ?: "Unknown",
                    callDuration = callDuration,
                    onEndCall = {
                        if (callHandle != null) {
                            RecentAudioBufferApplication.getSharedViewModel().myInCallService?.rejectCall(
                                callHandle!!
                            )
                        }
                        // Handle end call action here
                        finish()
                    })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        callerName = intent.getStringExtra("callerName")
        phoneNumber = intent.getStringExtra("phoneNumber")
        val callHandleString = intent.getStringExtra(NotificationActionReceiver.EXTRA_CALL_HANDLE)
        callHandle = if (callHandleString != null) Uri.parse(callHandleString) else null
        isIncomingCall = intent.getBooleanExtra("isIncomingCall", false)
    }
}

@Composable
fun IncomingCallScreen(
    callerName: String?, phoneNumber: String, callDuration: Long, onAnswer: () -> Unit, onReject: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.teal_100))
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Incoming Call",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = colorResource(id = R.color.teal_900)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = formatDuration(callDuration),
            fontSize = 18.sp,
            color = colorResource(id = R.color.teal_900)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = callerName ?: "Unknown",
            fontSize = 20.sp,
            color = colorResource(id = R.color.teal_900)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = phoneNumber, fontSize = 18.sp, color = colorResource(id = R.color.teal_900))
        Spacer(modifier = Modifier.height(32.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            CallScreenButton(
                text = "Answer",
                onClick = onAnswer,
                icon = R.drawable.baseline_call_24,
                iconTint = Color.White,
                width = 120.dp,
                roundedCornerRadius = 60.dp,
                contentPadding = 18.dp
            )
            CallScreenButton(
                text = "Reject",
                onClick = onReject,
                icon = R.drawable.exo_icon_close,
                iconTint = Color.White,
                width = 120.dp,
                roundedCornerRadius = 60.dp,
                contentPadding = 18.dp
            )
        }
    }
}

@Composable
fun OutgoingCallScreen(
    name: String?, phoneNumber: String, callDuration: Long, onEndCall: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.teal_100))
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Outgoing Call",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = colorResource(id = R.color.teal_900)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = formatDuration(callDuration),
            fontSize = 18.sp,
            color = colorResource(id = R.color.teal_900)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = name ?: "Unknown", fontSize = 20.sp, color = colorResource(id = R.color.teal_900)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = phoneNumber, fontSize = 18.sp, color = colorResource(id = R.color.teal_900))
        Spacer(modifier = Modifier.height(32.dp))
        CallScreenButton(
            text = "End Call",
            onClick = onEndCall,
            icon = R.drawable.exo_icon_close,
            iconTint = Color.White,
            width = 120.dp,
            roundedCornerRadius = 60.dp,
            contentPadding = 18.dp
        )
    }
}

fun formatDuration(durationMillis: Long): String {
    val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMillis) % 60
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis) % 60
    val hours = TimeUnit.MILLISECONDS.toHours(durationMillis)
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

@Preview(showBackground = true)
@Composable
fun IncomingCallScreenPreview() {
    IncomingCallScreen(callerName = "John Doe",
        phoneNumber = "+15551234567",
        callDuration = 129,
        onAnswer = {},
        onReject = {})
}

@Preview(showBackground = true)
@Composable
fun OutgoingCallScreenPreview() {
    OutgoingCallScreen(
        name = "Jane Smith",
        callDuration = 129,
        phoneNumber = "+15559876543",
        onEndCall = {})
}