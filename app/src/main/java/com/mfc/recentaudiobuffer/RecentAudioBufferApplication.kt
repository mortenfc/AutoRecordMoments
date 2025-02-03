package com.mfc.recentaudiobuffer

import android.app.Application
import androidx.lifecycle.ViewModel
import dagger.hilt.android.HiltAndroidApp

class SharedViewModel : ViewModel() {
    var myBufferService: MyBufferServiceInterface? = null
}

@HiltAndroidApp
class RecentAudioBufferApplication : Application() {
    lateinit var sharedViewModel: SharedViewModel

    companion object {
        lateinit var instance: RecentAudioBufferApplication
            private set

        fun getSharedViewModel(application: Application): SharedViewModel {
            return (application as RecentAudioBufferApplication).sharedViewModel
        }
    }

    override fun onCreate() {
        super.onCreate()
        sharedViewModel = SharedViewModel()
        instance = this
    }
}