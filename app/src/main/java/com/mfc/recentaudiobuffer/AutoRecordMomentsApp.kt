package com.mfc.recentaudiobuffer

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
            "recording_channel", "Recording Into RingBuffer", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Channel for the persistent recording notification banner"
        }

        // Result Channel
        val resultChannel = NotificationChannel(
            FileSavingService.RESULT_NOTIFICATION_CHANNEL_ID,
            FileSavingService.RESULT_NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = FileSavingService.RESULT_NOTIFICATION_CHANNEL_DESCRIPTION
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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