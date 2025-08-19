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

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import com.google.android.play.core.integrity.IntegrityManager
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.stripe.android.PaymentConfiguration
import com.stripe.android.googlepaylauncher.GooglePayEnvironment
import com.stripe.android.googlepaylauncher.GooglePayLauncher
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheet.Builder
import com.stripe.android.paymentsheet.PaymentSheetResult
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.joda.money.CurrencyUnit
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class DonationActivity : AppCompatActivity() {
    @Inject
    lateinit var authenticationManager: AuthenticationManager
    private val httpClient = OkHttpClient()
    private lateinit var integrityManager: IntegrityManager
    private val donationViewModel: DonationViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private lateinit var googlePayLauncher: GooglePayLauncher
    private lateinit var stripePaymentSheet: PaymentSheet

    // User's locale information
    private val userLocale: Locale by lazy { Locale.getDefault() }

    // JSON configuration for the Google Pay button
    private val allowedPaymentMethodsJson: String by lazy {
        // Using lazy to ensure stripeApiKey is initialized
        """
        [
          {
            "type": "CARD",
            "parameters": {
              "allowedAuthMethods": ["PAN_ONLY", "CRYPTOGRAM_3DS"],
              "allowedCardNetworks": ["AMEX", "DISCOVER", "JCB", "MASTERCARD", "VISA"]
            },
            "tokenizationSpecification": {
              "type": "PAYMENT_GATEWAY",
              "parameters": {
                "gateway": "stripe",
                "stripe:version": "2025-07-30.basil",
                "stripe:publishableKey": "${DonationConstants.STRIPE_API_KEY}"
              }
            }
          }
        ]
        """.trimIndent()
    }


    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        integrityManager = IntegrityManagerFactory.create(applicationContext)
        setupPaymentMethods()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    DonationScreen(
                        // State and authentication
                        viewModel = donationViewModel,
                        authError = authenticationManager.authError.collectAsState().value,
                        // Payment actions
                        onPayClick = ::onPayClick,
                        onBackClick = { this.finish() },
                        allowedPaymentMethodsJson = allowedPaymentMethodsJson,
                        windowSizeClass = calculateWindowSizeClass(this)
                    )
                }
            }
        }
    }

    private fun presentPaymentFlow() {
        val showGooglePay =
            donationViewModel.uiState.isGooglePayReady && authenticationManager.authError.value == null

        if (showGooglePay) {
            donationViewModel.clientSecret?.let {
                googlePayLauncher.presentForPaymentIntent(it)
            }
        } else {
            presentCardPaymentSheet()
        }
    }

    private fun onPayClick(amount: Int, currency: CurrencyUnit) {
        // Check if we already have a clientSecret from a previous rotation
        if (donationViewModel.clientSecret != null) {
            presentPaymentFlow()
        } else {
            fetchClientSecret(amount, currency)
        }
    }

    private fun fetchClientSecret(amount: Int, currency: CurrencyUnit) {
        // A nonce is a one-time-use value that your server should also verify to prevent replay attacks.
        // For simplicity, we'll just generate it here. For higher security, your server should generate it
        // and send it to the client first.
        val nonce = (System.currentTimeMillis().toString() + amount.toString() + currency.code)

        // Request an integrity token first
        integrityManager.requestIntegrityToken(
            IntegrityTokenRequest.builder().setNonce(nonce).build()
        ).addOnSuccessListener { tokenResponse ->
                val integrityToken = tokenResponse.token()
                sendPaymentRequestToServer(amount, currency, integrityToken)
            }.addOnFailureListener { e ->
                Timber.e(e, "Play Integrity token request failed")
                runOnUiThread {
                    showPaymentError("Device integrity check failed")
                }
            }
    }

    private fun setupPaymentMethods() {
        PaymentConfiguration.init(this, DonationConstants.STRIPE_API_KEY)
        stripePaymentSheet = Builder(::onPaymentSheetResult).build(this)

        val googlePayEnvironment =
            if (BuildConfig.DEBUG) GooglePayEnvironment.Test else GooglePayEnvironment.Production
        googlePayLauncher = GooglePayLauncher(
            activity = this,
            config = GooglePayLauncher.Config(
                environment = googlePayEnvironment,
                merchantCountryCode = userLocale.country,
                merchantName = "Auto Record Moments"
            ),
            readyCallback = { isReady -> donationViewModel.setGooglePayReady(isReady) },
            resultCallback = ::onGooglePayResult
        )
    }

    private fun sendPaymentRequestToServer(
        amount: Int, currency: CurrencyUnit, integrityToken: String
    ) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val environment = if (BuildConfig.DEBUG) "debug" else "production"

        val jsonBody = JSONObject().apply {
            put("amount", amount)
            put("environment", environment)
            put("currency", currency.code)
            put("integrityToken", integrityToken)
            put("packageName", BuildConfig.APPLICATION_ID)
        }
        val requestBody = jsonBody.toString().toRequestBody(mediaType)
        val request = Request.Builder().url("${DonationConstants.SERVER_URL}/createPaymentIntent")
            .post(requestBody).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                logNetworkError(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    logServerError(response.code)
                    return
                }
                val responseBody = response.body.string()
                donationViewModel.clientSecret = JSONObject(responseBody).getString("clientSecret")

                runOnUiThread {
                    presentPaymentFlow()
                }
            }
        })
    }

    private fun presentCardPaymentSheet() {
        donationViewModel.clientSecret?.let {
            val configuration = PaymentSheet.Configuration(
                merchantDisplayName = "Auto Record Moments",
                allowsDelayedPaymentMethods = true,
                defaultBillingDetails = PaymentSheet.BillingDetails(
                    address = PaymentSheet.Address(country = userLocale.country)
                )
            )
            stripePaymentSheet.presentWithPaymentIntent(it, configuration)
        }
    }

    private fun handleSuccessfulPayment() {
        showPaymentResultToast("Payment complete!")
        settingsViewModel.updateAreAdsEnabled(false)
    }

    private fun onPaymentSheetResult(paymentSheetResult: PaymentSheetResult) {
        when (paymentSheetResult) {
            is PaymentSheetResult.Completed -> handleSuccessfulPayment()
            is PaymentSheetResult.Canceled -> showPaymentResultToast("Payment canceled.")
            is PaymentSheetResult.Failed -> showPaymentError(
                "Card payment failed", paymentSheetResult.error
            )
        }

        donationViewModel.clientSecret = null
    }

    private fun onGooglePayResult(result: GooglePayLauncher.Result) {
        when (result) {
            is GooglePayLauncher.Result.Completed -> handleSuccessfulPayment()
            is GooglePayLauncher.Result.Canceled -> showPaymentResultToast("Payment Canceled")
            is GooglePayLauncher.Result.Failed -> showPaymentError(
                "Google Pay failed", result.error
            )
        }

        donationViewModel.clientSecret = null
    }

    private fun showPaymentResultToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showPaymentError(message: String, error: Throwable? = null) {
        error?.let { Timber.e("$message : $it") }
        Toast.makeText(this, "Payment failed: ${error?.localizedMessage}", Toast.LENGTH_SHORT)
            .show()
    }

    private fun logNetworkError(e: IOException) {
        Timber.e("sendPaymentRequest: Failed to fetch clientSecret $e")
        runOnUiThread {
            showPaymentError("Failed to connect to server")
        }
    }

    private fun logServerError(code: Int) {
        Timber.e("sendPaymentRequest: Server returned an error: $code")
        runOnUiThread {
            showPaymentError("Server error: $code")
        }
    }
}

object DonationConstants {
    const val SERVER_URL = "https://us-central1-recent-audio-buffer.cloudfunctions.net"
    const val STRIPE_API_KEY = BuildConfig.STRIPE_API_KEY
}