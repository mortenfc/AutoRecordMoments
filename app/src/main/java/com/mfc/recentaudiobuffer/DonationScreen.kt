package com.mfc.recentaudiobuffer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextField
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale

enum class SignInButtonViewState {
    Hidden, Ready
}

@Composable
fun DonationScreen(
    onSignInClick: () -> Unit,
    onPayClick: (Int) -> Unit,
    onCardPayClick: (Int) -> Unit,
    signInButtonText: MutableState<String>,
    signInButtonViewState: MutableState<SignInButtonViewState>,
    isGooglePayReady: MutableState<Boolean>
) {
    val context = LocalContext.current
    val passContainerVisible by remember { mutableStateOf(false) }
    var donationAmount by remember { mutableStateOf("") }
    var isError by rememberSaveable { mutableStateOf(false) }
    val isLoggedIn: Boolean = (signInButtonText.value == "Sign Out")

    ConstraintLayout(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.teal_350))
    ) {
        val (contentColumn) = createRefs()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 25.dp, end = 25.dp)
                .constrainAs(contentColumn) {
                    top.linkTo(parent.top)
                }, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.donation_banner),
                contentDescription = stringResource(id = R.string.donate_ads_away),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp)
                    .padding(top = 25.dp)
            )

            Text(
                text = stringResource(id = R.string.donate_your_heart_out),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 30.dp, start = 5.dp)
                    .align(Alignment.Start),
                color = colorResource(R.color.black)
            )

            Text(
                text = stringResource(id = R.string.it_supports_the_developer_and_gets_rid_of_ads),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(start = 5.dp, top = 5.dp, bottom = 15.dp)
                    .align(Alignment.Start),
                color = MaterialTheme.colorScheme.secondary
            )

            if (isGooglePayReady.value) {
                GoogleSignInButton(onClick = onSignInClick, signInButtonText)

                Spacer(modifier = Modifier.height(30.dp))
            }

            if (isGooglePayReady.value && isLoggedIn) {
                when (signInButtonViewState.value) {
                    SignInButtonViewState.Hidden -> {}
                    SignInButtonViewState.Ready -> {

                        TextField(
                            value = donationAmount,
                            onValueChange = {
                                donationAmount = it
                                isError = it.toIntOrNull() == null || it.toIntOrNull()!! < 5
                            },
                            label = { Text("Donation Amount ≥ 5 SEK") },
                            modifier = Modifier.padding(bottom = 16.dp),
                            isError = isError,
                            singleLine = true,
                            supportingText = {
                                if (isError) {
                                    Text(
                                        modifier = Modifier.fillMaxWidth(),
                                        text = "Error: Amount must be at least 5 SEK",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            },
                        )
                        GooglePayButton(onClick = {
                            val amount = donationAmount.toIntOrNull()
                            if (amount != null && amount >= 5) {
                                isError = false
                                onPayClick(amount)
                            } else {
                                isError = true
                            }
                        })
                    }
                }
            } else if (!isGooglePayReady.value) {
                TextField(
                    value = donationAmount,
                    onValueChange = {
                        donationAmount = it
                        isError = it.toIntOrNull() == null || it.toIntOrNull()!! < 5
                    },
                    label = { Text("Donation Amount ≥ 5 SEK") },
                    modifier = Modifier.padding(bottom = 16.dp),
                    isError = isError,
                    singleLine = true,
                    supportingText = {
                        if (isError) {
                            Text(
                                modifier = Modifier.fillMaxWidth(),
                                text = "Error: Amount must be at least 5 SEK",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                )
                CardPayButton(onClick = {
                    val amount = donationAmount.toIntOrNull()
                    if (amount != null && amount >= 5) {
                        isError = false
                        onCardPayClick(amount)
                    } else {
                        isError = true
                    }
                })
            }

            Spacer(modifier = Modifier.height(15.dp))

            if (passContainerVisible) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.or_add_a_pass_to_your_google_wallet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.align(Alignment.Start)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    AddToGoogleWalletButton(context)
                }
            }
        }
    }
}

