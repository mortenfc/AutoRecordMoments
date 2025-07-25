package com.mfc.recentaudiobuffer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class RecentAudioBufferApplication : Application() {
    companion object {
        lateinit var instance: RecentAudioBufferApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("Timber logging is enabled for debug build.")
        }
        instance = this
        createNotificationChannels()
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
}