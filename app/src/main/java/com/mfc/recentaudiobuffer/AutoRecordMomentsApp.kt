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

    var currentActivity: Activity? = null

    override fun onCreate() {
        super<Application>.onCreate()
        registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("Timber logging is enabled for debug build.")
        }
        createNotificationChannels()
        MobileAds.initialize(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onCreate(owner)
        // Use the currently visible Activity to show the ad
        currentActivity?.let { activity ->
            interstitialAdManager.showAdOnSecondOpen(activity)
        }
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
    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
    }

    override fun onActivityPaused(activity: Activity) {
        currentActivity = null
    }

    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}