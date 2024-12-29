package com.mfc.recentaudiobuffer

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.SignInButton
import com.google.android.gms.wallet.PaymentsClient
import com.google.android.gms.wallet.button.PayButton
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
    private lateinit var paymentsClient: PaymentsClient
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var auth: FirebaseAuth
    private lateinit var googlePayButton: PayButton
    private lateinit var googlePayLauncher: GooglePayLauncher
    private lateinit var googleSignInButton: SignInButton // Changed type hereprivate lateinit var googlePayLauncher: GooglePayLauncher
    private var clientSecret: String? = null
    private val stripeApiKey: String = "pk_test_51Qb05qH7rOdAu0fXFO9QEU8ygiSSOdlkqDofr9nSI54UHdWbxfIj0Iz0BBKIGlfzxwEUJTUOVILcNEVYs2UNS0Af00yMhr6dX1"

    private fun onGooglePayReady(isReady: Boolean) {
        googlePayButton.isEnabled = isReady
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
                Toast.makeText(this, "Payment Failed: ${result.error.message}", Toast.LENGTH_SHORT).show()
                Log.e(logTag, "Payment Failed", result.error)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.donation_layout)

        paymentsClient = PaymentsUtil.createPaymentsClient(this)
        auth = FirebaseAuth.getInstance()

        googlePayButton = findViewById(R.id.googlePayButton)
        googleSignInButton = findViewById(R.id.sign_in_button)

        // Configure Google Sign In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id)) // Make sure you have this in strings.xml
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        PaymentConfiguration.init(
            this,
            stripeApiKey
        )

        googlePayLauncher = GooglePayLauncher(
            activity = this,
            config = GooglePayLauncher.Config(
                environment = GooglePayEnvironment.Test,
                merchantCountryCode = "US",
                merchantName = "Widget Store"
            ),
            readyCallback = ::onGooglePayReady,
            resultCallback = ::onGooglePayResult
        )

        // Check if the user is already signed in
        if (auth.currentUser != null) {
            // User is signed in, enable Google Pay
            enableGooglePay()
        } else {
            // User is not signed in, show sign-in button
            googlePayButton.isEnabled = false
            googleSignInButton.setOnClickListener {
                signIn()
            }
        }
    }

    private fun signIn() {
        val signInIntent = googleSignInClient.signInIntent
        launcher.launch(signInIntent)
    }

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                firebaseAuthWithGoogle(account)
            } catch (e: ApiException) {
                Log.w(logTag, "Google sign in failed", e)
            }
        }
    }

    private fun firebaseAuthWithGoogle(account: GoogleSignInAccount) {
        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Sign in success, update UI with the signed-in user's information
                    Log.d(logTag, "signInWithCredential:success")
                    val user = auth.currentUser
                    updateUI(user)
                } else {
                    // If sign in fails, display a message to the user.
                    Log.w(logTag, "signInWithCredential:failure", task.exception)
                    Toast.makeText(this, "Authentication Failed.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun updateUI(user: FirebaseUser?) {
        if (user != null) {
            // User is signed in, enable Google Pay
            enableGooglePay()
        } else {
            // User is signed out, disable Google Pay
            googlePayButton.isEnabled = false
        }
    }

    private fun enableGooglePay() {
        googlePayButton.isEnabled = true
        googleSignInButton.isEnabled = false
        googleSignInButton.visibility = View.GONE
        // Fetch the clientSecret from the server before launching Google Pay
        fetchClientSecret()
    }

    private fun fetchClientSecret() {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = "{}".toRequestBody(mediaType) // Empty JSON body for now
        val request = Request.Builder()
            .url("$serverUrl/create-payment-intent") // Your server endpoint
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(logTag, "Failed to fetch clientSecret", e)
                runOnUiThread {
                    Toast.makeText(
                        this@DonationActivity,
                        "Failed to connect to server",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e(logTag, "Server returned an error: ${response.code}")
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
                    Log.e(logTag, "Empty response body")
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
                    Log.d(logTag, "clientSecret: $clientSecret")

                    runOnUiThread {
                        // Now that we have the clientSecret, launch Google Pay
                        if (clientSecret != null) {
                            googlePayButton.setOnClickListener {
                                googlePayLauncher.presentForPaymentIntent(clientSecret!!)
                            }
                        } else {
                            Toast.makeText(
                                this@DonationActivity,
                                "Failed to get client secret",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "Failed to parse JSON", e)
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