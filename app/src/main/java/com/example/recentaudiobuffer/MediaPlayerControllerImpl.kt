import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import android.widget.MediaController

class MyMediaPlayerController(private val mediaPlayer: MediaPlayer) :
    MediaController.MediaPlayerControl {

    override fun start() {
        mediaPlayer.start()
    }

    override fun pause() {
        mediaPlayer.pause()
    }

    override fun getDuration(): Int {
        return mediaPlayer.duration
    }

    override fun getCurrentPosition(): Int {
        return mediaPlayer.currentPosition
    }

    override fun seekTo(pos: Int) {
        mediaPlayer.seekTo(pos)
    }

    override fun isPlaying(): Boolean {
        return mediaPlayer.isPlaying
    }

    override fun getBufferPercentage(): Int {
        // MediaPlayer doesn't have a getBufferPercentage method, so return 0
        return 0
    }

    override fun canPause(): Boolean {
        // Return true if your MediaPlayer can pause, false otherwise
        return true
    }

    override fun canSeekBackward(): Boolean {
        // Return true if your MediaPlayer can seek backward, false otherwise
        return true
    }

    override fun canSeekForward(): Boolean {
        // Return true if your MediaPlayer can seek forward, false otherwise
        return true
    }

    override fun getAudioSessionId(): Int {
        return mediaPlayer.audioSessionId
    }

}

class MyMediaController(context: Context) : MediaController(context) {
    private var allowHiding = false

    override fun hide() {
        if (allowHiding) {
            super.hide()
            Log.i("MyMediaController", "Hid MyMediaController")
        }
        Log.i("MyMediaController", "Tried to hide MyMediaController but it was not allowed")
    }

    fun setAllowHiding(allow: Boolean) {
        allowHiding = allow
    }
}
