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

import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class InactivityAndBootTest {

    // Hilt rule must be the first rule defined.
    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    // Inject dependencies from the Hilt graph.
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private lateinit var context: Context
    private lateinit var uiDevice: UiDevice

    // Timeout for waiting for notifications to appear.
    private val NOTIFICATION_TIMEOUT = 5000L

    @Before
    fun setup() {
        // Initialize Hilt components for the test.
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
        // Get an instance of UiDevice to interact with the device UI (like notifications).
        uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Ensure the screen is on for UI interactions.
        uiDevice.wakeUp()
        // Clear any previous notifications to ensure a clean slate for each test.
        uiDevice.pressHome() // Go to home screen to dismiss any dialogs
        uiDevice.openNotification()
        val clearAllButton = uiDevice.findObject(By.text("Clear all"))
        if (clearAllButton != null) {
            clearAllButton.click()
        } else {
            // Fallback if "Clear all" is not found
            uiDevice.pressBack()
        }
        // Wait for the notification shade to close.
        uiDevice.wait(Until.gone(By.text("Clear all")), NOTIFICATION_TIMEOUT)
    }

    @After
    fun tearDown() {
        // Clean up settings after each test to ensure test isolation.
        runBlocking {
            settingsRepository.updateWasBufferingActive(false)
            settingsRepository.updateLastActiveTimestamp(0L)
        }
    }

    // --- InactivityCheckWorker Tests ---

    @Test
    fun inactivityWorker_postsNotification_whenUserIsInactiveForOver7Days() = runBlocking {
        // Arrange: Simulate the condition where the user has been inactive.
        val eightDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)
        settingsRepository.updateWasBufferingActive(false)
        settingsRepository.updateLastActiveTimestamp(eightDaysAgo)

        // Act: Build and run the worker directly.
        val worker = TestListenableWorkerBuilder<InactivityCheckWorker>(context)
            .setWorkerFactory(workerFactory)
            .build()
        val result = worker.doWork()

        // Assert: Check that the worker completed successfully.
        assertThat(result).isEqualTo(ListenableWorker.Result.success())

        // Assert: Verify that the inactivity notification is now visible.
        uiDevice.openNotification()
        val notificationTitle = "Feeling a Little Rusty?"
        val notificationVisible = uiDevice.wait(Until.hasObject(By.text(notificationTitle)), NOTIFICATION_TIMEOUT)
        assertThat(notificationVisible).isTrue()
    }

    @Test
    fun inactivityWorker_doesNotPostNotification_whenUserWasRecentlyActive() = runBlocking {
        // Arrange: Simulate a recently active user.
        val oneDayAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        settingsRepository.updateWasBufferingActive(false)
        settingsRepository.updateLastActiveTimestamp(oneDayAgo)

        // Act
        val worker = TestListenableWorkerBuilder<InactivityCheckWorker>(context)
            .setWorkerFactory(workerFactory)
            .build()
        val result = worker.doWork()

        // Assert
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        uiDevice.openNotification()
        val notificationTitle = "Feeling a Little Rusty?"
        val notificationVisible = uiDevice.wait(Until.hasObject(By.text(notificationTitle)), NOTIFICATION_TIMEOUT)
        assertThat(notificationVisible).isFalse()
    }

    // --- BootReceiver Tests ---

    @Test
    fun bootReceiver_postsNotification_whenRebootingAfterLongInactivity() = runBlocking {
        // Arrange: Simulate that the app was buffering and the device was off for a long time.
        val tenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)
        settingsRepository.updateWasBufferingActive(true) // Crucially, buffering was active.
        settingsRepository.updateLastActiveTimestamp(tenDaysAgo)

        // Act: Simulate the boot completed event by sending the broadcast intent.
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED).apply {
            // Set the package to ensure only our app's receiver gets it.
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)

        // Give the receiver time to process the broadcast and post the notification.
        Thread.sleep(2000)

        // Assert: Check if the correct notification was posted.
        uiDevice.openNotification()
        val notificationTitle = "Restart Buffering?"
        val notificationVisible = uiDevice.wait(Until.hasObject(By.text(notificationTitle)), NOTIFICATION_TIMEOUT)
        assertThat(notificationVisible).isTrue()
    }

    @Test
    fun bootReceiver_doesNotPostNotification_whenBufferingWasNotActive() = runBlocking {
        // Arrange: Simulate that the app was NOT buffering before the device shut down.
        val tenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)
        settingsRepository.updateWasBufferingActive(false) // Buffering was off.
        settingsRepository.updateLastActiveTimestamp(tenDaysAgo)

        // Act
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
        Thread.sleep(2000)

        // Assert
        uiDevice.openNotification()
        val notificationTitle = "Restart Buffering?"
        val notificationVisible = uiDevice.wait(Until.hasObject(By.text(notificationTitle)), NOTIFICATION_TIMEOUT)
        assertThat(notificationVisible).isFalse()
    }
}
