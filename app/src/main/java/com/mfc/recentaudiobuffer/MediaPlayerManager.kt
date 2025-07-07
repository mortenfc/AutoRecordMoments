import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerControlView
import timber.log.Timber
import java.io.File

@OptIn(UnstableApi::class)
class MediaPlayerManager(
    private val context: Context,
    var onPlayerReady: (String) -> Unit
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
                    onPlayerReady(getFileNameFromSelectedUri())
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
            player = ExoPlayer.Builder(context).build().apply {
                selectedUri = selectedMediaToPlayUri
                val mediaItem = MediaItem.fromUri(selectedMediaToPlayUri)
                Timber.d("setUpMediaPlayer: mediaItem = $mediaItem")
                setMediaItem(mediaItem)
                prepare()
                play()
                addListener(playerListener)
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
        return try {
            val file = File(selectedUri?.path ?: "")
            file.name
        } catch (e: Exception) {
            "Unknown File"
        }
    }
}