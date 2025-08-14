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

import CrashReportingTree
import InfoLogTree
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class AutoRecordMomentsApp : Application(), Application.ActivityLifecycleCallbacks,
    DefaultLifecycleObserver {
    @Inject
    lateinit var interstitialAdManager: InterstitialAdManager
    private var isAppComingToForeground = false
    private val isAdSdkInitialized = AtomicBoolean(false)
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super<Application>.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        registerActivityLifecycleCallbacks(this)

        this.applicationScope.launch {
            AdInitializer.isInitialized.collect { isInitialized ->
                if (isInitialized) {
                    isAdSdkInitialized.set(true)
                }
            }
        }

        plantTimberTrees()
        createNotificationChannels()
    }

     private fun plantTimberTrees() {
        when (BuildConfig.BUILD_TYPE_NAME) {
            "debug" -> {
                // In debug, log everything to Logcat AND report warnings/errors to Crashlytics.
                Timber.plant(Timber.DebugTree())
                Timber.plant(CrashReportingTree())
                Timber.d("Timber: Full debug logging + Crashlytics reporting enabled.")
            }
            "staging" -> {
                // In staging, log INFO+ to Logcat AND report warnings/errors to Crashlytics.
                Timber.plant(InfoLogTree())
                Timber.plant(CrashReportingTree())
                Timber.i("Timber: Info logging + Crashlytics reporting enabled for staging.")
            }
            "release" -> {
                // In release, ONLY report warnings/errors to Crashlytics. Nothing in Logcat.
                Timber.plant(CrashReportingTree())
            }
        }
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
        if (isAppComingToForeground && isAdSdkInitialized.get()) {
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