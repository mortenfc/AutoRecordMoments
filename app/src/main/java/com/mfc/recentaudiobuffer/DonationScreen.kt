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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
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

private data class DonationSizing(
    val horizontalPadding: Dp,
    val landscapePaneSpacing: Dp,
    val visualPortraitHeight: Dp,
    val descriptionHeaderStyle: TextStyle,
    val descriptionBodyStyle: TextStyle,
    val inputTextStyle: TextStyle,
    val inputLabelStyle: TextStyle,
    val inputSupportingTextStyle: TextStyle,
    val inputIconSize: Dp,
    val paymentButtonHeight: Dp,
    val cardPaymentButtonIconSize: Dp,
    val cardPaymentButtonTextStyle: TextStyle
)

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun DonationScreen(
    viewModel: DonationViewModel,
    onPayClick: (Int, CurrencyUnit) -> Unit,
    onBackClick: () -> Unit,
    authError: AuthError?,
    allowedPaymentMethodsJson: String,
    windowSizeClass: WindowSizeClass
) {
    val state = viewModel.uiState
    // This stateless composable is now easily previewable
    DonationScreenContent(
        widthSizeClass = windowSizeClass.widthSizeClass,
        heightSizeClass = windowSizeClass.heightSizeClass,
        state = state,
        onPayClick = onPayClick,
        onBackClick = onBackClick,
        authError = authError,
        allowedPaymentMethodsJson = allowedPaymentMethodsJson,
        onCurrencySelected = { currency -> viewModel.updateSelectedCurrency(currency) },
        useLiveViewModel = true
    )
}

// Stateless content composable that handles all UI logic
@Composable
private fun DonationScreenContent(
    widthSizeClass: WindowWidthSizeClass,
    heightSizeClass: WindowHeightSizeClass,
    state: DonationScreenState,
    onPayClick: (Int, CurrencyUnit) -> Unit,
    onBackClick: () -> Unit,
    authError: AuthError?,
    allowedPaymentMethodsJson: String,
    onCurrencySelected: (String) -> Unit,
    useLiveViewModel: Boolean
) {
    var currencyToUse = state.selectedCurrency
    var ruleToUse: CurrencyRule? = state.rules[currencyToUse.code]

    if (ruleToUse == null) {
        currencyToUse = CurrencyUnit.EUR
        ruleToUse = state.rules["EUR"]
    }

    Scaffold(
        topBar = {
            if (useLiveViewModel) {
                TopAppBar(
                    title = "Donate",
                    onBackButtonClicked = onBackClick,
                )
            } else {
                TopAppBarContent(
                    title = "Donate",
                    signInButtonText = "Sign In",
                    isSigningIn = false,
                    authError = null,
                    onSignInClick = {},
                    onDismissErrorDialog = {},
                    onBackButtonClicked = onBackClick,
                    onIconClick = {},
                    onSettingsClick = {})
            }
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
                val isTabletLandscape =
                    widthSizeClass == WindowWidthSizeClass.Expanded && heightSizeClass == WindowHeightSizeClass.Medium
                val isTabletPortrait =
                    widthSizeClass == WindowWidthSizeClass.Medium && heightSizeClass == WindowHeightSizeClass.Expanded
                val isTablet = isTabletLandscape || isTabletPortrait
                val isLandscape =
                    widthSizeClass == WindowWidthSizeClass.Expanded || heightSizeClass == WindowHeightSizeClass.Compact

                DonationContent(
                    modifier = Modifier.padding(innerPadding),
                    rule = ruleToUse,
                    currencyToUse = currencyToUse,
                    onPayClick = onPayClick,
                    isGooglePayReady = state.isGooglePayReady && authError == null,
                    allowedPaymentMethodsJson = allowedPaymentMethodsJson,
                    supportedCurrencies = state.rules.keys.toList(),
                    onCurrencySelected = onCurrencySelected,
                    isTablet = isTablet,
                    isLandscape = isLandscape
                )
            }
        }
    }
}


