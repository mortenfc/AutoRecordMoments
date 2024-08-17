import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import android.widget.MediaController as WidgetMediaController
import android.util.AttributeSet
import android.widget.TextView
import java.util.Locale
import android.widget.*
import com.mfc.recentaudiobuffer.R

class MyMediaPlayerController(private val mediaPlayer: MediaPlayer) :
    WidgetMediaController.MediaPlayerControl {

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

class MyMediaController : WidgetMediaController {

    private var allowHiding = false
    private var durationTextView: TextView? = null

    // Constructor for use in code
    constructor(context: Context) : super(context)

    // Constructors for use in layout XML
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    override fun onFinishInflate() {
        super.onFinishInflate()
        durationTextView = findViewById(R.id.duration_textview)
    }

    public fun getUpdateTime(currentTime: Int, duration: Int) {
        val formattedDuration = formatDuration(duration)
        val formattedCurrentTime = formatDuration(currentTime)
        val timeText = context.getString(R.string.time_display, formattedCurrentTime, formattedDuration)
        Log.d("MyMediaController", "Setting duration text: $timeText")
        durationTextView?.text = timeText
        durationTextView?.invalidate()
    }

    private fun formatDuration(durationMs: Int): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val milliseconds = durationMs % 1000

        return String.format(Locale.getDefault(), "%02d:%02d:%03d", minutes, seconds, milliseconds)
    }

    override fun hide() {
        if (allowHiding) {
            super.hide()
            Log.i("MyWidgetMediaController", "Hid MyWidgetMediaController")
        } else {
            Log.i("MyWidgetMediaController", "Tried to hide MyWidgetMediaController but it was not allowed")
        }
    }

    fun setAllowHiding(allow: Boolean) {
        allowHiding = allow
    }
}
