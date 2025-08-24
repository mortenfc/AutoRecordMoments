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
import org.joda.money.CurrencyUnit
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.Locale

// Data class to hold the rules fetched from the backend
data class CurrencyRule(val multiplier: Int, val min: Int)

// State for the donation screen
data class DonationScreenState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val rules: Map<String, CurrencyRule> = emptyMap(),
    val isGooglePayReady: Boolean = false,
    val selectedCurrency: CurrencyUnit = CurrencyUnit.of(Locale.getDefault())
)

class DonationViewModel : ViewModel() {
    private val httpClient = OkHttpClient()
    var uiState by mutableStateOf(DonationScreenState())
        private set

    var clientSecret: String? = null

    init {
        // Initialize with default currency
        uiState = uiState.copy(selectedCurrency = CurrencyUnit.of(Locale.getDefault()))
        fetchCurrencyRules()
    }

    fun updateSelectedCurrency(currencyCode: String) {
        // Only update if the currency is supported
        if (uiState.rules.containsKey(currencyCode)) {
            // Invalidate the client secret when currency changes
            clientSecret = null
            uiState = uiState.copy(selectedCurrency = CurrencyUnit.of(currencyCode))
        }
    }

    fun onAmountChanged() {
        // Any time the user edits the amount, the previous payment intent is invalid.
        clientSecret = null
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

                    val body = response.body.string()

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