@Composable
private fun DonationContent(
    modifier: Modifier = Modifier,
    rule: CurrencyRule,
    currencyToUse: CurrencyUnit,
    onPayClick: (Int, CurrencyUnit) -> Unit,
    isGooglePayReady: Boolean,
    allowedPaymentMethodsJson: String,
    supportedCurrencies: List<String>,
    onCurrencySelected: (String) -> Unit,
    isTablet: Boolean,
    isLandscape: Boolean
) {
    var amountString by remember { mutableStateOf("") }
    var isAmountError by remember { mutableStateOf(false) }

    fun performPayClick() {
        val amount = amountString.toDoubleOrNull()?.times(rule.multiplier)?.toInt()
        if (amount != null && amount >= rule.min) {
            isAmountError = false
            onPayClick(amount, currencyToUse)
        } else {
            isAmountError = true
        }
    }

    val phoneSizing = DonationSizing(
        horizontalPadding = 24.dp,
        landscapePaneSpacing = 24.dp,
        visualPortraitHeight = 300.dp,
        descriptionHeaderStyle = MaterialTheme.typography.headlineSmall,
        descriptionBodyStyle = MaterialTheme.typography.bodyMedium,
        inputTextStyle = MaterialTheme.typography.bodyLarge,
        inputLabelStyle = MaterialTheme.typography.bodyMedium,
        inputSupportingTextStyle = MaterialTheme.typography.bodySmall,
        inputIconSize = 24.dp,
        paymentButtonHeight = 48.dp,
        cardPaymentButtonIconSize = 24.dp,
        cardPaymentButtonTextStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White)
    )

    val tabletSizing = DonationSizing(
        horizontalPadding = 64.dp,
        landscapePaneSpacing = 64.dp,
        visualPortraitHeight = 600.dp,
        descriptionHeaderStyle = TextStyle(fontSize = 40.sp),
        descriptionBodyStyle = TextStyle(fontSize = 26.sp),
        inputTextStyle = TextStyle(fontSize = 32.sp),
        inputLabelStyle = TextStyle(fontSize = 32.sp),
        inputSupportingTextStyle = TextStyle(fontSize = 22.sp),
        inputIconSize = 42.dp,
        paymentButtonHeight = 68.dp,
        cardPaymentButtonIconSize = 40.dp,
        cardPaymentButtonTextStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Medium, color = Color.White)
    )

    val sizing = if (isTablet) tabletSizing else phoneSizing

    val sharedModifier = modifier
        .fillMaxSize()
        .background(colorResource(id = R.color.teal_100))

    if (isLandscape) {
        Row(
            modifier = sharedModifier.padding(
                horizontal = sizing.horizontalPadding, vertical = 16.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(sizing.landscapePaneSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // --- Left Pane (Visual) ---
            Column(
                modifier = Modifier
                    .weight(1.3f) // Give more space to the visual
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                DonationVisuals(isLandscape = true, portraitHeight = sizing.visualPortraitHeight)
            }
            // --- Right Pane (Input and Payment) ---
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(Modifier.weight(0.5f))
                DonationDescription(
                    headerStyle = sizing.descriptionHeaderStyle,
                    bodyStyle = sizing.descriptionBodyStyle
                )
                Spacer(Modifier.weight(0.5f))
                DonationInput(
                    supportedCurrencies = supportedCurrencies,
                    selectedCurrency = currencyToUse,
                    onCurrencySelected = { currencyCode ->
                        amountString = ""
                        isAmountError = false
                        onCurrencySelected(currencyCode)
                    },
                    amount = amountString,
                    onAmountChange = { newAmountString ->
                        amountString = newAmountString
                        val amount = newAmountString.toDoubleOrNull()
                        val minMajorUnit = rule.min.toDouble() / rule.multiplier
                        isAmountError = amount == null || amount < minMajorUnit
                    },
                    isAmountError = isAmountError,
                    rule = rule,
                    textStyle = sizing.inputTextStyle,
                    labelTextStyle = sizing.inputLabelStyle,
                    supportingTextStyle = sizing.inputSupportingTextStyle,
                    iconSize = sizing.inputIconSize
                )
                Spacer(Modifier.weight(0.5f))
                PaymentButtons(
                    isGooglePayReady = isGooglePayReady,
                    allowedPaymentMethodsJson = allowedPaymentMethodsJson,
                    onPayClick = ::performPayClick,
                    buttonHeight = sizing.paymentButtonHeight,
                    cardButtonIconSize = sizing.cardPaymentButtonIconSize,
                    cardButtonTextStyle = sizing.cardPaymentButtonTextStyle
                )
                Spacer(Modifier.weight(0.5f))
            }
        }
    } else { // Portrait Layout
        Column(
            modifier = sharedModifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = sizing.horizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.weight(0.2f))
            DonationVisuals(isLandscape = false, portraitHeight = sizing.visualPortraitHeight)
            Spacer(Modifier.weight(0.2f))
            DonationDescription(
                headerStyle = sizing.descriptionHeaderStyle,
                bodyStyle = sizing.descriptionBodyStyle
            )
            Spacer(Modifier.weight(0.4f))
            DonationInput(
                supportedCurrencies = supportedCurrencies,
                selectedCurrency = currencyToUse,
                onCurrencySelected = { currencyCode ->
                    amountString = ""
                    isAmountError = false
                    onCurrencySelected(currencyCode)
                },
                amount = amountString,
                onAmountChange = { newAmountString ->
                    amountString = newAmountString
                    val amount = newAmountString.toDoubleOrNull()
                    val minMajorUnit = rule.min.toDouble() / rule.multiplier
                    isAmountError = amount == null || amount < minMajorUnit
                },
                isAmountError = isAmountError,
                rule = rule,
                textStyle = sizing.inputTextStyle,
                labelTextStyle = sizing.inputLabelStyle,
                supportingTextStyle = sizing.inputSupportingTextStyle,
                iconSize = sizing.inputIconSize
            )
            Spacer(Modifier.weight(0.3f))
            PaymentButtons(
                isGooglePayReady = isGooglePayReady,
                allowedPaymentMethodsJson = allowedPaymentMethodsJson,
                onPayClick = ::performPayClick,
                buttonHeight = sizing.paymentButtonHeight,
                cardButtonIconSize = sizing.cardPaymentButtonIconSize,
                cardButtonTextStyle = sizing.cardPaymentButtonTextStyle
            )
            Spacer(Modifier.weight(0.3f))
        }
    }
}

