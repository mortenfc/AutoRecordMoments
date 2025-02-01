package com.mfc.recentaudiobuffer

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.util.concurrent.atomic.AtomicReference

private const val MAXIMUM_UPDATE_PERIOD_MS : Long = 850

// For observed live data in another activity and thread

class AtomicLiveDataThrottled<T>(defaultValue: T) : LiveData<T>() {
    private val atomicValue = AtomicReference(defaultValue)
    private val handler = Handler(Looper.getMainLooper())
    private var lastUpdateTime: Long = 0
    private val updateRunnable = Runnable {
        val value = atomicValue.get()
        super.postValue(value)
        lastUpdateTime = System.currentTimeMillis()
    }

    override fun observe(owner: LifecycleOwner, observer: Observer<in T>) {
        // Observe on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post {
                super.observe(owner, observer)
            }
        } else {
            super.observe(owner, observer)
        }
    }

    public override fun postValue(value: T) {
        atomicValue.set(value)
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime >= MAXIMUM_UPDATE_PERIOD_MS) {
            handler.removeCallbacks(updateRunnable)
            handler.post(updateRunnable)
        } else {
            handler.removeCallbacks(updateRunnable)
            handler.postDelayed(updateRunnable, MAXIMUM_UPDATE_PERIOD_MS)
        }
    }

    public override fun setValue(value: T) {
        atomicValue.set(value)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            super.setValue(value)
        } else {
            postValue(value)
        }
    }

    public fun set(value: T) {
        setValue(value)
    }

    public fun get(): T {
        return atomicValue.get()
    }
}