package com.autolyrics.media

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.autolyrics.lyrics.Countdown
import com.autolyrics.lyrics.LrcLibClient
import com.autolyrics.lyrics.LrcParser
import com.autolyrics.lyrics.LyricsCache
import com.autolyrics.lyrics.LyricsTranslator
import com.autolyrics.lyrics.MetadataCleaner
import com.autolyrics.lyrics.MusixmatchClient
import com.autolyrics.lyrics.Romanizer
import com.autolyrics.lyrics.SyncLrcClient
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsCandidate
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import com.autolyrics.util.AlbumColorExtractor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaTracker private constructor(context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences =
        context.getSharedPreferences("auto_lyrics_prefs", Context.MODE_PRIVATE)
    private val lyricsCache = LyricsCache(context)

    private val _state = MutableStateFlow(LyricsState())
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    private var activeController: MediaController? = null
    private var lastPositionMs: Long = 0
    private var lastPositionUpdateTime: Long = 0
    private var playbackSpeed: Float = 1.0f
    private var fetchJob: Job? = null
    private var prefetchJob: Job? = null
    private var artJob: Job? = null
    private var translationJob: Job? = null
    private var pendingTrack: TrackInfo? = null
    private var pendingArt: Bitmap? = null
    private var lyricsOffsetMs: Long = 0L

    init {
        lyricsOffsetMs = prefs.getLong("lyrics_offset_ms", 0L)
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
    }

    private val positionChecker = object : Runnable {
        override fun run() {
            updateCurrentPosition()
            if (_state.value.isPlaying) {
                handler.postDelayed(this, 150)
            }
        }
    }

    private val trackChangeRunnable = Runnable {
        val track = pendingTrack ?: return@Runnable
        val art = pendingArt
        val current = _state.value.track
        if (track.title == current?.title && track.artist == current.artist) return@Runnable

        translationJob?.cancel()
        _state.value = _state.value.copy(
            track = track,
            lines = emptyList(),
            currentIndex = -1,
            currentWordIndex = -1,
            status = LyricsStatus.LOADING,
            source = "",
            albumArt = art,
            albumColors = null,
            translatedLines = null,
            detectedLanguage = null,
            romanizedLines = null,
            countdownDots = 0,
            countdownLineIndex = -1
        )
        fetchLyrics(track)
        extractAlbumColors(art)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleMetadataChanged(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handlePlaybackStateChanged(state)
        }

        override fun onSessionDestroyed() {
            onMediaSessionChanged(null)
        }
    }

    fun adjustOffset(deltaMs: Long) {
        lyricsOffsetMs += deltaMs
        prefs.edit().putLong("lyrics_offset_ms", lyricsOffsetMs).apply()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun resetOffset() {
        lyricsOffsetMs = 0L
        prefs.edit().putLong("lyrics_offset_ms", 0L).apply()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun resumePlayback() {
        activeController?.transportControls?.play()
    }

    fun setOffset(ms: Long) {
        lyricsOffsetMs = ms
        prefs.edit().putLong("lyrics_offset_ms", lyricsOffsetMs).apply()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun getCurrentPositionMs(): Long {
        val basePos = if (!_state.value.isPlaying) {
            lastPositionMs
        } else {
            val elapsed = SystemClock.elapsedRealtime() - lastPositionUpdateTime
            lastPositionMs + (elapsed * playbackSpeed).toLong()
        }
        return basePos + lyricsOffsetMs
    }

    private fun updateCurrentPosition() {
        val currentState = _state.value
        val lines = currentState.lines
        if (lines.isEmpty() || currentState.status != LyricsStatus.FOUND) return

        val posMs = getCurrentPositionMs()

        var newLineIndex = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) {
                newLineIndex = i
            } else {
                break
            }
        }

        var newWordIndex = -1
        if (newLineIndex >= 0) {
            val words = lines[newLineIndex].words
            if (words.isNotEmpty()) {
                for (i in words.indices) {
                    if (words[i].timeMs <= posMs) {
                        newWordIndex = i
                    } else {
                        break
                    }
                }
            }
        }

        val countdown = Countdown.compute(lines, posMs)

        if (newLineIndex != currentState.currentIndex ||
            newWordIndex != currentState.currentWordIndex ||
            countdown.dots != currentState.countdownDots ||
            countdown.nextIndex != currentState.countdownLineIndex
        ) {
            _state.value = currentState.copy(
                currentIndex = newLineIndex,
                currentWordIndex = newWordIndex,
                countdownDots = countdown.dots,
                countdownLineIndex = countdown.nextIndex
            )
        }
    }

    fun onMediaSessionChanged(controller: MediaController?) {
        activeController?.unregisterCallback(mediaCallback)
        activeController = controller

        if (controller == null) {
            handler.removeCallbacks(positionChecker)
            handler.removeCallbacks(trackChangeRunnable)
            artJob?.cancel()
            _state.value = LyricsState(offsetMs = lyricsOffsetMs)
            return
        }

        controller.registerCallback(mediaCallback)
        handleMetadataChanged(controller.metadata)
        handlePlaybackStateChanged(controller.playbackState)
    }

    private fun handleMetadataChanged(metadata: MediaMetadata?) {
        if (metadata == null) return

        val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return
        val rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val rawAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val title = MetadataCleaner.cleanTitle(rawTitle)
        val artist = MetadataCleaner.cleanArtist(rawArtist)
        val album = MetadataCleaner.cleanAlbum(rawAlbum)

        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val newTrack = TrackInfo(title, artist, album, duration)
        val current = _state.value.track

        if (current != null && newTrack.title == current.title && newTrack.artist == current.artist) {
            if (art != null && _state.value.albumArt == null) {
                _state.value = _state.value.copy(albumArt = art)
                extractAlbumColors(art)
            }
            return
        }

        pendingTrack = newTrack
        pendingArt = art
        handler.removeCallbacks(trackChangeRunnable)
        handler.postDelayed(trackChangeRunnable, 600)
    }

    private fun extractAlbumColors(bitmap: Bitmap?) {
        artJob?.cancel()
        if (bitmap == null) return
        artJob = scope.launch(Dispatchers.Default) {
            val colors = AlbumColorExtractor.extract(bitmap)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(albumColors = colors)
            }
        }
    }

    private fun handlePlaybackStateChanged(pbState: PlaybackState?) {
        if (pbState == null) return

        val isPlaying = pbState.state == PlaybackState.STATE_PLAYING
        lastPositionMs = pbState.position
        lastPositionUpdateTime = pbState.lastPositionUpdateTime
        if (lastPositionUpdateTime == 0L) {
            lastPositionUpdateTime = SystemClock.elapsedRealtime()
        }
        playbackSpeed = if (pbState.playbackSpeed > 0) pbState.playbackSpeed else 1.0f

        _state.value = _state.value.copy(isPlaying = isPlaying)

        handler.removeCallbacks(positionChecker)
        if (isPlaying) {
            handler.post(positionChecker)
        }
    }

    private fun fetchLyrics(track: TrackInfo) {
        fetchJob?.cancel()
        fetchJob = scope.launch(Dispatchers.IO) {
            try {
                val cached = lyricsCache.get(track.title, track.artist)
                if (cached != null) {
                    val (lines, status, source) = cached
                    withContext(Dispatchers.Main) {
                        setLyrics(track, lines, status, "$source (cached)")
                    }

                    val cacheAge = lyricsCache.getAge(track.title, track.artist)
                    if (cacheAge < CACHE_REFRESH_MS) {
                        prefetchNextSong()
                        return@launch
                    }
                }

                val result = fetchPicked(track) ?: fetchAutomatic(track)

                withContext(Dispatchers.Main) {
                    if (_state.value.track != track) return@withContext

                    if (result != null) {
                        lyricsCache.put(track.title, track.artist, result.lines, result.status, result.source)
                        setLyrics(track, result.lines, result.status, result.source)
                    } else if (cached == null) {
                        _state.value = _state.value.copy(
                            status = LyricsStatus.NOT_FOUND,
                            source = ""
                        )
                    }
                }

                prefetchNextSong()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (_state.value.track == track && _state.value.status == LyricsStatus.LOADING) {
                        _state.value = _state.value.copy(
                            status = LyricsStatus.ERROR,
                            source = ""
                        )
                    }
                }
            }
        }
    }

    private fun prefetchNextSong() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            try {
                val controller = activeController ?: return@launch
                val queue = controller.queue ?: return@launch
                val currentTitle = _state.value.track?.title ?: return@launch
                val currentArtist = _state.value.track?.artist ?: return@launch

                var foundCurrent = false
                for (item in queue) {
                    val desc = item.description
                    val title = MetadataCleaner.cleanTitle(desc.title?.toString() ?: continue)
                    val artist = MetadataCleaner.cleanArtist(desc.subtitle?.toString() ?: "")

                    if (!foundCurrent) {
                        if (title.equals(currentTitle, ignoreCase = true) &&
                            artist.equals(currentArtist, ignoreCase = true)
                        ) {
                            foundCurrent = true
                        }
                        continue
                    }

                    if (lyricsCache.get(title, artist) != null) return@launch

                    val nextTrack = TrackInfo(title, artist, "", 0)
                    val result = fetchPicked(nextTrack) ?: fetchAutomatic(nextTrack)
                    if (result != null) {
                        lyricsCache.put(title, artist, result.lines, result.status, result.source)
                    }
                    return@launch
                }
            } catch (_: Exception) {
                // prefetch failures are non-fatal
            }
        }
    }

    private data class FetchResult(
        val lines: List<LyricLine>,
        val status: LyricsStatus,
        val source: String
    )

    /**
     * Word-timed lyrics win over line-timed ones: SyncLRC karaoke, then Musixmatch
     * (RichSync or line sync), then SyncLRC's other results, then LRCLIB.
     */
    private fun fetchAutomatic(track: TrackInfo): FetchResult? {
        val syncLrc = fetchFromSyncLrc(track)
        if (syncLrc != null && syncLrc.lines.any { it.words.isNotEmpty() }) return syncLrc
        val musixmatch = if (prefs.getBoolean("musixmatch_enabled", true)) fetchFromMusixmatch(track) else null
        if (musixmatch != null && (musixmatch.status == LyricsStatus.FOUND || syncLrc == null)) return musixmatch
        return syncLrc ?: fetchFromLrcLib(track)
    }

    private fun fetchFromMusixmatch(track: TrackInfo): FetchResult? {
        val result = try {
            MusixmatchClient.getLyrics(track, prefs)
        } catch (_: Exception) {
            null
        } ?: return null
        val hasRealText = result.lines.any { it.text != "♪" && it.text.isNotBlank() }
        if (!hasRealText) return null
        return FetchResult(
            result.lines,
            LyricsStatus.FOUND,
            if (result.isRichSync) "Musixmatch · Karaoke" else "Musixmatch · Synced"
        )
    }

    private fun fetchFromSyncLrc(track: TrackInfo): FetchResult? {
        val result = try {
            SyncLrcClient.getLyrics(track.title, track.artist)
        } catch (_: Exception) {
            null
        } ?: return null

        return when (result.type) {
            SyncLrcClient.LyricsType.KARAOKE -> {
                val lines = LrcParser.parseKaraoke(result.lyrics)
                val hasRealText = lines.any { it.text != "♪" && it.text.isNotBlank() }
                if (hasRealText) FetchResult(lines, LyricsStatus.FOUND, "SyncLRC · Karaoke")
                else null
            }
            SyncLrcClient.LyricsType.SYNCED -> {
                val lines = LrcParser.parse(result.lyrics)
                val hasRealText = lines.any { it.text != "♪" && it.text.isNotBlank() }
                if (hasRealText) FetchResult(lines, LyricsStatus.FOUND, "SyncLRC · Synced")
                else null
            }
            SyncLrcClient.LyricsType.PLAIN -> {
                val lines = result.lyrics.lines()
                    .filter { it.isNotBlank() }
                    .map { text -> LyricLine(0L, text) }
                if (lines.isNotEmpty()) FetchResult(lines, LyricsStatus.PLAIN_ONLY, "SyncLRC · Plain")
                else null
            }
        }
    }

    private fun fetchFromLrcLib(track: TrackInfo): FetchResult? {
        val durationSec = if (track.durationMs > 0) (track.durationMs / 1000).toInt() else 0

        val result = try {
            LrcLibClient.getLyrics(
                trackName = track.title,
                artistName = track.artist,
                albumName = track.album,
                durationSec = durationSec
            )
        } catch (_: Exception) {
            null
        } ?: return null

        return lyricsToResult(result.syncedLyrics, result.plainLyrics, "LRCLIB")
    }

    /** Turns LRCLIB lyrics text into lines, preferring the timed version. */
    private fun lyricsToResult(synced: String?, plain: String?, provider: String): FetchResult? {
        if (!synced.isNullOrBlank()) {
            val lines = LrcParser.parse(synced)
            val hasRealText = lines.any { it.text != "♪" && it.text.isNotBlank() }
            if (hasRealText) return FetchResult(lines, LyricsStatus.FOUND, "$provider · Synced")
        }

        if (!plain.isNullOrBlank()) {
            val lines = plain.lines()
                .filter { it.isNotBlank() }
                .map { text -> LyricLine(0L, text) }
            if (lines.isNotEmpty()) return FetchResult(lines, LyricsStatus.PLAIN_ONLY, "$provider · Plain")
        }

        return null
    }

    // --- Lyrics the user picked with "Wrong lyrics?" ---

    private fun pickKey(title: String, artist: String): String =
        "lyrics_pick_${title.lowercase().trim()}|${artist.lowercase().trim()}"

    private fun fetchPicked(track: TrackInfo): FetchResult? {
        val id = prefs.getInt(pickKey(track.title, track.artist), -1)
        if (id <= 0) return null
        val result = try {
            LrcLibClient.getById(id)
        } catch (_: Exception) {
            null
        } ?: return null
        return lyricsToResult(result.syncedLyrics, result.plainLyrics, "Your pick")
    }

    /** True when the current song uses lyrics the user picked by hand. */
    fun hasPickedLyrics(): Boolean {
        val track = _state.value.track ?: return false
        return prefs.getInt(pickKey(track.title, track.artist), -1) > 0
    }

    /**
     * Other lyrics matches for the current song, best first. Pass [query] to search
     * by free text instead of the song's own title and artist.
     */
    suspend fun searchAlternatives(query: String? = null): List<LyricsCandidate> =
        withContext(Dispatchers.IO) {
            val track = _state.value.track ?: return@withContext emptyList()
            val raw = try {
                if (query.isNullOrBlank()) {
                    LrcLibClient.searchCandidates(track.title, track.artist)
                } else {
                    LrcLibClient.searchQuery(query)
                }
            } catch (_: Exception) {
                emptyList()
            }
            val targetSec = (track.durationMs / 1000).toInt()
            raw.mapNotNull { r ->
                val id = r.id ?: return@mapNotNull null
                if (r.syncedLyrics.isNullOrBlank() && r.plainLyrics.isNullOrBlank()) return@mapNotNull null
                LyricsCandidate(
                    id = id,
                    title = r.trackName.orEmpty(),
                    artist = r.artistName.orEmpty(),
                    album = r.albumName.orEmpty(),
                    durationSec = r.duration?.toInt() ?: 0,
                    syncedLyrics = r.syncedLyrics,
                    plainLyrics = r.plainLyrics
                )
            }
                .distinctBy { it.id }
                .sortedWith(
                    compareBy<LyricsCandidate> { if (it.syncedLyrics.isNullOrBlank()) 1 else 0 }
                        .thenBy {
                            if (targetSec > 0 && it.durationSec > 0) kotlin.math.abs(it.durationSec - targetSec)
                            else Int.MAX_VALUE
                        }
                )
                .take(25)
        }

    /** Uses [candidate] for the current song now and every time it plays later. */
    fun applyCandidate(candidate: LyricsCandidate): Boolean {
        val track = _state.value.track ?: return false
        val result = lyricsToResult(candidate.syncedLyrics, candidate.plainLyrics, "Your pick")
            ?: return false
        prefs.edit().putInt(pickKey(track.title, track.artist), candidate.id).apply()
        lyricsCache.put(track.title, track.artist, result.lines, result.status, result.source)
        setLyrics(track, result.lines, result.status, result.source)
        return true
    }

    /** Forgets the user's pick and goes back to the automatic match. */
    fun clearPickedLyrics() {
        val track = _state.value.track ?: return
        prefs.edit().remove(pickKey(track.title, track.artist)).apply()
        lyricsCache.remove(track.title, track.artist)
        translationJob?.cancel()
        _state.value = _state.value.copy(
            lines = emptyList(),
            currentIndex = -1,
            currentWordIndex = -1,
            status = LyricsStatus.LOADING,
            source = "",
            translatedLines = null,
            detectedLanguage = null,
            romanizedLines = null,
            countdownDots = 0,
            countdownLineIndex = -1
        )
        fetchLyrics(track)
    }

    // --- Shared state updates ---

    /** Shows new lyrics for [track] (if it's still playing) and starts translation/pronunciation. */
    private fun setLyrics(track: TrackInfo, lines: List<LyricLine>, status: LyricsStatus, source: String) {
        if (_state.value.track != track) return
        _state.value = _state.value.copy(
            lines = lines,
            currentIndex = -1,
            currentWordIndex = -1,
            status = status,
            source = source,
            translatedLines = null,
            detectedLanguage = null,
            romanizedLines = romanizeIfEnabled(lines),
            countdownDots = 0,
            countdownLineIndex = -1
        )
        if (status == LyricsStatus.FOUND) {
            updateCurrentPosition()
        }
        translateIfNeeded(lines, track)
    }

    private fun romanizeIfEnabled(lines: List<LyricLine>): List<String>? {
        if (!prefs.getBoolean("romanization_enabled", true)) return null
        return try {
            Romanizer.romanizeLines(lines.map { it.text })
        } catch (_: Exception) {
            null
        }
    }

    /** Re-applies the pronunciation setting to the lyrics on screen. */
    fun refreshRomanization() {
        val current = _state.value
        _state.value = current.copy(romanizedLines = romanizeIfEnabled(current.lines))
    }

    private fun translateIfNeeded(lines: List<LyricLine>, track: TrackInfo) {
        if (!prefs.getBoolean("translation_enabled", true)) return
        translationJob?.cancel()
        translationJob = scope.launch(Dispatchers.IO) {
            try {
                val result = LyricsTranslator.translateLines(lines) ?: return@launch
                withContext(Dispatchers.Main) {
                    if (_state.value.track == track) {
                        _state.value = _state.value.copy(
                            translatedLines = result.translatedLines,
                            detectedLanguage = result.detectedLanguage
                        )
                    }
                }
            } catch (_: Exception) { }
        }
    }

    companion object {
        private const val CACHE_REFRESH_MS = 7L * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: MediaTracker? = null

        fun init(context: Context) {
            getInstance(context)
        }

        fun getInstance(context: Context): MediaTracker {
            return instance ?: synchronized(this) {
                instance ?: MediaTracker(context.applicationContext).also { instance = it }
            }
        }
    }
}