@Composable
private fun PaymentButtons(
    isGooglePayReady: Boolean,
    allowedPaymentMethodsJson: String,
    onPayClick: () -> Unit,
    buttonHeight: Dp,
    cardButtonIconSize: Dp,
    cardButtonTextStyle: TextStyle
) {
    if (isGooglePayReady) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(buttonHeight), factory = { context ->
                PayButton(context).apply {
                    val buttonOptions =
                        ButtonOptions.newBuilder().setButtonType(ButtonConstants.ButtonType.DONATE)
                            .setButtonTheme(ButtonConstants.ButtonTheme.DARK)
                            .setAllowedPaymentMethods(allowedPaymentMethodsJson).build()
                    initialize(buttonOptions)
                    setOnClickListener { onPayClick() }
                }
            })
    } else {
        CardPayButton(
            onClick = onPayClick,
            buttonHeight = buttonHeight,
            iconSize = cardButtonIconSize,
            textStyle = cardButtonTextStyle
        )
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
    rule: CurrencyRule,
    textStyle: TextStyle,
    labelTextStyle: TextStyle,
    supportingTextStyle: TextStyle,
    iconSize: Dp
) {
    val minAmountMoney = Money.ofMinor(selectedCurrency, rule.min.toLong())
    val userLocale = Locale.getDefault()
    val formatter = remember(selectedCurrency) {
        MoneyFormatterBuilder().appendCurrencySymbolLocalized().appendAmount()
            .toFormatter(userLocale)
    }

    val minAmountFormatted: String = formatter.print(minAmountMoney)

    var isDropdownExpanded by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colorResource(R.color.purple_accent),
        unfocusedBorderColor = colorResource(R.color.purple_accent),
        focusedLabelColor = colorResource(R.color.purple_accent),
        unfocusedLabelColor = colorResource(R.color.teal_900),
        focusedContainerColor = colorResource(R.color.teal_150).copy(alpha = 0.9f),
        unfocusedContainerColor = Color.White.copy(alpha = 0.35f),
        focusedTextColor = colorResource(R.color.teal_900),
        unfocusedTextColor = colorResource(R.color.teal_900),
        focusedTrailingIconColor = colorResource(R.color.purple_accent),
        unfocusedTrailingIconColor = colorResource(R.color.purple_accent),
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
                onValueChange = { text -> onAmountChange(text) },
                textStyle = textStyle,
                label = { Text("Donation Amount", style = labelTextStyle) },
                supportingText = {
                    if (!isAmountError) {
                        Text("Minimum: $minAmountFormatted", style = supportingTextStyle)
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
                            style = textStyle,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.Default.CurrencyExchange,
                            contentDescription = "Change Currency",
                            tint = colorResource(R.color.purple_accent),
                            modifier = Modifier.size(iconSize)
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.Companion.PrimaryEditable, true)
                    .fillMaxWidth()
            )

            ExposedDropdownMenu(
                expanded = isDropdownExpanded,
                onDismissRequest = { isDropdownExpanded = false },
                modifier = Modifier.background(colorResource(R.color.teal_100))
            ) {
                supportedCurrencies.forEach { currencyCode ->
                    DropdownMenuItem(text = {
                        Text(
                            currencyCode, color = colorResource(R.color.teal_900), style = textStyle
                        )
                    }, onClick = {
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
                style = supportingTextStyle,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}

@Composable
fun DonationVisuals(isLandscape: Boolean, portraitHeight: Dp) {
    Image(
        painter = painterResource(id = R.drawable.teal_gradient_orange),
        contentDescription = stringResource(id = R.string.donate_ads_away),
        contentScale = ContentScale.Fit, // Use Fit to prevent any clipping
        modifier = if (isLandscape) {
            Modifier.fillMaxHeight(0.9f) // Allow some padding around the image
        } else {
            Modifier
                .fillMaxWidth()
                .height(portraitHeight)
        }
    )
}

@Composable
fun DonationDescription(headerStyle: TextStyle, bodyStyle: TextStyle) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(id = R.string.donate_your_heart_out),
            style = headerStyle,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = stringResource(id = R.string.it_supports_the_developer_and_gets_rid_of_ads),
            style = bodyStyle,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
fun CardPayButton(onClick: () -> Unit, buttonHeight: Dp, iconSize: Dp, textStyle: TextStyle) {
    CustomPaymentButton(
        onClick = onClick,
        icon = painterResource(id = R.drawable.baseline_credit_card_24),
        contentDescription = stringResource(id = R.string.pay_with_credit_card),
        text = stringResource(id = R.string.pay_with_credit_card),
        buttonHeight = buttonHeight,
        iconSize = iconSize,
        textStyle = textStyle
    )
}

@Composable
fun CustomPaymentButton(
    onClick: () -> Unit,
    icon: Painter,
    contentDescription: String,
    text: String,
    buttonHeight: Dp,
    iconSize: Dp,
    textStyle: TextStyle
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = buttonHeight)
            .pillShapeShadow(shadowRadius = 5.dp, offsetY = 5.dp)
            .clip(CircleShape)
            .background(Color.Black),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
        ),
        border = ButtonDefaults.outlinedButtonBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                colorResource(id = R.color.teal_100)
            ), width = 2.dp
        ),
        shape = CircleShape
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Image(
                painter = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
                colorFilter = ColorFilter.tint(colorResource(id = R.color.teal_150))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text, style = textStyle
            )
        }
    }
}

