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

        // Use a ContentResolver query to get the file's display name
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