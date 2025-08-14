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

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Stateful TopAppBar. Your app calls this.
 * It gets the ViewModel and collects state.
 */
@Composable
fun TopAppBar(
    title: String,
    onBackButtonClicked: (() -> Unit)? = null,
    onIconClick: (() -> Unit)? = onBackButtonClicked,
    onSettingsClick: (() -> Unit)? = null,
    viewModel: IAuthViewModel = hiltViewModel<AuthViewModel>()
) {
    val isSigningIn by viewModel.isSigningIn.collectAsStateWithLifecycle()
    val authError by viewModel.authError.collectAsStateWithLifecycle()
    val signInButtonText by viewModel.signInButtonText

    TopAppBarContent(
        title = title,
        signInButtonText = signInButtonText,
        isSigningIn = isSigningIn,
        authError = authError,
        onSignInClick = { viewModel.onSignInClick(it) },
        onDismissErrorDialog = { viewModel.clearAuthError() },
        onBackButtonClicked = onBackButtonClicked,
        onIconClick = onIconClick,
        onSettingsClick = onSettingsClick
    )
}

/**
 * Stateless TopAppBar. This is what previews call.
 * It's a "dumb" component that just displays the state it's given.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBarContent(
    title: String,
    signInButtonText: String,
    isSigningIn: Boolean,
    authError: AuthError?,
    onSignInClick: (Activity) -> Unit,
    onDismissErrorDialog: () -> Unit,
    onBackButtonClicked: (() -> Unit)?,
    onIconClick: (() -> Unit)?,
    onSettingsClick: (() -> Unit)?
) {
    val context = LocalContext.current

    when (authError) {
        is AuthError.NoAccountsFound -> {
            NoAccountsFoundDialog(onDismissErrorDialog = onDismissErrorDialog)
        }

        is AuthError.Generic -> {
            SignInErrorDialog(errorMessage = authError.message, onDismiss = onDismissErrorDialog)
        }

        null -> {}
    }

    val toolbarOutlineColor = colorResource(id = R.color.purple_accent)
    val toolbarBackgroundColor = colorResource(id = R.color.teal_350)

    TopAppBar(
        title = { Text(text = title, color = colorResource(id = R.color.teal_900)) },
        navigationIcon = {
            Row(modifier = Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBackButtonClicked != null) {
                    IconButton(onClick = { onBackButtonClicked() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(onClick = { onIconClick?.invoke() }) {
                    Icon(
                        painter = painterResource(id = R.drawable.very_teal_fancy_simple_smiley_wave),
                        contentDescription = "Home",
                        tint = Color.Unspecified,
                        modifier = Modifier.border(
                            1.dp, colorResource(R.color.purple_accent), CircleShape
                        )
                    )
                }
            }
        },
        modifier = Modifier.drawBehind {
            drawToolbarBackground(
                toolbarOutlineColor, toolbarBackgroundColor
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        actions = {
            GoogleSignInButton(
                onClick = { onSignInClick(context as Activity) },
                signInButtonText = signInButtonText,
                isEnabled = !isSigningIn
            )
            if (onSettingsClick != null) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, radius = 24.dp),
                            onClick = { onSettingsClick() })
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(id = R.drawable.baseline_settings_24),
                        "Settings",
                        tint = Color.White
                    )
                }
            }
        })
}

// GoogleSignInButton now takes a simple String and Boolean
@Composable
fun GoogleSignInButton(onClick: () -> Unit, signInButtonText: String, isEnabled: Boolean) {
    Button(
        onClick = onClick,
        enabled = isEnabled,
        modifier = Modifier
            .wrapContentWidth()
            .height(48.dp)
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(4.dp)),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
            disabledContainerColor = colorResource(id = R.color.light_grey)
        ),
        shape = RoundedCornerShape(4.dp),
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(id = R.drawable.ic_google_logo),
                "Google logo",
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = if (signInButtonText == "Sign In") stringResource(id = R.string.sign_in) else stringResource(
                    id = R.string.sign_out
                ), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.Black
            )
        }
    }
}


private fun DrawScope.drawToolbarBackground(
    toolbarOutlineColor: Color, toolbarBackgroundColor: Color
) {
    val strokeWidthPx = 3.dp.toPx()

    // 1. Draw the main background fill for the entire TopAppBar.
    drawRect(
        color = toolbarBackgroundColor, size = size
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
fun TopAppBarContentPreview() {
    TopAppBarContent(
        title = "Preview",
        signInButtonText = "Sign In",
        isSigningIn = false,
        authError = null,
        onSignInClick = {},
        onDismissErrorDialog = {},
        onBackButtonClicked = {},
        onIconClick = {},
        onSettingsClick = {})
}

@Preview(showBackground = true)
@Composable
fun TopAppBarNoBackButtonPreview() {
    // 1. Define the state for this preview: Signed Out
    val fakeViewModel = FakeAuthViewModel(
        initialIsSigningIn = false, initialAuthError = null, initialSignInText = "Sign In"
    )

    // 2. Pass the fake ViewModel to the composable
    TopAppBar(
        title = "Main", viewModel = fakeViewModel, onSettingsClick = {})
}

@Preview(showBackground = true)
@Composable
fun TopAppBarNoSettingsButtonPreview() {
    // 1. Define the state for this preview: Signed In
    val fakeViewModel = FakeAuthViewModel(
        initialIsSigningIn = false, initialAuthError = null, initialSignInText = "Sign Out"
    )

    // 2. Pass the fake ViewModel to the composable
    TopAppBar(
        title = "Settings", viewModel = fakeViewModel, onBackButtonClicked = {})
}

@Preview(showBackground = true)
@Composable
fun TopAppBarWithErrorDialogPreview() {
    // 1. Define the state for this preview: Error occurred
    val fakeViewModel = FakeAuthViewModel(
        initialIsSigningIn = false,
        initialAuthError = AuthError.Generic("Network connection failed. Please try again."),
        initialSignInText = "Sign In"
    )

    // 2. Pass the fake ViewModel to the composable
    TopAppBar(
        title = "Error Preview", viewModel = fakeViewModel, onSettingsClick = {})
}

@Preview(showBackground = true)
@Composable
fun TopAppBarLoadingPreview() {
    val fakeViewModel = FakeAuthViewModel(
        initialIsSigningIn = true, // <-- The button will be disabled
        initialAuthError = null, initialSignInText = "Sign In"
    )
    TopAppBar(
        title = "Loading...", viewModel = fakeViewModel
    )
}

@Preview(showBackground = true)
@Composable
fun GoogleSignInButtonDisabledPreview() {
    val signInButtonText = "Sign In"
    GoogleSignInButton(
        signInButtonText = signInButtonText, onClick = {}, isEnabled = false
    )
}