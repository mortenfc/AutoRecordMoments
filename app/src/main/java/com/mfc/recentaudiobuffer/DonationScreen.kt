package com.mfc.recentaudiobuffer

import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextField
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextAlign

enum class SignInButtonViewState {
    Hidden, Ready
}

@Composable
fun DonationScreen(
    signInButtonText: MutableState<String>,
    onSignInClick: () -> Unit,
    onPayClick: (Int) -> Unit,
    onCardPayClick: (Int) -> Unit,
    onBackClick: () -> Unit,
    signInButtonViewState: MutableState<SignInButtonViewState>,
    isGooglePayReady: MutableState<Boolean>,
    authError: String?,
    onDismissErrorDialog: () -> Unit
) {
    var donationAmount by remember { mutableStateOf("") }
    val signInAttempted = remember { mutableStateOf(false) }
    var isDonationAmountError by rememberSaveable { mutableStateOf(false) }
    val isLoggedIn: Boolean = (signInButtonText.value == "Sign Out")

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(id = R.string.donate),
            signInButtonText = signInButtonText,
            onSignInClick = onSignInClick,
            onBackButtonClicked = onBackClick,
            authError = authError,
            onDismissErrorDialog = onDismissErrorDialog
        )
    }, content = { innerPadding ->
        ConstraintLayout(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(colorResource(id = R.color.teal_100))
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
                DonationHeader()
                Spacer(modifier = Modifier.height(16.dp))

                val showGooglePay = isGooglePayReady.value && isLoggedIn
                // Show card payment if Google Pay isn't ready, OR if sign-in has failed.
                val showCardPay = !isGooglePayReady.value || signInAttempted.value

                if (showGooglePay) {
                    // User is logged in and GPay is ready. Show GPay button.
                    when (signInButtonViewState.value) {
                        SignInButtonViewState.Hidden -> {}
                        SignInButtonViewState.Ready -> {
                            DonationAmountTextField(
                                donationAmount = donationAmount, onValueChange = {
                                    donationAmount = it
                                    isDonationAmountError =
                                        it.toIntOrNull() == null || it.toIntOrNull()!! < 5
                                }, isDonationAmountError = isDonationAmountError
                            )
                            Row() {
                                GooglePayButton(onClick = {
                                    val amount = donationAmount.toIntOrNull()
                                    if (amount != null && amount >= 5) {
                                        isDonationAmountError = false
                                        onPayClick(amount)
                                    } else {
                                        isDonationAmountError = true
                                    }
                                })

                                Spacer(modifier = Modifier.width(8.dp))

                                GoogleSignInButton(
                                    onClick = onSignInClick, signInButtonText
                                )
                            }
                        }
                    }
                } else if (showCardPay) {
                    // Fallback to card payment.
                    DonationAmountTextField(
                        donationAmount = donationAmount, onValueChange = {
                            donationAmount = it
                            isDonationAmountError =
                                it.toIntOrNull() == null || it.toIntOrNull()!! < 5
                        }, isDonationAmountError = isDonationAmountError
                    )
                    CardPayButton(onClick = {
                        val amount = donationAmount.toIntOrNull()
                        if (amount != null && amount >= 5) {
                            isDonationAmountError = false
                            onCardPayClick(amount)
                        } else {
                            isDonationAmountError = true
                        }
                    })
                } else {
                    // Initial state: GPay is ready, user is not logged in, and hasn't tried yet.
                    // Prompt them to sign in.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Google Pay is ready, sign in to use it",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        GoogleSignInButton(
                            onClick = onSignInClick, signInButtonText
                        )
                    }
                }
            }
        }
    })
}

