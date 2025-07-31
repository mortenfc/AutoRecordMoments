package com.mfc.recentaudiobuffer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException

// Data class to hold the rules fetched from the backend
data class CurrencyRule(val multiplier: Int, val min: Int)

// State for the donation screen
data class DonationScreenState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val rules: Map<String, CurrencyRule> = emptyMap(),
    val isGooglePayReady: Boolean = false
)

class DonationViewModel : ViewModel() {
    private val httpClient = OkHttpClient()
    var uiState by mutableStateOf(DonationScreenState())
        private set

    init {
        fetchCurrencyRules()
    }

    fun setGooglePayReady(isReady: Boolean) {
        uiState = uiState.copy(isGooglePayReady = isReady)
    }

    private fun fetchCurrencyRules() {
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true)
            val request =
                Request.Builder().url("${DonationConstants.SERVER_URL}/getCurrencyRules").build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Timber.e(e, "Failed to fetch currency rules")
                    uiState =
                        uiState.copy(isLoading = false, error = "Could not connect to server.")
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        uiState = uiState.copy(
                            isLoading = false, error = "Server error: ${response.code}"
                        )
                        return
                    }

                    val body = response.body?.string()
                    if (body == null) {
                        uiState =
                            uiState.copy(isLoading = false, error = "Empty response from server.")
                        return
                    }

                    try {
                        val json = JSONObject(body)
                        val rules = json.keys().asSequence().associateWith { key ->
                            val ruleJson = json.getJSONObject(key)
                            CurrencyRule(
                                multiplier = ruleJson.getInt("multiplier"),
                                min = ruleJson.getInt("min")
                            )
                        }
                        uiState = uiState.copy(isLoading = false, rules = rules)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse currency rules")
                        uiState =
                            uiState.copy(isLoading = false, error = "Failed to parse server data.")
                    }
                }
            })
        }
    }
}