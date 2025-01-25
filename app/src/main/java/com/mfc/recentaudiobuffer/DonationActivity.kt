package com.mfc.recentaudiobuffer

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.stripe.android.PaymentConfiguration
import com.stripe.android.googlepaylauncher.GooglePayEnvironment
import com.stripe.android.googlepaylauncher.GooglePayLauncher
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class DonationActivity : AppCompatActivity() {

    private val httpClient = OkHttpClient()
    private val logTag = DonationConstants.LOG_TAG
    private val serverUrl = DonationConstants.SERVER_URL
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var auth: FirebaseAuth
    private lateinit var googlePayLauncher: GooglePayLauncher
    private lateinit var stripePaymentSheet: PaymentSheet
    private var clientSecret: String? = null
    private val stripeApiKey = DonationConstants.STRIPE_API_KEY
    private var signInButtonText = mutableStateOf("Sign In")
    private var signInButtonViewState = mutableStateOf(SignInButtonViewState.Ready)
    private var isGooglePayReady = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = FirebaseAuth.getInstance()
        setupGoogleSignIn()
        setupStripe()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    DonationScreen(
                        onSignInClick = { onClickSignIn() },
                        onPayClick = { amount -> sendPaymentRequest(amount) },
                        onCardPayClick = { amount -> sendPaymentRequest(amount) },
                        onBackClick = { this.finish() },
                        signInButtonText = signInButtonText,
                        signInButtonViewState = signInButtonViewState,
                        isGooglePayReady = isGooglePayReady
                    )
                }
            }
        }
        updateInitialSignInStatus()
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id)).requestEmail().build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun setupStripe() {
        PaymentConfiguration.init(this, stripeApiKey)
        googlePayLauncher = GooglePayLauncher(
            activity = this, config = GooglePayLauncher.Config(
                environment = GooglePayEnvironment.Test,
                merchantCountryCode = "SE",
                merchantName = "Recent Audio Buffer"
            ), readyCallback = ::onGooglePayReady, resultCallback = ::onGooglePayResult
        )
        stripePaymentSheet = PaymentSheet(this, ::onPaymentSheetResult)
    }

    private fun updateInitialSignInStatus() {
        onSuccessSignInOut(auth.currentUser)
    }

    private fun onClickSignIn() {
        if (auth.currentUser != null) {
            onClickSignOut()
        } else {
            val signInIntent = googleSignInClient.signInIntent
            signInScreenLauncher.launch(signInIntent)
        }
    }

    private val signInScreenLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == Activity.RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    val account = task.getResult(ApiException::class.java)
                    launchFirebaseAuthWithGoogle(account)
                } catch (e: ApiException) {
                    logSignInError(e)
                }
            } else {
                logSignInError(result)
            }
        }

    private fun launchFirebaseAuthWithGoogle(account: GoogleSignInAccount) {
        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
        auth.signInWithCredential(credential).addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                onSuccessSignInOut(auth.currentUser)
            } else {
                logSignInError(task.exception)
            }
        }
    }

    private fun onSuccessSignInOut(user: FirebaseUser?) {
        signInButtonText.value = if (user != null) "Sign Out" else "Sign In"
    }

    private fun onClickSignOut() {
        auth.signOut()
        googleSignInClient.signOut().addOnCompleteListener(this) {
            onSuccessSignInOut(null)
        }
    }

    private fun presentPaymentSheet(clientSecret: String) {
        val configuration = PaymentSheet.Configuration(
            merchantDisplayName = "Recent Audio Buffer", allowsDelayedPaymentMethods = true
        )
        stripePaymentSheet.presentWithPaymentIntent(clientSecret, configuration)
    }

    private fun onPaymentSheetResult(paymentSheetResult: PaymentSheetResult) {
        when (paymentSheetResult) {
            is PaymentSheetResult.Completed -> showPaymentResultToast("Payment complete!")
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
        Log.d(logTag, "handleGooglePayPayment: Paying with GPay ...")
        googlePayLauncher.presentForPaymentIntent(clientSecret!!)
    }

    private fun handleCardPayment() {
        Log.d(logTag, "handleCardPayment: Paying with Card ...")
        presentPaymentSheet(clientSecret!!)
    }

    private fun fetchClientSecret(amount: Int) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val jsonBody = JSONObject().apply { put("amount", amount * 100) } // Convert to cents
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
            is GooglePayLauncher.Result.Completed -> showPaymentResultToast("Payment Successful!")
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
        error?.let { Log.e(logTag, "Payment Failed", it) }
        Toast.makeText(this, "Payment failed: $message", Toast.LENGTH_SHORT).show()
    }

    private fun logSignInError(e: ApiException) {
        Log.w(logTag, "Google sign in failed", e)
        Log.w(logTag, "Statuscode: ${e.statusCode}")
        Log.w(logTag, "Message: ${e.message}")
        showPaymentError("Google sign in failed")
    }

    private fun logSignInError(result: ActivityResult) {
        Log.e(
            logTag,
            "Google sign in failed with error code, data: ${result.resultCode}, ${result.data?.extras}"
        )
        val bundle = result.data?.extras
        if (bundle != null) {
            for (key in bundle.keySet()) {
                val value = bundle.get(key)
                Log.e(logTag, "Bundle data - Key: $key, Value: $value")
            }
        }
        showPaymentError("Google sign in failed")
    }

    private fun logSignInError(e: Exception?) {
        Log.w(logTag, "signInWithCredential:failure", e)
        showPaymentError("Authentication Failed.")
    }

    private fun logNetworkError(e: IOException) {
        Log.e(logTag, "sendPaymentRequest: Failed to fetch clientSecret", e)
        runOnUiThread {
            showPaymentError("Failed to connect to server")
        }
    }

    private fun logServerError(code: Int) {
        Log.e(logTag, "sendPaymentRequest: Server returned an error: $code")
        runOnUiThread {
            showPaymentError("Server error: $code")
        }
    }

    private fun logEmptyResponse() {
        Log.e(logTag, "sendPaymentRequest: Empty response body")
        runOnUiThread {
            showPaymentError("Empty response from server")
        }
    }

    private fun logJsonError(e: Exception) {
        Log.e(logTag, "sendPaymentRequest: Failed to parse JSON", e)
        runOnUiThread {
            showPaymentError("Failed to parse server response")
        }
    }
}

object DonationConstants {
    const val LOG_TAG = "DonationActivity"
    const val SERVER_URL = "https://us-central1-recent-audio-buffer.cloudfunctions.net"
    const val STRIPE_API_KEY =
        "pk_test_51Qb05qH7rOdAu0fXFO9QEU8ygiSSOdlkqDofr9nSI54UHdWbxfIj0Iz0BBKIGlfzxwEUJTUOVILcNEVYs2UNS0Af00yMhr6dX1"
}