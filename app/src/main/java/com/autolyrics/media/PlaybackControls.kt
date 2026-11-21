package com.autolyrics.media

import android.media.session.MediaController

/** Play/pause/skip/seek for whichever player the lyrics follow: a phone app or Spotify's API. */
interface PlaybackControls {
    fun play()
    fun pause()
    fun skipToNext()
    fun skipToPrevious()
    fun stop()
    fun seekTo(positionMs: Long)

    companion object {
        fun of(controls: MediaController.TransportControls) = object : PlaybackControls {
            override fun play() = controls.play()
            override fun pause() = controls.pause()
            override fun skipToNext() = controls.skipToNext()
            override fun skipToPrevious() = controls.skipToPrevious()
            override fun stop() = controls.stop()
            override fun seekTo(positionMs: Long) = controls.seekTo(positionMs)
        }
    }
}
