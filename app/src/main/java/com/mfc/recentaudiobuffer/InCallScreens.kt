package com.mfc.recentaudiobuffer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun IncomingCallScreen(
    callerName: String?,
    phoneNumber: String,
    callDuration: Long,
    onAnswer: () -> Unit,
    onReject: () -> Unit
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
        Spacer(modifier = Modifier.height(24.dp))
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
        Spacer(modifier = Modifier.height(24.dp))
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

@Composable
fun InCallScreen(
    name: String?,
    phoneNumber: String,
    callDuration: Long,
    onMute: () -> Unit,
    onSpeakerphone: () -> Unit,
    onHold: () -> Unit,
    onEndCall: () -> Unit
) {
    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }
    var isHolding by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.teal_100))
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Active Call",
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
        Spacer(modifier = Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (isMuted) {
                CallScreenButton(text = "Unmute", onClick = {
                    onMute()
                    isMuted = false
                })
            } else {
                CallScreenButton(text = "Mute", onClick = {
                    onMute()
                    isMuted = true
                })
            }
            if (isSpeakerOn) {
                CallScreenButton(text = "Earpiece", onClick = {
                    onSpeakerphone()
                    isSpeakerOn = false
                })
            } else {
                CallScreenButton(text = "Speaker", onClick = {
                    onSpeakerphone()
                    isSpeakerOn = true
                })
            }
            if (isHolding) {
                CallScreenButton(text = "Unhold", onClick = {
                    onHold()
                    isHolding = false
                })
            } else {
                CallScreenButton(text = "Hold", onClick = {
                    onHold()
                    isHolding = true
                })
            }
            CallScreenButton(text = "End Call", onClick = onEndCall)
        }
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