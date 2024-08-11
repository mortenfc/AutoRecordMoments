package com.mfc.recentaudiobuffer

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import java.io.IOException

class MediaPlayerViewModel : ViewModel() {
    var mediaPlayer: MediaPlayer? = null
    private var onMediaPlayerReadyCallback: (() -> Unit)? = null

    fun createMediaPlayer(
        context: Context, audioFileUri: Uri,
        audioAttributes: AudioAttributes,
        onReady: () -> Unit
    ) {
        onMediaPlayerReadyCallback = onReady
        releaseMediaPlayer()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(audioAttributes)
            try {
                setDataSource(context, audioFileUri)
                prepareAsync()
                setOnPreparedListener {
                    onMediaPlayerReadyCallback?.invoke()
                    start()
                }
                setOnCompletionListener {}
                setOnErrorListener { _, what, extra ->
                    Log.e("MediaPlayer", "Error occurred: $what, $extra")
                    true
                }
            } catch (e: IOException) {
                Log.e("MediaPlayer", "Error occurred: $e")
            }
        }
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onCleared() {
        super.onCleared()
        releaseMediaPlayer()
    }
}