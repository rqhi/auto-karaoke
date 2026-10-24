package com.autolyrics.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.autolyrics.lyrics.Countdown
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class LyricsBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaTracker: MediaTracker
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var lastNotifyTime = 0L
    private var pendingNotify = false
    private var displayedWindowStart = -1
    private var displayedWindowEnd = -1
    private var displayedCurrentIdx = -1
    private var displayedDots = 0
    private var displayedStatus: LyricsStatus? = null
    private var displayedTrackTitle: String? = null
    private var lastSubtitleText: String? = null
    private var lastAlbumArt: Bitmap? = null

    private var aaKaraokeEnabled = true
    private var aaOffsetMs = 0L
    private var aaHideControls = false

    private var lastKaraokeLineIdx = -1
    private var lastKaraokeWordIdx = -1
    private var lastKaraokeText: String? = null

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                "aa_karaoke_enabled" -> {
                    aaKaraokeEnabled = sp.getBoolean(key, true)
                    forceRefresh()
                }
                "aa_offset_ms" -> {
                    aaOffsetMs = sp.getLong(key, 0L)
                    forceRefresh()
                }
                "aa_hide_controls" -> {
                    aaHideControls = sp.getBoolean(key, false)
                    val state = mediaTracker.state.value
                    mediaSession.setMetadata(buildMetadata(state, getCardText(state)))
                    mediaSession.setPlaybackState(buildPlaybackState(state))
                }
            }
        }

    companion object {
        private const val TAG = "AutoLyricsAA"
        /** Debug builds only: see DEVELOPMENT.md, "Probing the car screen". */
        private const val DEBUG_METADATA_ACTION = "com.autolyrics.DEBUG_METADATA"
        private const val ROOT_ID = "root"
        private const val SYNC_MENU_ID = "sync_menu"
        private const val SYNC_MINUS_ID = "sync_minus"
        private const val SYNC_PLUS_ID = "sync_plus"
        private const val SYNC_STEP_MS = 50L
        private const val SONG_NEXT_ID = "song_next"
        private const val SONG_PREVIOUS_ID = "song_previous"
        private const val SONG_RESTART_ID = "song_restart"
        private const val MENU_TITLE = "⟳ Sync & songs"
        private const val MENU_SUBTITLE = "Timing, next or previous song"
        private const val PREVIOUS_SONG_DELAY_MS = 350L
        /** About two lines of the media card's big title text. */
        private const val CARD_MAX_CHARS = 34
        private const val LAST_LINE_MS = 6_000L
        private const val WINDOW_SIZE = 3
        private const val PLAIN_WINDOW_SIZE = 4
        private const val PAD_WIDTH = 60
        private const val NOTIFY_THROTTLE_MS = 500L
        private const val BROWSE_KARAOKE_WINDOW_MS = 600L
        private const val SUBTITLE_KARAOKE_WINDOW_MS = 300L
        private const val SESSION_REFRESH_MS = 1500L
        private const val PLAIN_LOOP_DELAY_MS = 2000L
    }

    override fun onCreate() {
        super.onCreate()
        mediaTracker = MediaTracker.getInstance(this)

        val prefs = getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
        aaKaraokeEnabled = prefs.getBoolean("aa_karaoke_enabled", true)
        aaOffsetMs = prefs.getLong("aa_offset_ms", 0L)
        aaHideControls = prefs.getBoolean("aa_hide_controls", false)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        mediaSession = MediaSessionCompat(this, "AutoLyrics").apply {
            setCallback(SessionCallback())
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val filter = IntentFilter(DEBUG_METADATA_ACTION)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(debugReceiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(debugReceiver, filter)
            }
        }

        scope.launch {
            mediaTracker.state.collectLatest { state ->
                updateMediaSession(state)
                throttledNotifyChildren(state)
            }
        }

        scope.launch {
            while (isActive) {
                delay(200)
                updateSubtitleKaraoke()
            }
        }

        scope.launch {
            while (isActive) {
                delay(PLAIN_LOOP_DELAY_MS)
                val state = mediaTracker.state.value
                if (state.isPlaying && state.status == LyricsStatus.PLAIN_ONLY && state.lines.isNotEmpty()) {
                    throttledNotifyChildren(state)
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(SESSION_REFRESH_MS)
                val state = mediaTracker.state.value
                if (state.isPlaying && state.track != null && !debugOverride) {
                    mediaSession.isActive = true
                    mediaSession.setPlaybackState(buildPlaybackState(state))
                }
            }
        }
    }

    override fun onDestroy() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            unregisterReceiver(debugReceiver)
        }
        getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        scope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val state = mediaTracker.state.value
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()

        if (parentId == SYNC_MENU_ID) {
            buildSyncMenu(state, items)
            result.sendResult(items)
            return
        }

        if (parentId == SYNC_MINUS_ID || parentId == SYNC_PLUS_ID ||
            parentId == SONG_NEXT_ID || parentId == SONG_PREVIOUS_ID || parentId == SONG_RESTART_ID
        ) {
            result.sendResult(items)
            return
        }

        when (state.status) {
            LyricsStatus.NO_MEDIA -> {
                items.add(buildTextItem("no_media", "Play a song to see lyrics"))
            }
            LyricsStatus.LOADING -> {
                addTrackHeader(state, items)
                items.add(buildTextItem("loading", "Finding lyrics…"))
                items.add(buildBrowsableItem(SYNC_MENU_ID, MENU_TITLE, MENU_SUBTITLE))
            }
            LyricsStatus.NOT_FOUND -> {
                addTrackHeader(state, items)
                items.add(buildTextItem("not_found", "No lyrics found for this song"))
                items.add(buildBrowsableItem(SYNC_MENU_ID, MENU_TITLE, MENU_SUBTITLE))
            }
            LyricsStatus.ERROR -> {
                addTrackHeader(state, items)
                items.add(buildTextItem("error", "Couldn't load lyrics"))
                items.add(buildBrowsableItem(SYNC_MENU_ID, MENU_TITLE, MENU_SUBTITLE))
            }
            LyricsStatus.FOUND -> {
                addTrackHeader(state, items)
                buildWindowedLyrics(state, items)
                items.add(buildBrowsableItem(SYNC_MENU_ID, MENU_TITLE, MENU_SUBTITLE))
            }
            LyricsStatus.PLAIN_ONLY -> {
                addTrackHeader(state, items)
                if (state.lines.isEmpty()) {
                    items.add(buildTextItem("empty", "♪"))
                } else {
                    val durationMs = state.track?.durationMs ?: 0
                    val posMs = try {
                        mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
                    } catch (_: Exception) { 0L }

                    val estimatedIdx = if (durationMs > 0) {
                        ((posMs.toFloat() / durationMs) * state.lines.size).toInt()
                            .coerceIn(0, state.lines.size - 1)
                    } else { 0 }

                    val half = PLAIN_WINDOW_SIZE / 2
                    val winStart = maxOf(0, estimatedIdx - half)
                    val winEnd = minOf(state.lines.size, winStart + PLAIN_WINDOW_SIZE)
                    val adjStart = maxOf(0, winEnd - PLAIN_WINDOW_SIZE)

                    for (i in adjStart until winEnd) {
                        val text = state.lines[i].text.ifBlank { "♪" }
                        items.add(buildTextItem("line_$i", "    $text", pad = true, subtitle = secondLine(state, i)))
                    }
                }
                items.add(buildBrowsableItem(SYNC_MENU_ID, MENU_TITLE, MENU_SUBTITLE))
            }
        }

        result.sendResult(items)
    }

    private fun buildSyncMenu(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val sign = if (aaOffsetMs >= 0) "+" else ""
        items.add(buildTextItem("sync_offset", "AA Offset: ${sign}${aaOffsetMs}ms"))

        if (state.lines.isNotEmpty()) {
            val posMs = getAaPositionMs()
            val idx = if (state.status == LyricsStatus.FOUND) {
                findLineIndex(state.lines, posMs).coerceAtLeast(0)
            } else {
                val durationMs = state.track?.durationMs ?: 0
                if (durationMs > 0) {
                    ((posMs.toFloat() / durationMs) * state.lines.size).toInt()
                        .coerceIn(0, state.lines.size - 1)
                } else 0
            }

            val curLine = state.lines.getOrNull(idx)
            val curText = if (curLine != null && aaKaraokeEnabled && curLine.words.isNotEmpty()) {
                buildKaraokeText(curLine, idx, posMs, BROWSE_KARAOKE_WINDOW_MS)
            } else {
                curLine?.text ?: "♪"
            }
            val curTrans = state.translatedLines?.getOrNull(idx)?.takeIf { it.isNotBlank() }
            items.add(buildTextItem("sync_cur", "▶  $curText", pad = true, subtitle = curTrans))
            val nextTrans = state.translatedLines?.getOrNull(idx + 1)?.takeIf { it.isNotBlank() }
            items.add(buildTextItem("sync_next", "    ${state.lines.getOrNull(idx + 1)?.text ?: ""}", pad = true, subtitle = nextTrans))
        }

        items.add(buildTextItem(SYNC_MINUS_ID, "⏪  − 50ms"))
        items.add(buildTextItem(SYNC_PLUS_ID, "⏩  + 50ms"))

        if (state.track != null) {
            items.add(buildTextItem(SONG_NEXT_ID, "⏭  Next song"))
            items.add(buildTextItem(SONG_PREVIOUS_ID, "⏮  Previous song"))
            items.add(buildTextItem(SONG_RESTART_ID, "↺  Start this song again"))
        }
    }

    private fun addTrackHeader(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val track = state.track ?: return

        val subtitle = buildString {
            append(track.artist)
            val posMs = try {
                mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
            } catch (_: Exception) { -1L }
            if (posMs >= 0 && track.durationMs > 0) {
                append("  ·  ")
                append(formatTime(posMs))
                append(" / ")
                append(formatTime(track.durationMs))
            }
            val typeLabel = lyricsTypeLabel(state)
            if (typeLabel.isNotBlank()) {
                append("  ·  ")
                append(typeLabel)
            }
        }

        val descBuilder = MediaDescriptionCompat.Builder()
            .setMediaId("header")
            .setTitle(track.title)
            .setSubtitle(subtitle)

        val headerArt = state.albumArt ?: lastAlbumArt
        headerArt?.let { art ->
            descBuilder.setIconBitmap(art)
        }

        items.add(
            MediaBrowserCompat.MediaItem(
                descBuilder.build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        )
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    private fun buildWindowedLyrics(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val lines = state.lines
        if (lines.isEmpty()) {
            items.add(buildTextItem("empty", "♪"))
            return
        }

        val posMs = getAaPositionMs()
        val aaCurrentIdx = findLineIndex(lines, posMs).coerceAtLeast(0)
        val half = WINDOW_SIZE / 2

        val windowStart = maxOf(0, aaCurrentIdx - half)
        val windowEnd = minOf(lines.size, windowStart + WINDOW_SIZE)
        val adjustedStart = maxOf(0, windowEnd - WINDOW_SIZE)

        val countdown = Countdown.compute(lines, posMs)

        for (i in adjustedStart until windowEnd) {
            val line = lines[i]
            if (countdown.dots > 0 && i == countdown.nextIndex) {
                items.add(buildTextItem("countdown", "    ${countdownDots(countdown.dots)}"))
            }
            val isCurrent = i == aaCurrentIdx && countdown.dots == 0
            val prefix = if (isCurrent) "▶  " else "    "

            val text = if (isCurrent && aaKaraokeEnabled && line.words.isNotEmpty()) {
                buildKaraokeText(line, i, posMs, BROWSE_KARAOKE_WINDOW_MS)
            } else {
                line.text.ifBlank { "♪" }
            }

            items.add(buildTextItem("line_$i", "$prefix$text", pad = true, subtitle = secondLine(state, i)))
        }
    }

    /** Second line under a lyric: pronunciation for Korean/Japanese, otherwise the translation. */
    private fun secondLine(state: LyricsState, index: Int): String? {
        val roman = state.romanizedLines?.getOrNull(index)?.takeIf { it.isNotBlank() }
        return roman ?: state.translatedLines?.getOrNull(index)?.takeIf { it.isNotBlank() }
    }

    private fun countdownDots(dots: Int): String =
        (1..Countdown.DOTS).joinToString("  ") { if (it <= dots) "●" else "○" }

    private fun buildTextItem(id: String, text: String, pad: Boolean = false, subtitle: String? = null): MediaBrowserCompat.MediaItem {
        val title = if (pad) text.padEnd(PAD_WIDTH) else text
        val builder = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
        if (!subtitle.isNullOrBlank()) builder.setSubtitle(subtitle)
        return MediaBrowserCompat.MediaItem(
            builder.build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    private fun buildBrowsableItem(id: String, title: String, subtitle: String): MediaBrowserCompat.MediaItem {
        return MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title)
                .setSubtitle(subtitle)
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
    }

    private fun lyricsTypeLabel(state: LyricsState): String {
        val lang = state.detectedLanguage?.uppercase()
        val suffix = if (lang != null) " · $lang" else ""
        return when (state.status) {
            LyricsStatus.FOUND -> {
                val hasKaraoke = state.lines.any { it.words.isNotEmpty() }
                if (hasKaraoke && aaKaraokeEnabled) "⟳ Karaoke$suffix" else "⟳ Synced$suffix"
            }
            LyricsStatus.PLAIN_ONLY -> "⟳ Not synced$suffix"
            else -> "⟳ Sync"
        }
    }

    // --- Karaoke helpers ---

    private fun getAaPositionMs(): Long {
        return try {
            mediaTracker.getCurrentPositionMs() + aaOffsetMs
        } catch (_: Exception) {
            0L
        }
    }

    private fun findLineIndex(lines: List<LyricLine>, posMs: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) idx = i
            else break
        }
        return idx
    }

    private fun buildKaraokeText(line: LyricLine, lineIdx: Int, posMs: Long, windowMs: Long): String {
        val words = line.words
        if (words.isEmpty()) return line.text

        var currentIdx = -1
        for (i in words.indices) {
            if (words[i].timeMs <= posMs) currentIdx = i
            else break
        }
        if (currentIdx < 0) return line.text

        val sameLine = lineIdx == lastKaraokeLineIdx
        if (sameLine && currentIdx <= lastKaraokeWordIdx && lastKaraokeText != null) {
            return lastKaraokeText!!
        }

        var endIdx = currentIdx
        for (i in (currentIdx + 1) until words.size) {
            if (words[i].timeMs <= posMs + windowMs) endIdx = i
            else break
        }

        if (sameLine) {
            endIdx = maxOf(endIdx, lastKaraokeWordIdx)
        }

        lastKaraokeLineIdx = lineIdx
        lastKaraokeWordIdx = currentIdx
        val sb = StringBuilder()
        for (i in words.indices) {
            if (i == currentIdx) sb.append("【")
            sb.append(words[i].text)
            if (i == endIdx) sb.append("】")
            if (i < words.size - 1) sb.append(" ")
        }
        lastKaraokeText = sb.toString()
        return lastKaraokeText!!
    }

    private fun resetKaraokeState() {
        lastKaraokeLineIdx = -1
        lastKaraokeWordIdx = -1
        lastKaraokeText = null
    }

    // --- Media card (the small player next to Maps) ---

    /** Big bold line and small line on the media card. */
    private data class CardText(val title: String, val subtitle: String)

    private fun songLabel(state: LyricsState): String {
        val track = state.track ?: return ""
        return if (track.artist.isNotBlank()) "${track.title} — ${track.artist}" else track.title
    }

    /** What to sing for a line: the pronunciation when there is one, else the lyric. */
    private fun singText(state: LyricsState, index: Int): String {
        val line = state.lines.getOrNull(index) ?: return ""
        val roman = state.romanizedLines?.getOrNull(index)?.takeIf { it.isNotBlank() }
        return (roman ?: line.text).ifBlank { "♪" }
    }

    private fun nextVocalIndex(lines: List<LyricLine>, after: Int): Int {
        for (i in (after + 1) until lines.size) {
            if (Countdown.isVocal(lines[i])) return i
        }
        return -1
    }

    /**
     * The card shows the piece being sung as its big title and the piece that comes
     * right after it underneath, so lyrics stay readable next to Maps. Long lines are
     * cut into pieces that fit the card and move along with the singing; the small
     * line shows the rest of the current line before moving on to the next line.
     */
    private fun getCardText(state: LyricsState): CardText {
        val song = songLabel(state)
        val posMs = getAaPositionMs()
        val lines = state.lines

        if (state.status == LyricsStatus.FOUND && lines.isNotEmpty()) {
            val countdown = Countdown.compute(lines, posMs)
            if (countdown.dots > 0) {
                return CardText(
                    countdownDots(countdown.dots),
                    firstChunk(singText(state, countdown.nextIndex))
                )
            }
            val idx = findLineIndex(lines, posMs)
            if (idx < 0) {
                val first = nextVocalIndex(lines, -1)
                return CardText(song, if (first >= 0) firstChunk(singText(state, first)) else "")
            }
            val line = lines[idx]
            val endMs = lines.getOrNull(idx + 1)?.timeMs ?: (line.timeMs + LAST_LINE_MS)
            val text = singText(state, idx)
            val usesOriginal = state.romanizedLines?.getOrNull(idx).isNullOrBlank()
            val pos = chunkPosition(text, line.words.takeIf { usesOriginal }, line.timeMs, endMs, posMs)
            val upNext = pos.chunks.getOrNull(pos.index + 1)
                ?: nextVocalIndex(lines, idx).takeIf { it >= 0 }?.let { firstChunk(singText(state, it)) }
                ?: song
            return CardText(pos.chunks[pos.index], upNext)
        }

        if (state.status == LyricsStatus.PLAIN_ONLY && lines.isNotEmpty()) {
            val durationMs = state.track?.durationMs ?: 0
            val idx = if (durationMs > 0) {
                ((posMs.toFloat() / durationMs) * lines.size).toInt().coerceIn(0, lines.size - 1)
            } else 0
            // Untimed lyrics: assume each line gets an equal share of the song.
            val perLineMs = if (durationMs > 0) durationMs / lines.size else LAST_LINE_MS
            val startMs = idx * perLineMs
            val pos = chunkPosition(singText(state, idx), null, startMs, startMs + perLineMs, posMs)
            val upNext = pos.chunks.getOrNull(pos.index + 1)
                ?: if (idx + 1 < lines.size) firstChunk(singText(state, idx + 1)) else song
            return CardText(pos.chunks[pos.index], upNext)
        }

        val status = when (state.status) {
            LyricsStatus.LOADING -> "Finding lyrics…"
            LyricsStatus.NOT_FOUND -> "No lyrics found"
            LyricsStatus.ERROR -> "Couldn't load lyrics"
            else -> ""
        }
        return CardText(song, status)
    }

    /** A line cut into card-sized pieces, and which piece is being sung now. */
    private data class ChunkPosition(val chunks: List<String>, val index: Int)

    /**
     * Finds the piece of a long line being sung right now. [words] are the line's word
     * timings when they match [text]; without them the pieces are spread over the line's time.
     */
    private fun chunkPosition(
        text: String,
        words: List<LyricWord>?,
        startMs: Long,
        endMs: Long,
        posMs: Long
    ): ChunkPosition {
        val chunks = splitIntoChunks(text, CARD_MAX_CHARS).ifEmpty { listOf(text) }
        if (chunks.size <= 1) return ChunkPosition(chunks, 0)

        // Word timings say exactly where the singer is.
        if (!words.isNullOrEmpty()) {
            var wordIdx = -1
            for (i in words.indices) {
                if (words[i].timeMs <= posMs) wordIdx = i else break
            }
            if (wordIdx >= 0) {
                var charPos = 0
                for (i in 0 until wordIdx) charPos += words[i].text.length + 1
                var acc = 0
                chunks.forEachIndexed { i, chunk ->
                    acc += chunk.length + 1
                    if (charPos < acc) return ChunkPosition(chunks, i)
                }
                return ChunkPosition(chunks, chunks.lastIndex)
            }
        }

        // Otherwise spread the pieces over the line's time (singers finish a bit early).
        val span = ((endMs - startMs) * 0.85).toLong()
        if (span <= 0) return ChunkPosition(chunks, 0)
        val frac = ((posMs - startMs).toFloat() / span).coerceIn(0f, 0.999f)
        return ChunkPosition(chunks, (frac * chunks.size).toInt().coerceIn(0, chunks.lastIndex))
    }

    private fun firstChunk(text: String): String =
        splitIntoChunks(text, CARD_MAX_CHARS).firstOrNull() ?: text

    /** Splits text into roughly even pieces of at most [max] characters, on word boundaries. */
    private fun splitIntoChunks(text: String, max: Int): List<String> {
        val clean = text.trim()
        if (clean.length <= max) return listOf(clean)
        val pieces = (clean.length + max - 1) / max
        val target = minOf(max, clean.length / pieces + 6)

        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (raw in clean.split(' ')) {
            if (raw.isEmpty()) continue
            var word = raw
            // Text without spaces (e.g. Japanese) is cut by length.
            while (word.length > max) {
                if (sb.isNotEmpty()) {
                    out += sb.toString()
                    sb.clear()
                }
                out += word.substring(0, max)
                word = word.substring(max)
            }
            if (sb.isNotEmpty() && sb.length + 1 + word.length > target) {
                out += sb.toString()
                sb.clear()
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(word)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return if (out.isEmpty()) listOf(clean) else out
    }

    // --- Debug: push raw metadata to the car screen with adb ---

    /** While set, the lyrics updates leave the media card alone. */
    private var debugOverride = false

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra("clear", false)) {
                debugOverride = false
                lastSubtitleText = null
                updateMediaSession(mediaTracker.state.value)
                Log.d(TAG, "debug metadata cleared")
                return
            }
            debugOverride = true
            val b = MediaMetadataCompat.Builder()
            val keys = mapOf(
                "title" to MediaMetadataCompat.METADATA_KEY_TITLE,
                "artist" to MediaMetadataCompat.METADATA_KEY_ARTIST,
                "album" to MediaMetadataCompat.METADATA_KEY_ALBUM,
                "album_artist" to MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST,
                "display_title" to MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE,
                "display_subtitle" to MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                "display_description" to MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION,
                "writer" to MediaMetadataCompat.METADATA_KEY_WRITER,
                "composer" to MediaMetadataCompat.METADATA_KEY_COMPOSER
            )
            for ((extra, key) in keys) {
                // A literal "\n" in the adb string becomes a real line break.
                intent.getStringExtra(extra)?.let { b.putString(key, it.replace("\\n", "\n")) }
            }
            if (intent.getBooleanExtra("art", false)) {
                (mediaTracker.state.value.albumArt ?: lastAlbumArt)?.let {
                    b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                }
            }
            if (intent.hasExtra("duration")) {
                b.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, intent.getLongExtra("duration", 0L))
            }
            mediaSession.setMetadata(b.build())
            intent.getStringExtra("queue_title")?.let { mediaSession.setQueueTitle(it) }
            if (intent.hasExtra("actions")) {
                mediaSession.setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
                        .setActions(intent.getLongExtra("actions", 0L))
                        .build()
                )
            }
            Log.d(TAG, "debug metadata set: ${intent.extras?.keySet()}")
        }
    }

    // --- MediaSession management ---

    /**
     * Metadata for the car's player and media card.
     */
    private fun buildMetadata(state: LyricsState, card: CardText): MediaMetadataCompat {
        val metaBuilder = MediaMetadataCompat.Builder()

        state.track?.let { track ->
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
            // Without a duration the car leaves out the progress bar.
            if (track.durationMs > 0 && !aaHideControls) {
                metaBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs)
            }
        }
        val art = state.albumArt ?: lastAlbumArt
        if (art != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            lastAlbumArt = art
        }

        if (card.title.isNotBlank()) {
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, card.title)
        }
        if (card.subtitle.isNotBlank()) {
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, card.subtitle)
        }
        return metaBuilder.build()
    }

    private fun updateMediaSession(state: LyricsState) {
        val trackChanged = state.track?.title != displayedTrackTitle
        if (trackChanged) {
            mediaSession.isActive = true
            lastSubtitleText = null
            lastAlbumArt = null
            resetKaraokeState()
        }
        if (debugOverride) return

        val card = getCardText(state)
        lastSubtitleText = "${card.title}\n${card.subtitle}"

        mediaSession.setMetadata(buildMetadata(state, card))
        mediaSession.setPlaybackState(buildPlaybackState(state))
    }

    private fun updateSubtitleKaraoke() {
        val state = mediaTracker.state.value
        if (!state.isPlaying || debugOverride) return
        if (state.status != LyricsStatus.FOUND && state.status != LyricsStatus.PLAIN_ONLY) return

        val card = getCardText(state)
        val key = "${card.title}\n${card.subtitle}"
        if (key == lastSubtitleText) return
        lastSubtitleText = key

        mediaSession.setMetadata(buildMetadata(state, card))
        mediaSession.setPlaybackState(buildPlaybackState(state))
    }

    private fun buildPlaybackState(state: LyricsState): PlaybackStateCompat {
        val pbState = if (state.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else if (state.track != null) {
            PlaybackStateCompat.STATE_PAUSED
        } else {
            PlaybackStateCompat.STATE_NONE
        }

        // An unknown position also takes the progress bar off the screen.
        val position = if (aaHideControls) {
            PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
        } else try {
            mediaTracker.getCurrentPositionMs()
        } catch (_: Exception) {
            0L
        }

        // Taps on lyric lines and the sync menu need PLAY_FROM_MEDIA_ID; the rest is
        // the transport bar, which "Hide playback controls" leaves out.
        val actions = if (aaHideControls) {
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
        } else {
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
        }

        return PlaybackStateCompat.Builder()
            .setState(pbState, position, if (state.isPlaying) 1.0f else 0f)
            .setActions(actions)
            .build()
    }

    // --- Throttled browse tree updates ---

    private data class WindowInfo(val start: Int, val end: Int, val currentIdx: Int)

    private fun computeWindow(state: LyricsState): WindowInfo {
        val lines = state.lines
        if (lines.isEmpty()) return WindowInfo(-1, -1, -1)

        val isPlain = state.status == LyricsStatus.PLAIN_ONLY
        val currentIdx = if (isPlain) {
            val durationMs = state.track?.durationMs ?: 0
            val posMs = try {
                mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
            } catch (_: Exception) { 0L }
            if (durationMs > 0) {
                ((posMs.toFloat() / durationMs) * lines.size).toInt()
                    .coerceIn(0, lines.size - 1)
            } else 0
        } else {
            val posMs = getAaPositionMs()
            findLineIndex(lines, posMs).coerceAtLeast(0)
        }

        val winSize = if (isPlain) PLAIN_WINDOW_SIZE else WINDOW_SIZE
        val half = winSize / 2
        val windowStart = maxOf(0, currentIdx - half)
        val windowEnd = minOf(lines.size, windowStart + winSize)
        val adjustedStart = maxOf(0, windowEnd - winSize)
        return WindowInfo(adjustedStart, windowEnd, currentIdx)
    }

    private fun forceRefresh() {
        displayedWindowStart = -1
        displayedWindowEnd = -1
        displayedCurrentIdx = -1
        lastNotifyTime = 0L
        resetKaraokeState()
        notifyChildrenChanged(ROOT_ID)
        notifyChildrenChanged(SYNC_MENU_ID)
    }

    private fun throttledNotifyChildren(state: LyricsState) {
        val statusChanged = state.status != displayedStatus
        val trackChanged = state.track?.title != displayedTrackTitle

        if (trackChanged) {
            displayedWindowStart = -1
            displayedWindowEnd = -1
            displayedCurrentIdx = -1
            displayedStatus = state.status
            displayedTrackTitle = state.track?.title
            lastNotifyTime = System.currentTimeMillis()
            handler.removeCallbacksAndMessages(null)
            pendingNotify = false
            resetKaraokeState()
            notifyChildrenChanged(ROOT_ID)
            notifyChildrenChanged(SYNC_MENU_ID)
            return
        }

        val win = computeWindow(state)
        val windowChanged = win.start != displayedWindowStart || win.end != displayedWindowEnd
        val lineChanged = win.currentIdx != displayedCurrentIdx
        val karaokeActive = aaKaraokeEnabled && state.status == LyricsStatus.FOUND
            && state.lines.getOrNull(state.currentIndex)?.words?.isNotEmpty() == true
        val dots = if (state.status == LyricsStatus.FOUND) {
            Countdown.compute(state.lines, getAaPositionMs()).dots
        } else 0
        val dotsChanged = dots != displayedDots
        displayedDots = dots

        if (!windowChanged && !lineChanged && !statusChanged && !karaokeActive && !dotsChanged) return

        displayedWindowStart = win.start
        displayedWindowEnd = win.end
        displayedCurrentIdx = win.currentIdx
        displayedStatus = state.status

        val now = System.currentTimeMillis()
        val elapsed = now - lastNotifyTime

        if (elapsed >= NOTIFY_THROTTLE_MS) {
            lastNotifyTime = now
            notifyChildrenChanged(ROOT_ID)
            notifyChildrenChanged(SYNC_MENU_ID)
        } else if (!pendingNotify) {
            pendingNotify = true
            handler.postDelayed({
                pendingNotify = false
                lastNotifyTime = System.currentTimeMillis()
                notifyChildrenChanged(ROOT_ID)
                notifyChildrenChanged(SYNC_MENU_ID)
            }, NOTIFY_THROTTLE_MS - elapsed)
        }
    }

    // --- Transport controls ---

    private fun getActiveMediaController(): MediaController? {
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE)
            as? android.media.session.MediaSessionManager ?: return null
        return try {
            val component = android.content.ComponentName(
                this, com.autolyrics.media.MediaListenerService::class.java
            )
            sessionManager.getActiveSessions(component)
                .firstOrNull { it.packageName != packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessionManager.getActiveSessions(component)
                    .firstOrNull { it.packageName != packageName }
        } catch (_: SecurityException) {
            null
        }
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            getActiveMediaController()?.transportControls?.play()
        }

        override fun onPause() {
            getActiveMediaController()?.transportControls?.pause()
        }

        override fun onSkipToNext() {
            getActiveMediaController()?.transportControls?.skipToNext()
        }

        override fun onSkipToPrevious() {
            getActiveMediaController()?.transportControls?.skipToPrevious()
        }

        override fun onStop() {
            getActiveMediaController()?.transportControls?.stop()
        }

        override fun onSeekTo(pos: Long) {
            getActiveMediaController()?.transportControls?.seekTo(pos)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            if (mediaId == null) return

            if (mediaId == SYNC_MINUS_ID || mediaId == SYNC_PLUS_ID) {
                if (mediaId == SYNC_MINUS_ID) aaOffsetMs -= SYNC_STEP_MS
                else aaOffsetMs += SYNC_STEP_MS
                getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
                    .edit().putLong("aa_offset_ms", aaOffsetMs).apply()
                notifyChildrenChanged(ROOT_ID)
                notifyChildrenChanged(SYNC_MENU_ID)
                return
            }

            when (mediaId) {
                SONG_NEXT_ID -> {
                    getActiveMediaController()?.transportControls?.skipToNext()
                    return
                }
                SONG_RESTART_ID -> {
                    getActiveMediaController()?.transportControls?.seekTo(0)
                    return
                }
                SONG_PREVIOUS_ID -> {
                    // Music apps restart the current song on "previous" unless it just started,
                    // so jump to the start first to make sure we really go back one song.
                    val controls = getActiveMediaController()?.transportControls ?: return
                    controls.seekTo(0)
                    scope.launch {
                        delay(PREVIOUS_SONG_DELAY_MS)
                        controls.skipToPrevious()
                    }
                    return
                }
            }

            if (!mediaId.startsWith("line_")) return
            val index = mediaId.removePrefix("line_").toIntOrNull() ?: return
            val state = mediaTracker.state.value
            if (state.status != LyricsStatus.FOUND) return
            val line = state.lines.getOrNull(index) ?: return
            if (line.timeMs > 0) {
                getActiveMediaController()?.transportControls?.seekTo(line.timeMs)
            }
        }
    }
}
