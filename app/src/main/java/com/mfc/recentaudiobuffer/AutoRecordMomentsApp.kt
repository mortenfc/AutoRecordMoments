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
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.MobileAds
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class AutoRecordMomentsApp : Application(), Application.ActivityLifecycleCallbacks,
    DefaultLifecycleObserver {
    @Inject
    lateinit var interstitialAdManager: InterstitialAdManager

    private var isAppComingToForeground = false

    override fun onCreate() {
        super<Application>.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        registerActivityLifecycleCallbacks(this)
        MobileAds.initialize(this)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("Timber logging is enabled for debug build.")
        }
        createNotificationChannels()
    }

    /**
     * This method is called when the app process moves to the foreground.
     * We just set a flag here to signal the event.
     */
    override fun onStart(owner: LifecycleOwner) {
        isAppComingToForeground = true
    }

    private fun createNotificationChannels() {
        // Recording Channel
        val recordingChannel = NotificationChannel(
            MyBufferService.CHRONIC_NOTIFICATION_CHANNEL_ID, "Background Recording in Ring-Buffer",
            // Use default importance to avoid sound on every update. Priority is for pre-Oreo.
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Persistent notification for audio buffering controls."
            // Request to show on lock screen, but user can override.
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            // The service updates frequently, so we don't want sound.
            setSound(null, null)
        }

        // Result Channel
        val resultChannel = NotificationChannel(
            FileSavingService.RESULT_NOTIFICATION_CHANNEL_ID,
            FileSavingService.RESULT_NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = FileSavingService.RESULT_NOTIFICATION_CHANNEL_DESCRIPTION
        }

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(recordingChannel)
        manager.createNotificationChannel(resultChannel)
    }

    // ## ActivityLifecycleCallbacks Implementation ##
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}

    /**
     * This method is called every time an activity is resumed.
     * It guarantees we have a valid activity context to work with.
     */
    override fun onActivityResumed(activity: Activity) {
        // Check if the app is coming to the foreground.
        if (isAppComingToForeground) {
            // Reset the flag immediately so the ad doesn't show on every single resume.
            isAppComingToForeground = false
            // Now, safely call the ad logic with a guaranteed valid activity.
            interstitialAdManager.showAdOnSecondOpen(activity)
        }
    }

    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}