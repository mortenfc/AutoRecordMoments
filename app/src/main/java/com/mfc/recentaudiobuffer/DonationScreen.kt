package com.mfc.recentaudiobuffer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.wallet.button.ButtonConstants
import com.google.android.gms.wallet.button.ButtonOptions
import com.google.android.gms.wallet.button.PayButton
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
        allowedPaymentMethodsJson = allowedPaymentMethodsJson,
        onCurrencySelected = { currency -> viewModel.updateSelectedCurrency(currency) })
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
    allowedPaymentMethodsJson: String,
    onCurrencySelected: (String) -> Unit,
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
                    currencyToUse = currencyToUse,
                    onPayClick = onPayClick,
                    isGooglePayReady = state.isGooglePayReady && authError == null,
                    allowedPaymentMethodsJson = allowedPaymentMethodsJson,
                    supportedCurrencies = state.rules.keys.toList(),
                    onCurrencySelected = onCurrencySelected
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
    currencyToUse: CurrencyUnit,
    onPayClick: (Double, CurrencyUnit) -> Unit,
    isGooglePayReady: Boolean,
    allowedPaymentMethodsJson: String,
    supportedCurrencies: List<String>,
    onCurrencySelected: (String) -> Unit
) {
    var amountString by remember { mutableStateOf("") }
    var isAmountError by remember { mutableStateOf(false) }

    val minAmountMajorUnit = rule.min.toDouble() / rule.multiplier

    fun performPayClick() {
        val amount = amountString.toDoubleOrNull()
        if (amount != null && amount >= minAmountMajorUnit) {
            isAmountError = false
            onPayClick(amount, currencyToUse)
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

        Spacer(Modifier.height(24.dp))

        DonationInput(
            supportedCurrencies = supportedCurrencies,
            selectedCurrency = currencyToUse,
            onCurrencySelected = { currencyCode ->
                amountString = ""
                isAmountError = false
                onCurrencySelected(currencyCode)
            },
            amount = amountString,
            onAmountChange = {
                amountString = it
                val amount = it.toDoubleOrNull()
                isAmountError = amount == null || amount < minAmountMajorUnit
            },
            isAmountError = isAmountError,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DonationInput(
    amount: String,
    onAmountChange: (String) -> Unit,
    isAmountError: Boolean,
    selectedCurrency: CurrencyUnit,
    supportedCurrencies: List<String>,
    onCurrencySelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    minAmountMajorUnit: Double
) {
    val userLocale = Locale.getDefault()
    val formatter = remember(selectedCurrency) {
        MoneyFormatterBuilder().appendCurrencySymbolLocalized().appendAmount()
            .toFormatter(userLocale)
    }

    val minAmountFormatted = formatter.print(Money.of(selectedCurrency, minAmountMajorUnit))

    var isDropdownExpanded by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        // Border and Label
        focusedBorderColor = colorResource(R.color.purple_accent),
        unfocusedBorderColor = colorResource(R.color.purple_accent),
        focusedLabelColor = colorResource(R.color.purple_accent),
        unfocusedLabelColor = colorResource(R.color.teal_900),

        // Container background changes on focus
        focusedContainerColor = colorResource(R.color.teal_150).copy(alpha = 0.9f),
        unfocusedContainerColor = Color.White.copy(alpha = 0.35f),

        // Text and Icon
        focusedTextColor = colorResource(R.color.teal_900),
        unfocusedTextColor = colorResource(R.color.teal_900),
        focusedTrailingIconColor = colorResource(R.color.purple_accent),
        unfocusedTrailingIconColor = colorResource(R.color.purple_accent),

        // Error state colors
        errorBorderColor = MaterialTheme.colorScheme.error,
        errorSupportingTextColor = MaterialTheme.colorScheme.error,
        errorContainerColor = colorResource(id = R.color.teal_150)
    )

    Column(modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = isDropdownExpanded,
            onExpandedChange = { isDropdownExpanded = !isDropdownExpanded }) {
            OutlinedTextField(
                value = amount,
                onValueChange = onAmountChange,
                label = { Text("Donation Amount") }, // Label is simpler now
                supportingText = {
                    // Supporting text is a better place for the minimum amount hint
                    if (!isAmountError) {
                        Text("Minimum: $minAmountFormatted")
                    }
                },
                isError = isAmountError,
                singleLine = true,
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = {
                    Row(
                        modifier = Modifier.clickable { isDropdownExpanded = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            selectedCurrency.code,
                            color = colorResource(R.color.purple_accent),
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.Default.CurrencyExchange,
                            contentDescription = "Change Currency",
                            tint = colorResource(R.color.purple_accent)
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.Companion.PrimaryEditable, true)
                    .fillMaxWidth(fraction = 0.95f)
            )

            // The actual dropdown menu
            ExposedDropdownMenu(
                expanded = isDropdownExpanded,
                onDismissRequest = { isDropdownExpanded = false },
                modifier = Modifier.background(colorResource(R.color.teal_100))
            ) {
                supportedCurrencies.forEach { currencyCode ->
                    DropdownMenuItem(
                        text = { Text(currencyCode, color = colorResource(R.color.teal_900)) },
                        onClick = {
                            onCurrencySelected(currencyCode)
                            isDropdownExpanded = false
                        })
                }
            }
        }
        if (isAmountError) {
            Text(
                text = "Amount must be at least $minAmountFormatted",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
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
            onPayClick = { _, _ -> },
            onBackClick = {},
            authError = null,
            onDismissErrorDialog = {},
            allowedPaymentMethodsJson = "",
            onCurrencySelected = {} // Add this
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
            onPayClick = { _, _ -> },
            onBackClick = {},
            authError = null,
            onDismissErrorDialog = {},
            allowedPaymentMethodsJson = "",
            onCurrencySelected = {} // Add this
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
            onPayClick = { _, _ -> },
            onBackClick = {},
            authError = "There was a problem signing you in with Google.",
            onDismissErrorDialog = {},
            allowedPaymentMethodsJson = "",
            onCurrencySelected = {} // Add this
        )
    }
}

@Preview(showBackground = true, name = "State: Loading")
@Composable
private fun DonationScreenLoadingPreview() {
    MaterialTheme {
        DonationScreenContent(
            state = DonationScreenState(isLoading = true),
            signInButtonText = remember { mutableStateOf("Sign In") },
            onSignInClick = {},
            onPayClick = { _, _ -> },
            onBackClick = {},
            authError = null,
            onDismissErrorDialog = {},
            allowedPaymentMethodsJson = "",
            onCurrencySelected = {} // Add this
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
            ),
            signInButtonText = remember { mutableStateOf("Sign In") },
            onSignInClick = {},
            onPayClick = { _, _ -> },
            onBackClick = {},
            authError = null,
            onDismissErrorDialog = {},
            allowedPaymentMethodsJson = "",
            onCurrencySelected = {} // Add this
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
            onPayClick = { _, _ -> },
            onBackClick = {},
            authError = null,
            onDismissErrorDialog = {},
            allowedPaymentMethodsJson = "",
            onCurrencySelected = {} // Add this
        )
    }
}