// Mock data for previews
private val mockRules = mapOf(
    "USD" to CurrencyRule(100, 50), "EUR" to CurrencyRule(100, 50), "GBP" to CurrencyRule(100, 50)
)
private val googlePayReadyState = DonationScreenState(
    isLoading = false, error = null, rules = mockRules, isGooglePayReady = true
)

private val googlePayNotReadyState = DonationScreenState(
    isLoading = false, error = null, rules = mockRules, isGooglePayReady = false
)

@Preview(
    showBackground = true, name = "Phone Portrait", device = "spec:width=360dp,height=640dp,dpi=420"
)
@Composable
private fun DonationScreenPhonePortraitPreview() {
    MaterialTheme {
        DonationScreenContent(
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Expanded,
            state = googlePayNotReadyState,
            onPayClick = { _, _ -> },
            onBackClick = {},
            authError = null,
            allowedPaymentMethodsJson = "",
            onCurrencySelected = {},
            useLiveViewModel = false)
    }
}

@Preview(
    showBackground = true,
    name = "Phone Landscape",
    device = "spec:width=640dp,height=360dp,dpi=420"
)
@Composable
private fun DonationScreenPhoneLandscapePreview() {
    MaterialTheme {
        DonationScreenContent(
            widthSizeClass = WindowWidthSizeClass.Expanded,
            heightSizeClass = WindowHeightSizeClass.Compact,
            state = googlePayReadyState,
            onPayClick = { _, _ -> },
            onBackClick = {},
            authError = null,
            allowedPaymentMethodsJson = "",
            onCurrencySelected = {},
            useLiveViewModel = false)
    }
}

@Preview(
    showBackground = true,
    name = "Tablet Portrait",
    device = "spec:width=720dp,height=1280dp,dpi=240"
)
@Composable
private fun DonationScreenTabletPortraitPreview() {
    MaterialTheme {
        DonationScreenContent(
            widthSizeClass = WindowWidthSizeClass.Medium,
            heightSizeClass = WindowHeightSizeClass.Expanded,
            state = googlePayReadyState,
            onPayClick = { _, _ -> },
            onBackClick = {},
            authError = null,
            allowedPaymentMethodsJson = "",
            onCurrencySelected = {},
            useLiveViewModel = false)
    }
}

@Preview(
    showBackground = true,
    name = "Tablet Landscape",
    device = "spec:width=1280dp,height=720dp,dpi=240"
)
@Composable
private fun DonationScreenTabletLandscapePreview() {
    MaterialTheme {
        DonationScreenContent(
            widthSizeClass = WindowWidthSizeClass.Expanded,
            heightSizeClass = WindowHeightSizeClass.Medium,
            state = googlePayNotReadyState,
            onPayClick = { _, _ -> },
            onBackClick = {},
            authError = null,
            allowedPaymentMethodsJson = "",
            onCurrencySelected = {},
            useLiveViewModel = false)
    }
}