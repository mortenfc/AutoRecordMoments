package com.mfc.recentaudiobuffer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {

    // 1. Define an EntryPoint to get dependencies from the Hilt graph.
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootReceiverEntryPoint {
        fun settingsRepository(): SettingsRepository
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val RESTART_REMINDER_CHANNEL_ID = "restart_reminder_channel"
        private const val RESTART_NOTIFICATION_ID = 101
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        // 2. Manually get the EntryPoint from the application context.
        val hiltEntryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            BootReceiverEntryPoint::class.java
        )

        // 3. Access the repository through the entry point.
        val settingsRepository = hiltEntryPoint.settingsRepository()

        scope.launch {
            val settings = settingsRepository.getSettingsConfig()
            if (settings.wasBufferingActive) {
                Timber.i("Device booted and buffering was active. Showing restart notification.")
                showRestartNotification(context)
            } else {
                Timber.i("Device booted, but buffering was not active. No action needed.")
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun showRestartNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val mainActivityIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, mainActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, RESTART_REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_record_voice_over_24)
            .setContentTitle("Restart Audio Buffering?")
            .setContentText("The app was recording when the device shut down. Tap to restart.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(RESTART_NOTIFICATION_ID, notification)
    }
}
