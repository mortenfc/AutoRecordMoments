/*
 * Auto Record Moments
 * Copyright (C) 2025 Morten Fjord Christensen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.mfc.recentaudiobuffer

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
    onIconClick: (() -> Unit)? = null,
    onBackButtonClicked: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
    authError: String? = null, // New parameter for auth error message
    onDismissErrorDialog: () -> Unit = {} // New lambda to handle dismissal
) {
    // When authError is not null, display the dialog.
    // The dialog is a full-screen overlay, so it's invoked here at the top level
    // of the composable. It won't interfere with the TopAppBar's layout.
    if (authError != null) {
        SignInErrorDialog(errorMessage = authError, onDismiss = onDismissErrorDialog)
    }

    val toolbarOutlineColor = colorResource(id = R.color.purple_accent)
    val toolbarBackgroundColor = colorResource(id = R.color.teal_350)

    // The visual TopAppBar component's implementation remains the same.
    TopAppBar(
        title = {
            Text(
                text = title, color = colorResource(id = R.color.teal_900)
            )
        },
        navigationIcon = {
            Row(
                modifier = Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBackButtonClicked != null) {
                    IconButton(onClick = { onBackButtonClicked() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.back),
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(onClick = {
                    if (onIconClick != null) {
                        onIconClick()
                    }
                }) {
                    Icon(
                        painter = painterResource(id = R.drawable.simple_abstract_black_orange_icon),
                        contentDescription = "Cool",
                        tint = Color.Unspecified
                    )
                }
            }
        },
        modifier = Modifier.drawBehind {
            drawToolbarBackground(
                toolbarOutlineColor,
                toolbarBackgroundColor
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent
        ),
        actions = {
            GoogleSignInButton(onClick = onSignInClick, signInButtonText = signInButtonText)
            if (onSettingsClick != null) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
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

private fun DrawScope.drawToolbarBackground(
    toolbarOutlineColor: Color,
    toolbarBackgroundColor: Color
) {
    val strokeWidthPx = 3.dp.toPx()

    // 1. Draw the main background fill for the entire TopAppBar.
    drawRect(
        color = toolbarBackgroundColor,
        size = size
    )

    // 2. Calculate the Y position to center the line just inside the bottom edge.
    //    This ensures the entire line is visible.
    val y = size.height - (strokeWidthPx / 2)

    // 3. Draw only the bottom border line.
    drawLine(
        color = toolbarOutlineColor,
        start = Offset(x = 0f, y = y),
        end = Offset(x = size.width, y = y),
        strokeWidth = strokeWidthPx
    )
}

@Preview(showBackground = true)
@Composable
fun TopAppBarNoBackButtonPreview() {
    val signInButtonText = remember { mutableStateOf("Sign In") }
    TopAppBar(
        title = "Main",
        signInButtonText = signInButtonText,
        onSignInClick = {},
        onSettingsClick = {})
}

@Preview(showBackground = true)
@Composable
fun TopAppBarNoSettingsButtonPreview() {
    val signInButtonText = remember { mutableStateOf("Sign Out") }
    TopAppBar(
        title = "Settings",
        signInButtonText = signInButtonText,
        onSignInClick = {},
        onBackButtonClicked = {})
}

@Preview(showBackground = true)
@Composable
fun TopAppBarWithErrorDialogPreview() {
    val signInButtonText = remember { mutableStateOf("Sign In") }
    TopAppBar(
        title = "Error Preview",
        signInButtonText = signInButtonText,
        onSignInClick = {},
        authError = "Network connection failed. Please try again.",
        onDismissErrorDialog = {}
    )
}