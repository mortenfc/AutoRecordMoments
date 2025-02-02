package com.mfc.recentaudiobuffer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class RecentAudioBufferApplication : Application() {
    companion object {
        lateinit var instance: RecentAudioBufferApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}