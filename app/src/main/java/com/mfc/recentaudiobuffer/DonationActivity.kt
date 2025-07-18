package com.mfc.recentaudiobuffer

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.stripe.android.PaymentConfiguration
import com.stripe.android.googlepaylauncher.GooglePayEnvironment
import com.stripe.android.googlepaylauncher.GooglePayLauncher
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

@AndroidEntryPoint
class DonationActivity : AppCompatActivity() {
    @Inject
    lateinit var authenticationManager: AuthenticationManager
    private val httpClient = OkHttpClient()
    private val serverUrl = DonationConstants.SERVER_URL
    private lateinit var googlePayLauncher: GooglePayLauncher
    private lateinit var stripePaymentSheet: PaymentSheet
    private var clientSecret: String? = null
    private val stripeApiKey = DonationConstants.STRIPE_API_KEY
    private var signInButtonViewState = mutableStateOf(SignInButtonViewState.Ready)
    private var isGooglePayReady = mutableStateOf(false)
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupStripe()
        setContent {
            // Collect the error state from the AuthenticationManager
            val authError by authenticationManager.authError.collectAsState()
            // Track if a sign-in attempt has been made and failed
            var signInAttempted = remember { mutableStateOf(false) }

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    DonationScreen(
                        signInButtonText = authenticationManager.signInButtonText,
                        onSignInClick = { authenticationManager.onSignInClick() },
                        onPayClick = { amount -> sendPaymentRequest(amount) },
                        onCardPayClick = { amount -> sendPaymentRequest(amount) },
                        onBackClick = { this.finish() },
                        signInButtonViewState = signInButtonViewState,
                        isGooglePayReady = isGooglePayReady,
                        signInAttempted = signInAttempted
                    )

                    // When authError is not null, display the AlertDialog
                    authError?.let {
                        // A sign-in attempt has now officially failed
                        signInAttempted.value = true
                        SignInErrorDialog(
                            errorMessage = it,
                            onDismiss = { authenticationManager.clearAuthError() })
                    }
                }
            }
        }
    }

    override fun onStart() {
        Timber.i("onStart() called")
        super.onStart()
        authenticationManager.registerLauncher(this)
    }

    private fun setupStripe() {
        PaymentConfiguration.init(this, stripeApiKey)
        val googlePayEnvironment = if (BuildConfig.DEBUG) {
            GooglePayEnvironment.Test
        } else {
            GooglePayEnvironment.Production
        }
        googlePayLauncher = GooglePayLauncher(
            activity = this, config = GooglePayLauncher.Config(
                environment = googlePayEnvironment,
                merchantCountryCode = "SE",
                merchantName = "Auto Record Moments"
            ), readyCallback = ::onGooglePayReady, resultCallback = ::onGooglePayResult
        )
        stripePaymentSheet = PaymentSheet(this, ::onPaymentSheetResult)
    }

    private fun presentPaymentSheet(clientSecret: String) {
        val configuration = PaymentSheet.Configuration(
            merchantDisplayName = "Auto Record Moments", allowsDelayedPaymentMethods = true
        )
        stripePaymentSheet.presentWithPaymentIntent(clientSecret, configuration)
    }

    private fun onPaymentSheetResult(paymentSheetResult: PaymentSheetResult) {
        when (paymentSheetResult) {
            is PaymentSheetResult.Completed -> {
                showPaymentResultToast("Payment complete!")
                settingsViewModel.updateAreAdsEnabled(false)
            }

            is PaymentSheetResult.Canceled -> showPaymentResultToast("Payment canceled.")
            is PaymentSheetResult.Failed -> showPaymentError(
                paymentSheetResult.error.toString(), paymentSheetResult.error
            )
        }
    }

    private fun sendPaymentRequest(amount: Int) {
        fetchClientSecret(amount)
    }

    private fun handlePaymentWithClientSecret() {
        runOnUiThread {
            if (clientSecret == null) {
                showPaymentError("Failed to get client secret")
                return@runOnUiThread
            }
            if (isGooglePayReady.value) {
                handleGooglePayPayment()
            } else {
                handleCardPayment()
            }
        }
    }

    private fun handleGooglePayPayment() {
        Timber.d("handleGooglePayPayment: Paying with GPay ...")
        googlePayLauncher.presentForPaymentIntent(clientSecret!!)
    }

    private fun handleCardPayment() {
        Timber.d("handleCardPayment: Paying with Card ...")
        presentPaymentSheet(clientSecret!!)
    }

    private fun fetchClientSecret(amount: Int) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        // Determine the environment based on the build type
        val environment = if (BuildConfig.DEBUG) "debug" else "production"
        // Add the 'environment' key to the JSON body
        val jsonBody = JSONObject().apply {
            put("amount", amount * 100) // Convert to cents
            put("environment", environment)
        }
        val requestBody = jsonBody.toString().toRequestBody(mediaType)
        val request =
            Request.Builder().url("$serverUrl/createPaymentIntent").post(requestBody).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                logNetworkError(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    logServerError(response.code)
                    return
                }
                val responseBody = response.body?.string() ?: run {
                    logEmptyResponse()
                    return
                }
                try {
                    val jsonObject = JSONObject(responseBody)
                    clientSecret = jsonObject.getString("clientSecret")
                    handlePaymentWithClientSecret()
                } catch (e: Exception) {
                    logJsonError(e)
                }
            }
        })
    }

    private fun onGooglePayReady(isReady: Boolean) {
        isGooglePayReady.value = isReady
        if (!isReady) {
            signInButtonViewState.value = SignInButtonViewState.Hidden
            showGooglePayNotReadyDialog()
        } else {
            signInButtonViewState.value = SignInButtonViewState.Ready
        }
    }

    private fun onGooglePayResult(result: GooglePayLauncher.Result) {
        when (result) {
            is GooglePayLauncher.Result.Completed -> {
                showPaymentResultToast("Payment Successful!")
                settingsViewModel.updateAreAdsEnabled(false)
            }

            is GooglePayLauncher.Result.Canceled -> showPaymentResultToast("Payment Canceled")
            is GooglePayLauncher.Result.Failed -> showPaymentError(
                result.error.toString(), result.error
            )
        }
    }

    private fun showGooglePayNotReadyDialog() {
        AlertDialog.Builder(this).setTitle("Google Pay Not Available")
            .setMessage("No worries! Google Pay is just one way to donate. If you want to use it, make sure you've installed the Google Wallet app and added a credit or debit card.")
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }.create().show()
    }

    private fun showPaymentResultToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showPaymentError(message: String, error: Throwable? = null) {
        error?.let { Timber.e("Payment Failed $it") }
        Toast.makeText(this, "Payment failed: $message", Toast.LENGTH_SHORT).show()
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

    private fun logEmptyResponse() {
        Timber.e("sendPaymentRequest: Empty response body")
        runOnUiThread {
            showPaymentError("Empty response from server")
        }
    }

    private fun logJsonError(e: Exception) {
        Timber.e("sendPaymentRequest: Failed to parse JSON $e")
        runOnUiThread {
            showPaymentError("Failed to parse server response")
        }
    }
}

object DonationConstants {
    const val SERVER_URL = "https://us-central1-recent-audio-buffer.cloudfunctions.net"
    const val STRIPE_API_KEY = BuildConfig.STRIPE_API_KEY
}