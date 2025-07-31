package com.mfc.recentaudiobuffer

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
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
    private val donationViewModel: DonationViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private lateinit var googlePayLauncher: GooglePayLauncher
    private lateinit var stripePaymentSheet: PaymentSheet
    private var clientSecret: String? = null

    // User's locale information
    private val userLocale: Locale by lazy { Locale.getDefault() }
    private val userCurrency: CurrencyUnit by lazy { CurrencyUnit.of(userLocale) }

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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        onDismissErrorDialog = { authenticationManager.clearAuthError() },
                        signInButtonText = authenticationManager.signInButtonText,
                        onSignInClick = { authenticationManager.onSignInClick(this) },
                        // Payment actions
                        onPayClick = ::fetchClientSecret,
                        onBackClick = { this.finish() },
                        allowedPaymentMethodsJson = allowedPaymentMethodsJson,
                    )
                }
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

    private fun fetchClientSecret(amount: Double, currency: CurrencyUnit) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val environment = if (BuildConfig.DEBUG) "debug" else "production"

        val jsonBody = JSONObject().apply {
            put("amount", amount) // Send the major unit amount as a double
            put("environment", environment)
            put("currency", currency.code)
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
                val responseBody = response.body?.string() ?: return
                clientSecret = JSONObject(responseBody).getString("clientSecret")

                runOnUiThread {
                    val showGooglePay =
                        donationViewModel.uiState.isGooglePayReady && authenticationManager.authError.value == null

                    if (showGooglePay) {
                        googlePayLauncher.presentForPaymentIntent(clientSecret!!)
                    } else {
                        presentCardPaymentSheet()
                    }
                }
            }
        })
    }

    private fun presentCardPaymentSheet() {
        clientSecret?.let {
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
    }

    private fun onGooglePayResult(result: GooglePayLauncher.Result) {
        when (result) {
            is GooglePayLauncher.Result.Completed -> handleSuccessfulPayment()
            is GooglePayLauncher.Result.Canceled -> showPaymentResultToast("Payment Canceled")
            is GooglePayLauncher.Result.Failed -> showPaymentError(
                "Google Pay failed", result.error
            )
        }
    }

    private fun showPaymentResultToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showPaymentError(message: String, error: Throwable? = null) {
        error?.let { Timber.e("Payment Failed $it") }
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