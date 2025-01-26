package com.mfc.recentaudiobuffer

import android.util.DisplayMetrics
import android.util.Log
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds

@Composable
fun AdMobBanner(modifier: Modifier = Modifier, settingsViewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val areAdsEnabled by settingsViewModel.areAdsEnabled.collectAsState(initial = true)

    LaunchedEffect(key1 = Unit) {
        MobileAds.initialize(context)
    }

    if (areAdsEnabled) {
        val density = LocalDensity.current
        val adWidth = with(density) {
            val displayMetrics: DisplayMetrics = context.resources.displayMetrics
            displayMetrics.widthPixels.toDp()
        }
        val adSize: AdSize =
            AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth.value.toInt())

        AndroidView(modifier = modifier, factory = {
            AdView(it).apply {
                Log.d("AdMobBanner", "AdView created")
                setAdSize(adSize)
                // Replace with your actual ad unit ID
                adUnitId = "ca-app-pub-5330230981165217/6883566605"
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        Log.d("AdMobBanner", "onAdLoaded")
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        Log.e("AdMobBanner", "onAdFailedToLoad: ${adError.message}")
                    }

                    override fun onAdOpened() {
                        Log.d("AdMobBanner", "onAdOpened")
                    }

                    override fun onAdClicked() {
                        Log.d("AdMobBanner", "onAdClicked")
                    }

                    override fun onAdClosed() {
                        Log.d("AdMobBanner", "onAdClosed")
                    }
                }
                loadAd(AdRequest.Builder().build())
            }
        }, update = {
            it.loadAd(AdRequest.Builder().build())
        })
    }
}