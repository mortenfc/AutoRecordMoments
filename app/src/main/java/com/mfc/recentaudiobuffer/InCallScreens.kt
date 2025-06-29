package com.mfc.recentaudiobuffer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
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
                icon = R.drawable.outline_call_end_24,
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
            text = "Dialing",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = colorResource(id = R.color.teal_900)
        )
        Spacer(modifier = Modifier.height(24.dp))
        AnimatedDots()
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
            icon = R.drawable.outline_call_end_24,
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
    onMute: (Boolean) -> Unit,
    onSpeakerphone: (Boolean) -> Unit,
    onHold: (Boolean) -> Unit,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            CallScreenButton(
                text = if (isMuted) "Unmute" else "Mute",
                onClick = {
                    isMuted = !isMuted
                    onMute(isMuted) // Pass the updated state
                },
                icon = if (isMuted) R.drawable.baseline_mic_off_24 else R.drawable.baseline_mic_24,
                iconTint = Color.White,
                width = 140.dp,
                roundedCornerRadius = 60.dp,
                contentPadding = 18.dp
            )
            Spacer(modifier = Modifier.width(16.dp))
            CallScreenButton(
                text = if (isSpeakerOn) "Earpiece" else "Speaker",
                onClick = {
                    isSpeakerOn = !isSpeakerOn
                    onSpeakerphone(isSpeakerOn) // Pass the updated state
                },
                icon = if (isSpeakerOn) R.drawable.twotone_hearing_24 else R.drawable.outline_speaker,
                iconTint = Color.White,
                width = 140.dp,
                roundedCornerRadius = 60.dp,
                contentPadding = 18.dp
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        CallScreenButton(
            text = if (isHolding) "Unhold" else "Hold",
            onClick = {
                isHolding = !isHolding
                onHold(isHolding) // Pass the updated state
            },
            icon = if (isHolding) R.drawable.baseline_play_circle_outline_24 else R.drawable.baseline_stop_circle_24,
            iconTint = Color.White,
            width = 100.dp,
            roundedCornerRadius = 40.dp,
            contentPadding = 12.dp
        )
        Spacer(modifier = Modifier.height(24.dp))
        CallScreenButton(
            text = "End Call",
            onClick = onEndCall,
            icon = R.drawable.outline_call_end_24,
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

@Composable
fun DisconnectingCallScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.teal_100))
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Disconnecting",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = colorResource(id = R.color.teal_900)
        )
        Spacer(modifier = Modifier.height(24.dp))
        AnimatedDots()
    }
}

@Composable
fun AnimatedDots() {
    val dotScales = listOf(remember { Animatable(1f) }, remember { Animatable(1f) }, remember { Animatable(1f) })

    LaunchedEffect(Unit) {
        while (true) {
            for (i in dotScales.indices) {
                dotScales[i].animateTo(
                    targetValue = 1.5f,
                    animationSpec = tween(durationMillis = 300, easing = LinearEasing)
                )
                delay(100)
                dotScales[i].animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 300, easing = LinearEasing)
                )
                delay(100)
            }
        }
    }

    Row(horizontalArrangement = Arrangement.Center) {
        for (i in dotScales.indices) {
            Text(
                text = ".",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = colorResource(id = R.color.teal_900),
                modifier = Modifier
                    .scale(dotScales[i].value)
            )
        }
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

@Preview(showBackground = true)
@Composable
fun InCallScreenPreview() {
    InCallScreen(
        name = "Annie",
        phoneNumber = "+46772345123",
        callDuration = 23,
        onMute = {},
        onSpeakerphone = {},
        onHold = {},
        onEndCall = {}
    )
}

@Preview(showBackground = true)
@Composable
fun DisconnectingCallScreenPreview() {
    DisconnectingCallScreen()
}