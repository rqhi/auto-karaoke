package com.autolyrics.testplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver

/**
 * Pretends to play [SONGS] through a MediaSession, without any audio.
 *
 *   adb shell am start-foreground-service -n com.autolyrics.testplayer/.PlayerService -a play --ei song 0
 *   adb shell am start-foreground-service -n com.autolyrics.testplayer/.PlayerService -a seek --el pos 60000
 *   actions: play (optional song index), pause, next, previous, seek (pos in ms)
 */
class PlayerService : Service() {

    private lateinit var session: MediaSessionCompat
    private var songIndex = 0
    private var playing = false
    private var positionMs = 0L
    private var positionAt = SystemClock.elapsedRealtime()

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))

        session = MediaSessionCompat(this, "TestPlayer").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onSkipToNext() = load(songIndex + 1)
                override fun onSkipToPrevious() =
                    if (currentPosition() > 3_000) seek(0) else load(songIndex - 1)
                override fun onSeekTo(pos: Long) = seek(pos)
            })
            isActive = true
        }
        load(0, autoplay = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(session, intent)
        when (intent?.action) {
            "play" -> if (intent.hasExtra("song")) load(intent.getIntExtra("song", 0)) else play()
            "pause" -> pause()
            "next" -> load(songIndex + 1)
            "previous" -> load(songIndex - 1)
            "seek" -> seek(intent.getLongExtra("pos", 0L))
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        session.release()
        super.onDestroy()
    }

    private val song get() = SONGS[songIndex]

    private fun currentPosition(): Long {
        val elapsed = if (playing) SystemClock.elapsedRealtime() - positionAt else 0L
        return (positionMs + elapsed).coerceAtMost(song.durationMs)
    }

    fun load(index: Int, autoplay: Boolean = true) {
        songIndex = Math.floorMod(index, SONGS.size)
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.durationMs)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover(songIndex))
                .build()
        )
        positionMs = 0L
        positionAt = SystemClock.elapsedRealtime()
        if (autoplay) play() else publish()
    }

    /** A plain cover per song, so album art shows up like with a real player. */
    private fun cover(index: Int): Bitmap {
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.HSVToColor(floatArrayOf(index * 60f, 0.6f, 0.75f)))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 64f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(SONGS[index].album.take(14), 256f, 280f, paint)
        return bmp
    }

    private fun play() {
        positionMs = currentPosition()
        positionAt = SystemClock.elapsedRealtime()
        playing = true
        publish()
    }

    private fun pause() {
        positionMs = currentPosition()
        playing = false
        publish()
    }

    private fun seek(pos: Long) {
        positionMs = pos.coerceIn(0L, song.durationMs)
        positionAt = SystemClock.elapsedRealtime()
        publish()
    }

    private fun publish() {
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    positionMs,
                    if (playing) 1f else 0f,
                    positionAt
                )
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .build()
        )
        ServiceCompat.startForeground(
            this, 1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    private fun buildNotification(): Notification {
        fun action(icon: Int, title: String, action: Long) = NotificationCompat.Action(
            icon, title, MediaButtonReceiver.buildMediaButtonPendingIntent(this, action)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, PlayerActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setContentIntent(open)
            .setOngoing(playing)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(
                if (playing) action(android.R.drawable.ic_media_pause, "Pause", PlaybackStateCompat.ACTION_PAUSE)
                else action(android.R.drawable.ic_media_play, "Play", PlaybackStateCompat.ACTION_PLAY)
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    companion object {
        private const val CHANNEL = "playback"
    }
}
