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

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerControlView
import timber.log.Timber

@OptIn(UnstableApi::class)
class MediaPlayerManager(
    private val context: Context,
    var onPlayerReady: (uri: Uri, fileName: String) -> Unit
) {
    var player: ExoPlayer? = null
    var playerControlView: PlayerControlView? = null
    var selectedUri: Uri? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            Timber.d("onPlaybackStateChanged: playbackState = $playbackState")
            when (playbackState) {
                Player.STATE_ENDED -> {
                    Timber.d("Player STATE_ENDED")
                }

                Player.STATE_READY -> {
                    Timber.d("Player STATE_READY")
                    selectedUri?.let { uri ->
                        onPlayerReady(uri, getFileNameFromSelectedUri())
                    }
                }

                Player.STATE_BUFFERING -> {
                    Timber.d("Player STATE_BUFFERING")
                }

                Player.STATE_IDLE -> {
                    Timber.d("Player STATE_IDLE")
                }
            }
        }
    }

    fun setUpMediaPlayer(selectedMediaToPlayUri: Uri) {
        Timber.d("setUpMediaPlayer: selectedMediaToPlayUri = $selectedMediaToPlayUri")
        try {
            closeMediaPlayer()

            selectedUri = selectedMediaToPlayUri
            player = ExoPlayer.Builder(context).build().apply {
                val mediaItem = MediaItem.fromUri(selectedMediaToPlayUri)
                setMediaItem(mediaItem)
                addListener(playerListener)
                prepare()
                play()
                playerControlView?.player = this
                playerControlView?.show()
            }
        } catch (e: Exception) {
            Timber.e("Error setting up media player: ${e.message}")
        }
    }

    fun closeMediaPlayer() {
        Timber.d("closeMediaPlayer")
        player?.let {
            it.stop()
            it.release()
        }
        player = null
    }

    private fun getFileNameFromSelectedUri(): String {
        if (selectedUri == null) return "Unknown File"
        var fileName = "Unknown File"

        context.contentResolver.query(selectedUri!!, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        return fileName
    }
}
