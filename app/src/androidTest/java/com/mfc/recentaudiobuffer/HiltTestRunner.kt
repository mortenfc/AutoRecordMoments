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
 * dependency injection in instrumented tests.
 */

class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?, className: String?, context: Context?
    ): Application {
        Timber.plant(Timber.DebugTree())
        // ✅ Use the context parameter to call the function
        createNotificationChannels(context)
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }

    private fun createNotificationChannels(context: Context?) {
        // Use a null-safe call in case the context is null
        context?.let {
            // Recording Channel
            val recordingChannel = NotificationChannel(
                "recording_channel",
                "Recording Into RingBuffer",
                NotificationManager.IMPORTANCE_DEFAULT
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

            // ✅ Get the system service from the context
            val manager = it.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(recordingChannel)
            manager.createNotificationChannel(resultChannel)
        }
    }
}