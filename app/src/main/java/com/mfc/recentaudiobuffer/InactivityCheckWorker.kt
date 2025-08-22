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
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.media3.common.util.UnstableApi
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

@HiltWorker
class InactivityCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val INACTIVITY_REMINDER_CHANNEL_ID = "inactivity_reminder_channel"
        const val INACTIVITY_NOTIFICATION_ID = 3 // Use a new, unique ID
        const val WORKER_TAG = "inactivity-check-worker"
        const val INACTIVITY_PERIOD_DAYS = 7L
    }

    override suspend fun doWork(): Result {
        Timber.d("InactivityCheckWorker running.")

        try {
            val settings = settingsRepository.getSettingsConfig()

            // 1. If the service is currently buffering, do nothing.
            if (settings.wasBufferingActive) {
                Timber.d("Worker exit: Buffering is currently active.")
                return Result.success()
            }

            // 2. If the user has never used the app, the timestamp will be 0. Do nothing.
            if (settings.lastActiveTimestamp == 0L) {
                Timber.d("Worker exit: App has not been used yet.")
                return Result.success()
            }

            val timeSinceLastActive = System.currentTimeMillis() - settings.lastActiveTimestamp
            val inactivityPeriodMillis = TimeUnit.DAYS.toMillis(INACTIVITY_PERIOD_DAYS)

            // 3. Check if the inactivity period has been exceeded.
            if (timeSinceLastActive > inactivityPeriodMillis) {
                Timber.i("User has been inactive for over $INACTIVITY_PERIOD_DAYS days. Showing notification.")
                showInactivityNotification(applicationContext)
            } else {
                Timber.d("Worker exit: User is still within the active period.")
            }

        } catch (e: Exception) {
            Timber.e(e, "Error in InactivityCheckWorker.")
            return Result.failure() // Return failure if something went wrong
        }

        return Result.success()
    }

    @OptIn(UnstableApi::class)
    private fun showInactivityNotification(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create channel (safe to call repeatedly)
        val channel = NotificationChannel(
            INACTIVITY_REMINDER_CHANNEL_ID,
            "Inactivity Reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "A friendly reminder to use the app after a period of inactivity."
        }
        notificationManager.createNotificationChannel(channel)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, INACTIVITY_REMINDER_CHANNEL_ID)
            .setContentTitle("Feeling a Little Rusty?")
            .setContentText("It's been a while! Tap here to get back to buffering your moments.")
            .setSmallIcon(R.drawable.baseline_record_voice_over_24)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppPendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(INACTIVITY_NOTIFICATION_ID, notification)
    }
}