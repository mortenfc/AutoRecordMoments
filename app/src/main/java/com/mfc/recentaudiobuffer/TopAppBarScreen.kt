package com.mfc.recentaudiobuffer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp

@Composable
fun GoogleSignInButton(onClick: () -> Unit, signInButtonText: MutableState<String>) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .wrapContentWidth() // Fit to content of Button, which is Row
            .height(48.dp)
            .padding(horizontal = 0.dp) // Reduced horizontal
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(4.dp)),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White, contentColor = Color.Black // Text color
        ),
        shape = RoundedCornerShape(4.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier
                .wrapContentWidth() // Fit to content of Row
                .padding(horizontal = 0.dp), // Reduced horizontal
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(6.dp))
            Image(
                painter = painterResource(id = R.drawable.ic_google_logo),
                contentDescription = stringResource(id = R.string.google_logo),
                modifier = Modifier
                    .size(24.dp)
                    .padding(0.dp)
            )
            Text(
                text = if (signInButtonText.value == "Sign In") stringResource(id = R.string.sign_in) else stringResource(
                    id = R.string.sign_out
                ), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.DarkGray
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
    }

    Spacer(modifier = Modifier.width(6.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBar(
    title: String,
    signInButtonText: MutableState<String>,
    onSignInClick: () -> Unit,
    onBackButtonClicked: (() -> Unit)? = null, // Optional back button
    onSettingsClick: (() -> Unit)? = null // Optional settings button
) {
    val toolbarOutlineColor = colorResource(id = R.color.purple_accent)
    val toolbarBackgroundColor = colorResource(id = R.color.teal_350)
    TopAppBar(title = {
        Text(
            text = title, color = colorResource(id = R.color.teal_900)
        )
    }, modifier = Modifier.drawBehind {
        val paint = Paint().apply {
            color = toolbarOutlineColor
            style = PaintingStyle.Stroke
            strokeWidth = 8.dp.toPx()
        }
        drawIntoCanvas { canvas ->
            canvas.drawRoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                radiusX = 0.dp.toPx(),
                radiusY = 0.dp.toPx(),
                paint = paint
            )
        }
        drawRoundRect(
            color = toolbarBackgroundColor,
            topLeft = Offset(0f, 0f),
            size = size,
            style = Fill,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                0.dp.toPx(), 0.dp.toPx()
            )
        )
    }, navigationIcon = {
        if (onBackButtonClicked != null) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onBackButtonClicked() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(id = R.string.back),
                        tint = Color.White
                    )
                }
            }
        }
    }, colors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent
    ), actions = {
        GoogleSignInButton(onClick = onSignInClick, signInButtonText = signInButtonText)
        if (onSettingsClick != null) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(
                            bounded = true, radius = 24.dp
                        ),
                        onClick = {
                            onSettingsClick()
                        })
                    .padding(12.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.baseline_settings_24),
                    contentDescription = stringResource(id = R.string.settings),
                    tint = Color.White
                )
            }
        }
    })
}

@Preview(showBackground = true)
@Composable
fun TopAppBarNoBackButtonPreview() {
    val signInButtonText = remember { mutableStateOf("Sign In") }
    TopAppBar(title = "My App",
        signInButtonText = signInButtonText,
        onSignInClick = {},
        onSettingsClick = {})
}

@Preview(showBackground = true)
@Composable
fun TopAppBarNoSettingsButtonPreview() {
    val signInButtonText = remember { mutableStateOf("Sign In") }
    TopAppBar(title = "My App",
        signInButtonText = signInButtonText,
        onSignInClick = {},
        onBackButtonClicked = {})
}