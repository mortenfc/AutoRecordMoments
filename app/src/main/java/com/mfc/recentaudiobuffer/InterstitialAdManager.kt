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

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

object AdInitializer {
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized = _isInitialized.asStateFlow()

    fun initialize(context: Context) {
        if (_isInitialized.value) return // Already initialized

        MobileAds.initialize(context) {
            Timber.d("MobileAds initialized successfully.")
            _isInitialized.value = true
        }
    }
}

@Singleton
class InterstitialAdManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // A SharedFlow to notify the UI when the reward state changes.
    private val _rewardStateChanged = MutableSharedFlow<Unit>()
    val rewardStateChanged = _rewardStateChanged.asSharedFlow()

    private var rewardedInterstitialAd: RewardedInterstitialAd? = null

    companion object {
        private const val AD_PREFS = "AdPrefs"
        private const val KEY_REWARD_EXPIRY_TIMESTAMP = "rewardExpiryTimestamp"

        private const val INITIAL_OPEN_COUNT_GOAL = 6
        private const val CONTINUED_OPEN_COUNT_GOAL = 2
        private val REWARD_DURATION_MS = TimeUnit.DAYS.toMillis(2)
        
        private val REWARDED_INTERSTITIAL_AD_UNIT_ID = BuildConfig.REWARDED_INTERSTITIAL_AD_ID
    }

    fun getRewardExpiryTimestamp(): Long {
        val prefs = context.getSharedPreferences(AD_PREFS, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_REWARD_EXPIRY_TIMESTAMP, 0L)
    }

    private fun isRewardActive(): Boolean {
        return System.currentTimeMillis() < getRewardExpiryTimestamp()
    }

    private fun grantReward() {
        val prefs = context.getSharedPreferences(AD_PREFS, Context.MODE_PRIVATE)
        val expiryTimestamp = System.currentTimeMillis() + REWARD_DURATION_MS
        prefs.edit { putLong(KEY_REWARD_EXPIRY_TIMESTAMP, expiryTimestamp) }
        Timber.d("Reward granted. Launch ads disabled until: $expiryTimestamp")
    }

    fun showAdOnSecondOpen(activity: Activity) {
        if (isRewardActive()) {
            Timber.d("Reward is active. Skipping ad check.")
            return
        }

        val prefs = context.getSharedPreferences(AD_PREFS, Context.MODE_PRIVATE)
        val appOpenCount = prefs.getInt("appOpenCount", 0) + 1
        val openCountGoal = prefs.getInt("openCountGoal", INITIAL_OPEN_COUNT_GOAL)
        prefs.edit { putInt("appOpenCount", appOpenCount) }

        // Check if it's time to show an ad.
        if (appOpenCount % openCountGoal != 0) {
            Timber.d("App open count is $appOpenCount. Not an ad trigger.")
            return
        }

        // If this is the trigger for the first ad, update the goal for all future launches.
        if (appOpenCount == INITIAL_OPEN_COUNT_GOAL) {
            prefs.edit { putInt("openCountGoal", CONTINUED_OPEN_COUNT_GOAL) }
            Timber.d("Initial ad goal met. Updating goal to $CONTINUED_OPEN_COUNT_GOAL for future launches.")
        }

        // Prevent loading a new ad while one is already showing.
        if (rewardedInterstitialAd != null) {
            return
        }
        Timber.d("App open count is $appOpenCount. Loading Rewarded Interstitial Ad.")
        val adRequest = AdRequest.Builder().build()
        RewardedInterstitialAd.load(
            activity,
            REWARDED_INTERSTITIAL_AD_UNIT_ID,
            adRequest,
            object : RewardedInterstitialAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Timber.e("Ad failed to load: ${adError.message}")
                    rewardedInterstitialAd = null
                }

                override fun onAdLoaded(ad: RewardedInterstitialAd) {
                    Timber.d("Ad was loaded.")
                    rewardedInterstitialAd = ad

                    val options = ServerSideVerificationOptions.Builder()
                        .setCustomData("${TimeUnit.MILLISECONDS.toDays(REWARD_DURATION_MS)} Ad-Free Days") // A string to identify the reward
                        .build()
                    rewardedInterstitialAd?.setServerSideVerificationOptions(options)

                    rewardedInterstitialAd?.fullScreenContentCallback =
                        object : FullScreenContentCallback() {
                            override fun onAdDismissedFullScreenContent() {
                                Timber.d("Ad was dismissed.")
                                // Set to null so the ad can be loaded again.
                                rewardedInterstitialAd = null
                            }

                            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                                Timber.e("Ad failed to show: ${adError.message}")
                                rewardedInterstitialAd = null
                            }

                            override fun onAdShowedFullScreenContent() {
                                Timber.d("Ad showed fullscreen content.")
                            }
                        }

                    // Show the ad and implement the onReward callback
                    rewardedInterstitialAd?.show(activity) {
                        Timber.d("User earned reward: ${it.amount} ${it.type}")
                        grantReward()
                        CoroutineScope(Dispatchers.Main).launch {
                            _rewardStateChanged.emit(Unit)
                        }
                    }
                }
            })
    }
}