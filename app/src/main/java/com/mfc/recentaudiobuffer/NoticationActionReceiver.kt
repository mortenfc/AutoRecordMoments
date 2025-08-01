/*
 * # Auto Record Moments
 * # Copyright (C) 2025 Morten Fjord Christensen
 * #
 * # This program is free software: you can redistribute it and/or modify
 * # it under the terms of the GNU Affero General Public License as published by
 * # the Free Software Foundation, either version 3 of the License, or
 * # (at your option) any later version.
 * #
 * # This program is distributed in the hope that it will be useful,
 * # but WITHOUT ANY WARRANTY; without even the implied warranty of
 * # MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * # GNU Affero General Public License for more details.
 * #
 * # You should have received a copy of the GNU Affero General Public License
 * # along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.mfc.recentaudiobuffer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import timber.log.Timber

class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING"
        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"
        const val ACTION_SAVE_RECORDING = "ACTION_SAVE_RECORDING"
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_RECORDING -> {
                Timber.d("Received ACTION_STOP_RECORDING")
                startService(context, MyBufferService.ACTION_STOP_RECORDING_SERVICE)
            }

            ACTION_START_RECORDING -> {
                Timber.d("Received ACTION_START_RECORDING")
                startService(context, MyBufferService.ACTION_START_RECORDING_SERVICE)
            }

            ACTION_SAVE_RECORDING -> {
                Timber.d("Received ACTION_SAVE_RECORDING")
                val grantedUri = FileSavingUtils.getCachedGrantedUri(context)

                if (FileSavingUtils.isUriValidAndAccessible(context, grantedUri)) {
                    Timber.d("Valid URI permission found. Proceeding with quick save.")
                    // The service needs to be running to save, so we just send an intent.
                    // The service will then handle the entire save process.
                    startService(context, MyBufferService.ACTION_SAVE_RECORDING_SERVICE)
                } else {
                    // Permission does NOT exist, launch MainActivity to request it.
                    Timber.d("No valid URI permission. Launching MainActivity to request it.")
                    val mainActivityIntent = Intent(context, MainActivity::class.java).apply {
                        action = MainActivity.ACTION_REQUEST_DIRECTORY_PERMISSION
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    context.startActivity(mainActivityIntent)
                }
            }
        }
    }

    private fun startService(context: Context, action: String) {
        val serviceIntent = Intent(context, MyBufferService::class.java).apply {
            this.action = action
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
