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

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import io.mockk.verify

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class InactivityAndBootTest {

    // Hilt rule must run before other rules, so we give it a lower order number.
    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    // This rule grants the POST_NOTIFICATIONS permission required on Android 13+
    // for the app to show notifications during the test.
    @get:Rule(order = 1)
    var grantPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    // Inject dependencies from the Hilt graph.
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // The @BindValue annotation tells Hilt: "When any class asks for a
    // SettingsRepository, inject THIS mock instance instead of the real one."
    @BindValue
    @JvmField
    val mockSettingsRepository: SettingsRepository = mockk(relaxed = true)

    private lateinit var context: Context
    private lateinit var uiDevice: UiDevice
    private lateinit var notificationManager: NotificationManager

    // Timeout for waiting for notifications to appear.
    private val NOTIFICATION_TIMEOUT = 5000L

    @Before
    fun setup() {
        // Initialize Hilt components for the test.
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Get an instance of UiDevice to interact with the device UI (like notifications).
        uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Ensure the screen is on for UI interactions.
        uiDevice.wakeUp()
        // Go to the home screen to ensure a clean state.
        uiDevice.pressHome()
        // Programmatically clear all notifications for this app before each test.
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        clearMocks(mockSettingsRepository)
        notificationManager.cancelAll()
        uiDevice.pressBack()
    }

    // --- InactivityCheckWorker Tests ---

    @Test
    fun inactivityWorker_postsNotification_whenUserIsInactiveForOver7Days() = runBlocking {
        // Arrange: Simulate the condition where the user has been inactive.
        val eightDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)
        val inactiveSettings = SettingsConfig(
            wasBufferingActive = false, lastActiveTimestamp = eightDaysAgo
        )
        coEvery { mockSettingsRepository.getSettingsConfig() } returns inactiveSettings

        // Act: Build and run the worker directly.
        val worker = TestListenableWorkerBuilder<InactivityCheckWorker>(context).setWorkerFactory(
            workerFactory
        ).build()
        val result = worker.doWork()

        // Assert: Check that the worker completed successfully.
        assertThat(result).isEqualTo(ListenableWorker.Result.success())

        // Assert: Verify that the inactivity notification is now visible using a robust wait.
        uiDevice.openNotification()
        val notificationTitle = "Feeling a Little Rusty?"
        val notificationVisible =
            uiDevice.wait(Until.hasObject(By.text(notificationTitle)), NOTIFICATION_TIMEOUT)
        assertThat(notificationVisible).isTrue()
    }

    @Test
    fun inactivityWorker_doesNotPostNotification_whenUserWasRecentlyActive() = runBlocking {
        // Arrange: Simulate a recently active user.
        val oneDayAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        val activeSettings = SettingsConfig(
            wasBufferingActive = false, lastActiveTimestamp = oneDayAgo
        )
        coEvery { mockSettingsRepository.getSettingsConfig() } returns activeSettings

        // Act
        val worker = TestListenableWorkerBuilder<InactivityCheckWorker>(context).setWorkerFactory(
            workerFactory
        ).build()
        val result = worker.doWork()

        // Assert
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        uiDevice.openNotification()
        val notificationTitle = "Feeling a Little Rusty?"
        // Verify that the notification does NOT appear within the timeout.
        val notificationVisible =
            uiDevice.wait(Until.hasObject(By.text(notificationTitle)), NOTIFICATION_TIMEOUT)
        assertThat(notificationVisible).isFalse()
    }

    // --- BootReceiver Tests ---

    @Test
    fun bootReceiver_postsNotification_whenRebootingAfterLongInactivity() = runBlocking {
        // Arrange: Simulate that the app was buffering and the device was off for a long time.
        val tenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)
        coEvery {
            mockSettingsRepository.getSettingsConfig()
        } returns SettingsConfig(
            wasBufferingActive = true, lastActiveTimestamp = tenDaysAgo
        )


        // Create a spy of the receiver to intercept the problematic goAsync() call.
        val receiverSpy = spyk(BootReceiver())
        // Stub goAsync() to return a relaxed mock, preventing the test from crashing.
        val mockPendingResult = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
        every { receiverSpy.goAsync() } returns mockPendingResult
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        // Act: Call onReceive on the spy.
        receiverSpy.onReceive(context, intent)
        verify(timeout = 2000L) { mockPendingResult.finish() }

        // Assert: Open the shade and wait for the notification to appear.
        uiDevice.openNotification()
        val notificationTitle = "Restart Audio Buffering?"
        val notificationVisible =
            uiDevice.wait(Until.hasObject(By.text(notificationTitle)), NOTIFICATION_TIMEOUT)
        assertThat(notificationVisible).isTrue()
    }

    @Test
    fun bootReceiver_doesNotPostNotification_whenBufferingWasNotActive() = runBlocking {
        // Arrange: Simulate that the app was NOT buffering before the device shut down.
        val tenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)
        coEvery {
            mockSettingsRepository.getSettingsConfig()
        } returns SettingsConfig(
            wasBufferingActive = false, lastActiveTimestamp = tenDaysAgo
        )


        // Create a spy of the receiver to intercept the problematic goAsync() call.
        val receiverSpy = spyk(BootReceiver())
        val mockPendingResult = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
        every { receiverSpy.goAsync() } returns mockPendingResult
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        // Act: Call onReceive on the spy.
        receiverSpy.onReceive(context, intent)

        verify(timeout = 2000L) { mockPendingResult.finish() }

        // Assert
        uiDevice.openNotification()
        val notificationTitle = "Restart Audio Buffering?"
        // Verify that the notification does NOT appear within the timeout.
        val notificationVisible =
            uiDevice.wait(Until.hasObject(By.text(notificationTitle)), NOTIFICATION_TIMEOUT)
        assertThat(notificationVisible).isFalse()
    }

    @Test
    fun bootReceiver_doesNothing_whenIntentActionIsNotBootCompleted() = runBlocking {
        // Arrange: Simulate that buffering was active, so if the guard clause fails,
        // a notification WOULD be posted.
        coEvery {
            mockSettingsRepository.getSettingsConfig()
        } returns SettingsConfig(wasBufferingActive = true)

        val receiverSpy = spyk(BootReceiver())
        val mockPendingResult = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
        every { receiverSpy.goAsync() } returns mockPendingResult

        // Use an irrelevant but valid system action
        val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)

        // Act
        receiverSpy.onReceive(context, intent)

        // We don't need to verify finish() because goAsync() should not even be called
        // if the guard clause works correctly.

        // Assert: Verify that no notification appears.
        uiDevice.openNotification()
        val notificationTitle = "Restart Audio Buffering?"
        val notificationVisible = uiDevice.wait(
            Until.hasObject(By.text(notificationTitle)), 2000L
        ) // Shorter timeout is fine
        assertThat(notificationVisible).isFalse()
    }

    @Test
    fun bootReceiver_handlesRepositoryErrorGracefully() = runBlocking {
        // Arrange: Force the repository to throw an exception when called.
        coEvery { mockSettingsRepository.getSettingsConfig() } throws RuntimeException("Failed to read settings")

        val receiverSpy = spyk(BootReceiver())
        val mockPendingResult = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
        every { receiverSpy.goAsync() } returns mockPendingResult
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        // Act
        receiverSpy.onReceive(context, intent)

        // Assert: The most important thing is that the PendingResult is still finished,
        // preventing an "Application Not Responding" error from the system.
        verify(timeout = 1000L) { mockPendingResult.finish() }

        // Optional: Assert that no notification was shown
        uiDevice.openNotification()
        val notificationTitle = "Restart Audio Buffering?"
        val notificationVisible = uiDevice.wait(Until.hasObject(By.text(notificationTitle)), 1000L)
        assertThat(notificationVisible).isFalse()
    }
}

