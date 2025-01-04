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
    private val logTag = "DonationActivity"
    private val serverUrl = "https://us-central1-recent-audio-buffer.cloudfunctions.net"
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var auth: FirebaseAuth

    private lateinit var googlePayLauncher: GooglePayLauncher
    private var clientSecret: String? = null
    private val stripeApiKey: String =
        "pk_test_51Qb05qH7rOdAu0fXFO9QEU8ygiSSOdlkqDofr9nSI54UHdWbxfIj0Iz0BBKIGlfzxwEUJTUOVILcNEVYs2UNS0Af00yMhr6dX1"
    private var signInButtonText = mutableStateOf("Sign In")
    private var googlePayButtonViewState = mutableStateOf(GooglePayButtonViewState.Hidden)
    private var isGooglePayReady = mutableStateOf(false)

    private fun onGooglePayReady(isReady: Boolean) {
        Log.d(logTag, "onGooglePayReady: isReady: $isReady")
        isGooglePayReady.value = isReady
        if (!isReady) {
            showGooglePayNotReadyDialog()
            googlePayButtonViewState.value = GooglePayButtonViewState.Hidden
        }
    }

    private fun showGooglePayNotReadyDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Google Pay Not Available")
        builder.setMessage("Google Pay is not available on this device. Please make sure you have the Google Pay app installed and have added a payment method.")
        builder.setPositiveButton("OK") { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
    }


    private fun onGooglePayResult(result: GooglePayLauncher.Result) {
        when (result) {
            GooglePayLauncher.Result.Completed -> {
                // Payment succeeded, show a receipt view
                Toast.makeText(this, "Payment Successful!", Toast.LENGTH_SHORT).show()
                // Here you would update your database to remove ads for this user
                // You'll need to associate the payment with the user's ID (auth.currentUser?.uid)
            }

            GooglePayLauncher.Result.Canceled -> {
                // User canceled the operation
                Toast.makeText(this, "Payment Canceled", Toast.LENGTH_SHORT).show()
            }

            is GooglePayLauncher.Result.Failed -> {
                // Operation failed; inspect `result.error` for the exception
                Toast.makeText(
                    this,
                    "Payment Failed: ${result.error.message}",
                    Toast.LENGTH_SHORT
                )
                    .show()
                Log.e(logTag, "Payment Failed", result.error)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(logTag, "onCreate: Started")
        super.onCreate(savedInstanceState)

        auth = FirebaseAuth.getInstance()

        // Configure Google Sign In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id)) // Make sure you have this in strings.xml
            .requestEmail().build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        PaymentConfiguration.init(
            this, stripeApiKey
        )

        googlePayLauncher = GooglePayLauncher(
            activity = this, config = GooglePayLauncher.Config(
                environment = GooglePayEnvironment.Test,
                merchantCountryCode = "SE",
                merchantName = "Widget Store"
            ), readyCallback = ::onGooglePayReady, resultCallback = ::onGooglePayResult
        )

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DonationScreen(
                        onSignInClick = { onClickSignIn() },
                        onPayClick = {
                            if (clientSecret != null && isGooglePayReady.value) {
                                googlePayLauncher.presentForPaymentIntent(clientSecret!!)
                            } else {
                                if (!isGooglePayReady.value) {
                                    Toast.makeText(
                                        this@DonationActivity,
                                        "Google Pay is not ready",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        this@DonationActivity,
                                        "Failed to get client secret",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        signInButtonText = signInButtonText,
                        googlePayButtonViewState = googlePayButtonViewState
                    )
                }
            }
        }

        updateInitialSignInStatus()
    }

    private fun updateInitialSignInStatus() {
        Log.d(logTag, "updateInitialSignInStatus: Started")

        if (auth.currentUser != null) {
            // User is signed in
            onSuccessSignInOut(auth.currentUser)
        } else {
            // User is signed out
            onSuccessSignInOut(null)
        }
    }

    private fun onClickSignIn() {
        Log.d(logTag, "onClickSignIn: Started")
        if (auth.currentUser != null) {
            onClickSignOut()
        } else {
            val signInIntent = googleSignInClient.signInIntent
            signInScreenLauncher.launch(signInIntent)
        }
    }

    private val signInScreenLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d(logTag, "Google sign in returned RESULT_OK")
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                launchFirebaseAuthWithGoogle(account)
            } catch (e: ApiException) {
                Log.w(logTag, "Google sign in failed", e)
                Log.w(logTag, "Statuscode: ${e.statusCode}")
                Log.w(logTag, "Message: ${e.message}")
            }
        } else {
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
        }
    }

    private fun launchFirebaseAuthWithGoogle(account: GoogleSignInAccount) {
        Log.d(logTag, "launchFirebaseAuthWithGoogle started")
        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
        auth.signInWithCredential(credential).addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                // Sign in success, update UI with the signed-in user's information
                Log.d(logTag, "signInWithCredential:success")
                onSuccessSignInOut(auth.currentUser)
            } else {
                // If sign in fails, display a message to the user.
                Log.w(logTag, "signInWithCredential:failure", task.exception)
                Toast.makeText(this, "Authentication Failed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onSuccessSignInOut(user: FirebaseUser?) {
        if (user != null) {
            // User is signed in
            Log.d(logTag, "onSuccessSignInOut: Sign Out On Click")
            signInButtonText.value = "Sign Out"
            // Attempt to enable Google Pay
            googlePayButtonViewState.value = GooglePayButtonViewState.Loading
            fetchClientSecret()
        } else {
            // User is signed out
            Log.d(logTag, "onSuccessSignInOut: Sign In On Click")
            signInButtonText.value = "Sign In"
            // Disable Google Pay
            googlePayButtonViewState.value = GooglePayButtonViewState.Hidden
        }
    }

    // Sign out function
    private fun onClickSignOut() {
        auth.signOut()
        googleSignInClient.signOut().addOnCompleteListener(this) {
            // Update UI after sign out
            onSuccessSignInOut(null)
        }
    }

    private fun fetchClientSecret() {
        Log.d(logTag, "fetchClientSecret: Started")
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val jsonBody = JSONObject().apply {
            put("amount", 500) // Amount in cents (5 SEK)
        }
        val requestBody = jsonBody.toString().toRequestBody(mediaType)
        val request =
            Request.Builder().url("$serverUrl/createPaymentIntent")
                .post(requestBody).build()
        Log.d(logTag, "fetchClientSecret: Request URL: ${request.url}")

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(logTag, "fetchClientSecret: Failed to fetch clientSecret", e)
                runOnUiThread {
                    Toast.makeText(
                        this@DonationActivity, "Failed to connect to server", Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d(logTag, "fetchClientSecret: Response received")
                if (!response.isSuccessful) {
                    Log.e(
                        logTag,
                        "fetchClientSecret: Server returned an error: ${response.code}"
                    )
                    runOnUiThread {
                        Toast.makeText(
                            this@DonationActivity,
                            "Server error: ${response.code}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return
                }

                val responseBody = response.body?.string()
                if (responseBody == null) {
                    Log.e(logTag, "fetchClientSecret: Empty response body")
                    runOnUiThread {
                        Toast.makeText(
                            this@DonationActivity,
                            "Empty response from server",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return
                }

                try {
                    val jsonObject = JSONObject(responseBody)
                    clientSecret = jsonObject.getString("clientSecret")
                    Log.d(logTag, "fetchClientSecret: clientSecret: $clientSecret")

                    if (clientSecret != null) {
                        Log.d(logTag, "fetchClientSecret: GPay is ready")
                        googlePayButtonViewState.value = GooglePayButtonViewState.Ready
                    } else {
                        runOnUiThread {
                            Toast.makeText(
                                this@DonationActivity,
                                "Failed to get client secret",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        Log.d(logTag, "fetchClientSecret: GPay is hidden")
                        googlePayButtonViewState.value = GooglePayButtonViewState.Hidden
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "fetchClientSecret: Failed to parse JSON", e)
                    runOnUiThread {
                        Toast.makeText(
                            this@DonationActivity,
                            "Failed to parse server response",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
    }
}