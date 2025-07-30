package com.mfc.recentaudiobuffer

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

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
        private val REWARD_DURATION_MS = TimeUnit.DAYS.toMillis(3)
        private const val REWARDED_INTERSTITIAL_AD_UNIT_ID =
            "ca-app-pub-5330230981165217/4603016372"
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
        prefs.edit { putInt("appOpenCount", appOpenCount) }

        if (appOpenCount % 2 != 0) {
            Timber.d("App open count is $appOpenCount. Not an ad trigger.")
            return
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