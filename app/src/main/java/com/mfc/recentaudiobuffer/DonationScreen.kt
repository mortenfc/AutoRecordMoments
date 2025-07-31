package com.mfc.recentaudiobuffer

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextField
import androidx.compose.runtime.MutableState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.wallet.button.PayButton
import com.google.android.gms.wallet.button.ButtonOptions
import com.google.android.gms.wallet.button.ButtonConstants
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import org.joda.money.CurrencyUnit
import org.joda.money.Money
import org.joda.money.format.MoneyFormatterBuilder
import java.util.Locale

@Composable
fun DonationScreen(
    viewModel: DonationViewModel,
    signInButtonText: MutableState<String>,
    onSignInClick: () -> Unit,
    onPayClick: (Double, CurrencyUnit) -> Unit,
    onBackClick: () -> Unit,
    authError: String?,
    onDismissErrorDialog: () -> Unit,
    allowedPaymentMethodsJson: String
) {
    val state = viewModel.uiState
    // This stateless composable is now easily previewable
    DonationScreenContent(
        state = state,
        signInButtonText = signInButtonText,
        onSignInClick = onSignInClick,
        onPayClick = onPayClick,
        onBackClick = onBackClick,
        authError = authError,
        onDismissErrorDialog = onDismissErrorDialog,
        allowedPaymentMethodsJson = allowedPaymentMethodsJson
    )
}

// Stateless content composable that handles all UI logic
@Composable
private fun DonationScreenContent(
    state: DonationScreenState,
    signInButtonText: MutableState<String>,
    onSignInClick: () -> Unit,
    onPayClick: (Double, CurrencyUnit) -> Unit,
    onBackClick: () -> Unit,
    authError: String?,
    onDismissErrorDialog: () -> Unit,
    allowedPaymentMethodsJson: String
) {
    val userLocale = Locale.getDefault()
    var currencyToUse = CurrencyUnit.of(userLocale)
    var ruleToUse: CurrencyRule? = state.rules[currencyToUse.code]

    if (ruleToUse == null) {
        currencyToUse = CurrencyUnit.EUR
        ruleToUse = state.rules["EUR"]
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "Donate",
                signInButtonText = signInButtonText,
                onSignInClick = onSignInClick,
                onBackButtonClicked = onBackClick,
                authError = authError,
                onDismissErrorDialog = onDismissErrorDialog
            )
        }) { innerPadding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colorResource(R.color.teal_100)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = colorResource(id = R.color.purple_accent))
                }
            }

            state.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colorResource(R.color.teal_100))
                        .padding(16.dp), contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Error: ${state.error}\nPlease try again later.",
                        textAlign = TextAlign.Center,
                        color = colorResource(R.color.teal_900)
                    )
                }
            }

            // If even EUR is not available, show a final error
            ruleToUse == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colorResource(R.color.teal_100))
                        .padding(16.dp), contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Somehow, a valid currency\ncould not be found",
                        textAlign = TextAlign.Center,
                        color = colorResource(R.color.teal_900)
                    )
                }
            }

            else -> {
                DonationContent(
                    modifier = Modifier.padding(innerPadding),
                    rule = ruleToUse,
                    userCurrency = currencyToUse,
                    onPayClick = onPayClick,
                    isGooglePayReady = state.isGooglePayReady && authError == null,
                    allowedPaymentMethodsJson = allowedPaymentMethodsJson
                )
            }
        }
    }
}


@Composable
private fun DonationContent(
    modifier: Modifier = Modifier,
    rule: CurrencyRule,
    // The currency to use, which may be the user's or the EUR fallback
    userCurrency: CurrencyUnit,
    onPayClick: (Double, CurrencyUnit) -> Unit,
    isGooglePayReady: Boolean,
    allowedPaymentMethodsJson: String
) {
    var amountString by remember { mutableStateOf("") }
    var isAmountError by remember { mutableStateOf(false) }

    val minAmountMajorUnit = rule.min.toDouble() / rule.multiplier

    fun performPayClick() {
        val amount = amountString.toDoubleOrNull()
        if (amount != null && amount >= minAmountMajorUnit) {
            isAmountError = false
            onPayClick(amount, userCurrency)
        } else {
            isAmountError = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.teal_100))
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        DonationHeader()

        Spacer(Modifier.height(32.dp))

        DonationAmountTextField(
            donationAmount = amountString,
            onValueChange = {
                amountString = it
                val amount = it.toDoubleOrNull()
                isAmountError = amount == null || amount < minAmountMajorUnit
            },
            isDonationAmountError = isAmountError,
            userCurrency = userCurrency,
            minAmountMajorUnit = minAmountMajorUnit
        )

        Spacer(Modifier.height(16.dp))

        if (isGooglePayReady) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp), factory = { context ->
                    PayButton(context).apply {
                        val buttonOptions = ButtonOptions.newBuilder()
                            .setButtonType(ButtonConstants.ButtonType.DONATE)
                            .setButtonTheme(ButtonConstants.ButtonTheme.DARK)
                            .setAllowedPaymentMethods(allowedPaymentMethodsJson).build()
                        initialize(buttonOptions)
                        setOnClickListener { performPayClick() }
                    }
                })
        } else {
            CardPayButton(onClick = ::performPayClick)
        }
    }
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
    donationAmount: String,
    onValueChange: (String) -> Unit,
    isDonationAmountError: Boolean,
    userCurrency: CurrencyUnit,
    minAmountMajorUnit: Double,
) {

    val userLocale = Locale.getDefault()
    val formatter = remember(userCurrency) {
        MoneyFormatterBuilder().appendCurrencySymbolLocalized().appendAmount()
            .toFormatter(userLocale)
    }

    val minAmountFormatted = formatter.print(Money.of(userCurrency, minAmountMajorUnit))

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
            label = { Text("Donation Amount ≥$minAmountFormatted") },
            modifier = Modifier.fillMaxWidth(fraction = 0.7f),
            isError = isDonationAmountError,
            singleLine = true,
            colors = TextFieldDefaults.colors(
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
                text = "Error: Amount must be at least $minAmountFormatted",
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun CardPayButton(onClick: () -> Unit) {
    CustomPaymentButton(
        onClick = onClick,
        painterResource(id = R.drawable.baseline_credit_card_24),
        stringResource(id = R.string.pay_with_credit_card),
        stringResource(id = R.string.pay_with_credit_card)
    )
}

@Composable
fun CustomPaymentButton(
    onClick: () -> Unit, icon: Painter, contentDescription: String, text: String
) {
    Button(
        onClick = onClick, modifier = Modifier
            // Set a minimum height, but allow it to grow if the text wraps
            .defaultMinSize(minHeight = 48.dp)
            .pillShapeShadow(shadowRadius = 5.dp, offsetY = 5.dp)
            .clip(CircleShape) // Fully rounded corners
            .background(Color.Black), colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent, // Make container transparent
            contentColor = Color.White,
        ), border = ButtonDefaults.outlinedButtonBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                colorResource(id = R.color.teal_100)
            ), width = 2.dp
        ), shape = CircleShape
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Image(
                painter = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp),
                colorFilter = ColorFilter.tint(colorResource(id = R.color.teal_150))
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = text, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White
            )
        }
    }
}