@Composable
fun DonationHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(id = R.drawable.teal_gradient_orange),
            contentDescription = stringResource(id = R.string.donate_ads_away),
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp)
                .padding(top = 25.dp)
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(start = 5.dp),
        ) {
            Text(
                text = stringResource(id = R.string.donate_your_heart_out),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 30.dp)
                    .align(Alignment.CenterHorizontally),
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = stringResource(id = R.string.it_supports_the_developer_and_gets_rid_of_ads),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 5.dp, bottom = 15.dp),
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
fun DonationAmountTextField(
    donationAmount: String, onValueChange: (String) -> Unit, isDonationAmountError: Boolean
) {
    Column(
        modifier = Modifier
            .padding(bottom = 16.dp)
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        TextField(
            value = donationAmount,
            onValueChange = onValueChange,
            label = { Text("Donation Amount ≥5 SEK") },
            modifier = Modifier.width(250.dp),
            isError = isDonationAmountError,
            singleLine = true,
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
            ),
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
        )
        if (isDonationAmountError) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 2.dp),
                text = "Error: Amount must be at least 5 SEK",
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun GooglePayButton(onClick: () -> Unit) {
    PaymentButton(
        onClick = onClick,
        painterResource(id = R.drawable.ic_google_logo),
        stringResource(id = R.string.google_logo),
        stringResource(id = R.string.pay),
        true
    )
}

@Composable
fun CardPayButton(onClick: () -> Unit) {
    PaymentButton(
        onClick = onClick,
        painterResource(id = R.drawable.baseline_credit_card_24),
        stringResource(id = R.string.pay_with_credit_card),
        stringResource(id = R.string.pay_with_credit_card),
        false
    )
}

@Composable
fun PaymentButton(
    onClick: () -> Unit,
    icon: Painter,
    contentDescription: String,
    text: String,
    isGooglePay: Boolean
) {
    Button(
        onClick = onClick, modifier = Modifier // Common button styling
            .width(if (isGooglePay) 150.dp else 200.dp)
            .height(48.dp)
            .drawBehind {
                val shadowColor = Color.White
                val transparentColor = Color.Transparent
                val shadowRadius = 4.dp.toPx()
                val offset = 2.dp.toPx() // Offset downwards

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
                        radiusX = size.height / 2, // Half the height for rounded corners
                        radiusY = size.height / 2, // Half the height for rounded corners
                        paint = paint
                    )
                }
            }
            .clip(CircleShape) // Fully rounded corners
            .background(Color.Black), colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent, // Make container transparent
            contentColor = Color.White // Text color
        ), shape = CircleShape) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Image(
                painter = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp),
                colorFilter = if (!isGooglePay) ColorFilter.tint(colorResource(id = R.color.teal_900)) else null
            )
            Spacer(modifier = if (isGooglePay) Modifier.width(1.dp) else Modifier.width(5.dp))
            Text(
                text = text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DonationScreenGooglePayPreview() {
    val signInTextState = remember { mutableStateOf("Sign Out") }
    val signInButtonViewState = remember { mutableStateOf(SignInButtonViewState.Ready) }
    val isGooglePayReady = remember { mutableStateOf(true) }
    DonationScreen(
        signInTextState,
        {},
        {},
        {},
        {},
        signInButtonViewState,
        isGooglePayReady,
        null, {})
}

@Preview(showBackground = true)
@Composable
fun DonationScreenGooglePaySignInPreview() {
    val signInTextState = remember { mutableStateOf("Sign In") }
    val signInButtonViewState = remember { mutableStateOf(SignInButtonViewState.Ready) }
    val isGooglePayReady = remember { mutableStateOf(true) }
    DonationScreen(
        signInTextState,
        {},
        {},
        {},
        {},
        signInButtonViewState,
        isGooglePayReady,
        null, {})
}

@Preview(showBackground = true)
@Composable
fun DonationScreenGooglePaySignInFailedPreview() {
    val signInTextState = remember { mutableStateOf("Sign In") }
    val signInButtonViewState = remember { mutableStateOf(SignInButtonViewState.Ready) }
    val isGooglePayReady = remember { mutableStateOf(true) }
    DonationScreen(
        signInTextState,
        {},
        {},
        {},
        {},
        signInButtonViewState,
        isGooglePayReady,
        "Failure string", {})
}

@Preview(showBackground = true)
@Composable
fun DonationScreenCardPaymentPreview() {
    val signInTextState = remember { mutableStateOf("Sign In") }
    val signInButtonViewState = remember { mutableStateOf(SignInButtonViewState.Ready) }
    val isGooglePayReady = remember { mutableStateOf(false) }
    DonationScreen(
        signInTextState,
        {},
        {},
        {},
        {},
        signInButtonViewState,
        isGooglePayReady,
        null,
        {})
}