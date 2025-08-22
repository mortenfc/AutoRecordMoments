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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settingsRepository: SettingsRepository
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val RESTART_REMINDER_CHANNEL_ID = "restart_reminder_channel"
        const val RESTART_NOTIFICATION_ID = 2
        private const val REQUEST_CODE_RESTART = 4 // Use a unique request code
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        Timber.i("Boot completed, checking if buffering reminder is needed.")

        val pendingResult = goAsync()
        coroutineScope.launch {
            try {
                // We assume SettingsRepository is updated to provide these values
                val settings = settingsRepository.getSettingsConfig()
                val wasBuffering = settings.wasBufferingActive
                val lastActive = settings.lastActiveTimestamp
                val timeSinceLastActive = System.currentTimeMillis() - lastActive
                val oneWeekInMillis = TimeUnit.DAYS.toMillis(7)

                Timber.d("wasBuffering: $wasBuffering, timeSinceLastActive: $timeSinceLastActive ms")

                if (wasBuffering && timeSinceLastActive > oneWeekInMillis) {
                    Timber.i("Conditions met. Showing restart buffering notification.")
                    showRestartNotification(context)
                    // Reset the flag so it doesn't show again on the next reboot
                    settingsRepository.updateWasBufferingActive(false)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error processing boot completed event.")
            } finally {
                pendingResult.finish()
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun showRestartNotification(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // The notification channel is created in AutoRecordMomentsApp.kt
        // but it's safe to call createNotificationChannel multiple times.
        val channel = NotificationChannel(
            RESTART_REMINDER_CHANNEL_ID,
            "Buffering Reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Reminders related to audio buffering."
        }
        notificationManager.createNotificationChannel(channel)

        // Intent to start the service when the notification action is tapped
        val restartIntent = Intent(context, MyBufferService::class.java).apply {
            action = MyBufferService.ACTION_START_RECORDING_SERVICE
        }
        val restartPendingIntent = PendingIntent.getService(
            context,
            REQUEST_CODE_RESTART,
            restartIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Intent to open the main app when the notification is tapped
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )


        val notification = NotificationCompat.Builder(context, RESTART_REMINDER_CHANNEL_ID)
            .setContentTitle("Restart Buffering?")
            .setContentText("Your device was off for a while. Tap to resume audio buffering.")
            .setSmallIcon(R.drawable.baseline_record_voice_over_24)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                R.drawable.baseline_mic_24,
                "Restart Buffering",
                restartPendingIntent
            )
            .setAutoCancel(true) // Dismiss the notification when tapped
            .build()

        notificationManager.notify(RESTART_NOTIFICATION_ID, notification)
    }
}