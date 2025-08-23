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
 * GNU Affero General Public License for more details
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.mfc.recentaudiobuffer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication
import timber.log.Timber

/**
 * A custom runner for Hilt tests. This runner is necessary to replace the
 * default Application class with Hilt's test application, enabling
 * dependency injection in instrumented tests. It also sets up necessary
 * components like Notification Channels for the test environment.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?, className: String?, context: Context?
    ): Application {
        // Plant a Timber tree for logging during tests.
        Timber.plant(Timber.DebugTree())
        // Create all necessary notification channels before tests run.
        createNotificationChannels(context)
        // Return the HiltTestApplication to enable dependency injection.
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }

    private fun createNotificationChannels(context: Context?) {
        // Use a null-safe call in case the context is null for any reason.
        context?.let {
            val manager = it.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Recording Channel (for MyBufferService)
            val recordingChannel = NotificationChannel(
                MyBufferService.CHRONIC_NOTIFICATION_CHANNEL_ID,
                "Background Recording in Ring-Buffer",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Persistent notification for audio buffering controls."
            }

            // 2. Result Channel (for FileSavingService)
            val resultChannel = NotificationChannel(
                FileSavingService.RESULT_NOTIFICATION_CHANNEL_ID,
                FileSavingService.RESULT_NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = FileSavingService.RESULT_NOTIFICATION_CHANNEL_DESCRIPTION
            }

            // 3. Boot Reminder Channel (for BootReceiver)
            val bootReminderChannel = NotificationChannel(
                BootReceiver.RESTART_REMINDER_CHANNEL_ID,
                "Buffering Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Reminders related to audio buffering after a device restart."
            }

            // 4. Inactivity Reminder Channel (for InactivityCheckWorker)
            val inactivityChannel = NotificationChannel(
                InactivityCheckWorker.INACTIVITY_REMINDER_CHANNEL_ID,
                "Inactivity Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "A friendly reminder to use the app after a period of inactivity."
            }

            // Create all the channels on the system.
            manager.createNotificationChannel(recordingChannel)
            manager.createNotificationChannel(resultChannel)
            manager.createNotificationChannel(bootReminderChannel)
            manager.createNotificationChannel(inactivityChannel)
        }
    }
}
