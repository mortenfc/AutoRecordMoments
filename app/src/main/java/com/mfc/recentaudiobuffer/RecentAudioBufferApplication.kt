package com.mfc.recentaudiobuffer

import android.app.Application
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
    }
}