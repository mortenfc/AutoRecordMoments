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

import android.util.DisplayMetrics
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import timber.log.Timber

@Composable
fun AdMobBanner(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val adWidth = with(density) {
        val displayMetrics: DisplayMetrics = context.resources.displayMetrics
        displayMetrics.widthPixels.toDp()
    }

    val adSize: AdSize =
        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth.value.toInt())

    AndroidView(modifier = modifier, factory = {
        AdView(it).apply {
            Timber.d("AdView created")
            setAdSize(adSize)
            adUnitId = "ca-app-pub-5330230981165217/6883566605"
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    Timber.d("onAdLoaded")
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Timber.e("onAdFailedToLoad: ${adError.message}")
                }

                override fun onAdOpened() {
                    Timber.d("onAdOpened")
                }

                override fun onAdClicked() {
                    Timber.d("onAdClicked")
                }

                override fun onAdClosed() {
                    Timber.d("onAdClosed")
                }
            }
            loadAd(AdRequest.Builder().build())
        }
    }, update = {
        it.loadAd(AdRequest.Builder().build())
    })
}