@Composable
fun GoogleSignInButton(onClick: () -> Unit, signInButtonText: MutableState<String>) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .width(140.dp)
            .height(48.dp)
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(4.dp)),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White, contentColor = Color.Black // Text color
        ),
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_google_logo),
                contentDescription = stringResource(id = R.string.google_logo),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = if (signInButtonText.value == "Sign In") stringResource(id = R.string.sign_in) else stringResource(
                    id = R.string.sign_out
                ), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.DarkGray
            )
        }
    }
}

@Composable
fun GooglePayButton(onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier
            .width(150.dp)
            .height(48.dp)
            .drawBehind {
                val shadowColor = Color.White
                val transparentColor = Color.Transparent
                val shadowRadius = 4.dp.toPx()
                val offset = 2.dp.toPx() // Offset downwards

                val paint = Paint().apply {
                    this.color = transparentColor
                    this.isAntiAlias = true
                    this
                        .asFrameworkPaint()
                        .setShadowLayer(
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
                        radiusX = size.height / 2, // Half the height for rounded corners
                        radiusY = size.height / 2, // Half the height for rounded corners
                        paint = paint
                    )
                }
            }
            .clip(CircleShape) // Fully rounded corners
            .background(Color.Black),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent, // Make container transparent
            contentColor = Color.White // Text color
        ),
        shape = CircleShape
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_google_logo), // Google Pay logo
                contentDescription = stringResource(id = R.string.google_logo),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(1.dp))
            Text(
                text = stringResource(id = R.string.pay),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}

@Composable
fun CardPayButton(onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier
            .width(200.dp)
            .height(48.dp)
            .drawBehind {
                val shadowColor = Color.White
                val transparentColor = Color.Transparent
                val shadowRadius = 4.dp.toPx()
                val offset = 2.dp.toPx() // Offset downwards

                val paint = Paint().apply {
                    this.color = transparentColor
                    this.isAntiAlias = true
                    this
                        .asFrameworkPaint()
                        .setShadowLayer(
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
                        radiusX = size.height / 2, // Half the height for rounded corners
                        radiusY = size.height / 2, // Half the height for rounded corners
                        paint = paint
                    )
                }
            }
            .clip(CircleShape) // Fully rounded corners
            .background(Color.Black),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent, // Make container transparent
            contentColor = Color.White // Text color
        ),
        shape = CircleShape
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.baseline_credit_card_24),
                contentDescription = stringResource(id = R.string.pay_with_credit_card),
                modifier = Modifier.size(24.dp),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(colorResource(id = R.color.teal_900))
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = stringResource(id = R.string.pay_with_credit_card),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}

@Composable
fun AddToGoogleWalletButton(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        data =
            Uri.parse("https://pay.google.com/gp/v/save/YOUR_JWT_HERE") // Replace with your actual JWT link
    }
    val backgroundPainter: Painter =
        painterResource(id = R.drawable.add_to_google_wallet_button_background)
    Box(modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .drawBehind {
            with(backgroundPainter) {
                draw(size)
            }
        }
        .clickable {
            context.startActivity(intent)
        }, contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.add_to_google_wallet_button_content),
            contentDescription = stringResource(id = R.string.add_to_google_wallet_button_content_description),
            modifier = Modifier
                .width(200.dp)
                .height(26.dp),
            contentScale = ContentScale.FillBounds
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DonationScreenPreview() {
    val signInTextState = remember { mutableStateOf("Sign Out") }
    val signInButtonViewState = remember { mutableStateOf(SignInButtonViewState.Ready) }
    val isGooglePayReady = remember { mutableStateOf(false) }
    DonationScreen({}, {}, {}, signInTextState, signInButtonViewState, isGooglePayReady)
}