package com.mfc.recentaudiobuffer

import android.app.Application
import androidx.lifecycle.ViewModel
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

class SharedViewModel : ViewModel() {
    var myBufferService: MyBufferServiceInterface? = null
}

@HiltAndroidApp
class RecentAudioBufferApplication : Application() {
    lateinit var sharedViewModel: SharedViewModel

    companion object {
        lateinit var instance: RecentAudioBufferApplication
            private set

        fun getSharedViewModel(): SharedViewModel {
            return instance.sharedViewModel
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("Timber logging is enabled for debug build.")
        }
        sharedViewModel = SharedViewModel()
        instance = this
    }
}