// Mock data for previews
private val mockRules = mapOf(
    "USD" to CurrencyRule(100, 50), "EUR" to CurrencyRule(100, 50)
)

@Preview(showBackground = true, name = "State: Google Pay Ready")
@Composable
private fun DonationScreenGooglePayPreview() {
    MaterialTheme {
        DonationScreenContent(
            state = DonationScreenState(
            isLoading = false, error = null, rules = mockRules, isGooglePayReady = true
        ),
            signInButtonText = remember { mutableStateOf("Sign Out") },
            onSignInClick = {},
            onPayClick = { amount, currency -> },
            onBackClick = {},
            authError = null,
            onDismissErrorDialog = {},
            allowedPaymentMethodsJson = ""
        )
    }
}

@Preview(showBackground = true, name = "State: Card Payment Only")
@Composable
private fun DonationScreenCardPaymentPreview() {
    MaterialTheme {
        DonationScreenContent(
            state = DonationScreenState(
            isLoading = false, error = null, rules = mockRules, isGooglePayReady = false
        ),
            signInButtonText = remember { mutableStateOf("Sign In") },
            onSignInClick = {},
            onPayClick = { amount, currency -> },
            onBackClick = {},
            authError = null,
            onDismissErrorDialog = {},
            allowedPaymentMethodsJson = ""
        )
    }
}

@Preview(showBackground = true, name = "State: Sign-In Failed")
@Composable
private fun DonationScreenSignInFailedPreview() {
    MaterialTheme {
        DonationScreenContent(
            state = DonationScreenState(
            isLoading = false, error = null, rules = mockRules, isGooglePayReady = true
        ),
            signInButtonText = remember { mutableStateOf("Sign In") },
            onSignInClick = {},
            onPayClick = { amount, currency -> },
            onBackClick = {},
            authError = "There was a problem signing you in with Google.",
            onDismissErrorDialog = {},
            allowedPaymentMethodsJson = ""
        )
    }
}

@Preview(showBackground = true, name = "State: Loading")
@Composable
private fun DonationScreenLoadingPreview() {
    MaterialTheme {
        DonationScreenContent(
            state = DonationScreenState(isLoading = true), // Loading state
            signInButtonText = remember { mutableStateOf("Sign In") },
            onSignInClick = {},
            onPayClick = { amount, currency -> },
            onBackClick = {},
            authError = null,
            onDismissErrorDialog = {},
            allowedPaymentMethodsJson = ""
        )
    }
}

@Preview(showBackground = true, name = "State: Network Error")
@Composable
private fun DonationScreenErrorPreview() {
    MaterialTheme {
        DonationScreenContent(
            state = DonationScreenState(
            isLoading = false, error = "Failed to connect"
        ), // Error state
            signInButtonText = remember { mutableStateOf("Sign In") },
            onSignInClick = {},
            onPayClick = { amount, currency -> },
            onBackClick = {},
            authError = null,
            onDismissErrorDialog = {},
            allowedPaymentMethodsJson = ""
        )
    }
}

@Preview(showBackground = true, name = "State: No Currency Available")
@Composable
private fun DonationScreenNoCurrencyPreview() {
    MaterialTheme {
        DonationScreenContent(
            state = DonationScreenState(
            isLoading = false, error = null, rules = mapOf(), isGooglePayReady = true
        ),
            signInButtonText = remember { mutableStateOf("Sign In") },
            onSignInClick = {},
            onPayClick = { amount, currency -> },
            onBackClick = {},
            authError = null,
            onDismissErrorDialog = {},
            allowedPaymentMethodsJson = ""
        )
    }
}