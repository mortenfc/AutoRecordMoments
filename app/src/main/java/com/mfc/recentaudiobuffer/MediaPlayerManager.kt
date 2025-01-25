import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerControlView
import java.io.File

@OptIn(UnstableApi::class)
class MediaPlayerManager(
    private val context: Context,
    var onPlayerReady: (String) -> Unit
) {
    private val logTag = "MediaPlayerManager"
    var player: ExoPlayer? = null
    var playerControlView: PlayerControlView? = null
    var selectedUri: Uri? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(logTag, "onPlaybackStateChanged: playbackState = $playbackState")
            when (playbackState) {
                Player.STATE_ENDED -> {
                    Log.d(logTag, "Player STATE_ENDED")
                }

                Player.STATE_READY -> {
                    Log.d(logTag, "Player STATE_READY")
                    onPlayerReady(getFileNameFromSelectedUri())
                }

                Player.STATE_BUFFERING -> {
                    Log.d(logTag, "Player STATE_BUFFERING")
                }

                Player.STATE_IDLE -> {
                    Log.d(logTag, "Player STATE_IDLE")
                }
            }
        }
    }

    fun setUpMediaPlayer(selectedMediaToPlayUri: Uri) {
        Log.d(logTag, "setUpMediaPlayer: selectedMediaToPlayUri = $selectedMediaToPlayUri")
        try {
            player = ExoPlayer.Builder(context).build().apply {
                selectedUri = selectedMediaToPlayUri
                val mediaItem = MediaItem.fromUri(selectedMediaToPlayUri)
                Log.d(logTag, "setUpMediaPlayer: mediaItem = $mediaItem")
                setMediaItem(mediaItem)
                prepare()
                play()
                addListener(playerListener)
                playerControlView?.player = this
                playerControlView?.show()
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error setting up media player: ${e.message}")
        }
    }

    fun closeMediaPlayer() {
        Log.d(logTag, "closeMediaPlayer")
        player?.let {
            it.stop()
            it.release()
        }
        player = null
    }

    private fun getFileNameFromSelectedUri(): String {
        return try {
            val file = File(selectedUri?.path ?: "")
            file.name
        } catch (e: Exception) {
            "Unknown File"
        }
    }
}