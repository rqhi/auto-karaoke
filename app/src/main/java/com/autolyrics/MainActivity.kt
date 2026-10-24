package com.autolyrics

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autolyrics.media.MediaListenerService
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.AlbumColors
import com.autolyrics.model.LyricsCandidate
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var mediaTracker: MediaTracker
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var rootLayout: LinearLayout
    private lateinit var tvAppTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnPermission: Button
    private lateinit var ivAlbumArt: ImageView
    private lateinit var tvTrack: TextView
    private lateinit var tvArtist: TextView
    private lateinit var btnWrongLyrics: Button
    private lateinit var switchRomanization: SwitchCompat
    private lateinit var tvLyrics: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var tvDelay: TextView
    private lateinit var btnSettingsToggle: ImageButton
    private lateinit var fontSettingsPanel: LinearLayout
    private lateinit var tvFontSize: TextView
    private lateinit var switchAaKaraoke: SwitchCompat
    private lateinit var switchTranslation: SwitchCompat
    private lateinit var tvAaDelay: TextView
    private lateinit var btnJumpToCurrent: Button
    private lateinit var prefs: SharedPreferences
    private var lastScrolledIndex = -1
    private var currentLineCharStart = -1
    private var colorsApplied = false
    private var currentColors: AlbumColors? = null
    private var lyricsFontSizeSp = DEFAULT_FONT_SIZE
    private var lyricsFontFamily = "sans-serif"
    private var aaOffsetMs = 0L
    private var userScrolling = false
    private var userTouching = false

    private var plainScrollAnimator: ValueAnimator? = null
    private var lastPlainTrackTitle: String? = null

    private lateinit var btnTapSync: Button
    private lateinit var tvSyncStatus: TextView
    private var tapSyncActive = false
    private var tapSyncTargetLineIndex = -1
    private var tapSyncOffsets = mutableListOf<Long>()

    private val fontButtons = mutableMapOf<String, Button>()
    private val scrollResetRunnable = Runnable {
        userScrolling = false
        btnJumpToCurrent.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaTracker = MediaTracker.getInstance(this)
        prefs = getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)

        rootLayout = findViewById(R.id.root_layout)
        tvAppTitle = findViewById(R.id.tv_app_title)
        tvStatus = findViewById(R.id.tv_status)
        btnPermission = findViewById(R.id.btn_permission)
        ivAlbumArt = findViewById(R.id.iv_album_art)
        tvTrack = findViewById(R.id.tv_track)
        tvArtist = findViewById(R.id.tv_artist)
        btnWrongLyrics = findViewById(R.id.btn_wrong_lyrics)
        btnWrongLyrics.setOnClickListener { openLyricsPicker(null) }
        tvLyrics = findViewById(R.id.tv_lyrics)
        scrollView = findViewById(R.id.scroll_lyrics)
        tvDelay = findViewById(R.id.tv_delay)

        ivAlbumArt.clipToOutline = true
        btnJumpToCurrent = findViewById(R.id.btn_jump_to_current)

        setupScrollDetection()

        btnPermission.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        findViewById<Button>(R.id.btn_delay_minus).setOnClickListener {
            mediaTracker.adjustOffset(-100)
        }
        findViewById<Button>(R.id.btn_delay_plus).setOnClickListener {
            mediaTracker.adjustOffset(100)
        }
        findViewById<Button>(R.id.btn_delay_reset).setOnClickListener {
            mediaTracker.resetOffset()
        }

        fontSettingsPanel = findViewById(R.id.layout_font_settings)
        tvFontSize = findViewById(R.id.tv_font_size)

        lyricsFontSizeSp = prefs.getInt("lyrics_font_size", DEFAULT_FONT_SIZE)
            .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        lyricsFontFamily = prefs.getString("lyrics_font_family", "sans-serif") ?: "sans-serif"

        applyFontSettings()

        btnSettingsToggle = findViewById(R.id.btn_settings_toggle)
        btnSettingsToggle.setOnClickListener { toggleSettingsPanel() }

        findViewById<Button>(R.id.btn_performance_mode).setOnClickListener {
            startActivity(Intent(this, PerformanceActivity::class.java))
        }

        findViewById<Button>(R.id.btn_font_size_minus).setOnClickListener {
            if (lyricsFontSizeSp > MIN_FONT_SIZE) {
                lyricsFontSizeSp -= 2
                saveFontSettings()
                applyFontSettings()
            }
        }
        findViewById<Button>(R.id.btn_font_size_plus).setOnClickListener {
            if (lyricsFontSizeSp < MAX_FONT_SIZE) {
                lyricsFontSizeSp += 2
                saveFontSettings()
                applyFontSettings()
            }
        }

        val btnSans = findViewById<Button>(R.id.btn_font_sans)
        val btnSerif = findViewById<Button>(R.id.btn_font_serif)
        val btnMono = findViewById<Button>(R.id.btn_font_mono)
        val btnCursive = findViewById<Button>(R.id.btn_font_cursive)

        // Preference keys are kept from older versions so saved choices carry over.
        fontButtons["sans-serif"] = btnSans
        fontButtons["serif"] = btnSerif
        fontButtons["monospace"] = btnMono
        fontButtons["cursive"] = btnCursive

        btnSans.setOnClickListener { selectFont("sans-serif") }
        btnSerif.setOnClickListener { selectFont("serif") }
        btnMono.setOnClickListener { selectFont("monospace") }
        btnCursive.setOnClickListener { selectFont("cursive") }

        updateFontButtonHighlights()

        switchAaKaraoke = findViewById(R.id.switch_aa_karaoke)
        tvAaDelay = findViewById(R.id.tv_aa_delay)

        switchAaKaraoke.isChecked = prefs.getBoolean("aa_karaoke_enabled", true)
        aaOffsetMs = prefs.getLong("aa_offset_ms", 0L)
        updateAaDelayDisplay()

        switchAaKaraoke.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("aa_karaoke_enabled", isChecked).apply()
        }

        switchTranslation = findViewById(R.id.switch_translation)
        switchTranslation.isChecked = prefs.getBoolean("translation_enabled", true)
        switchTranslation.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("translation_enabled", isChecked).apply()
        }

        switchRomanization = findViewById(R.id.switch_romanization)
        switchRomanization.isChecked = prefs.getBoolean("romanization_enabled", true)
        switchRomanization.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("romanization_enabled", isChecked).apply()
            mediaTracker.refreshRomanization()
        }

        findViewById<SwitchCompat>(R.id.switch_musixmatch).apply {
            isChecked = prefs.getBoolean("musixmatch_enabled", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("musixmatch_enabled", isChecked).apply()
            }
        }

        findViewById<SwitchCompat>(R.id.switch_aa_hide_controls).apply {
            isChecked = prefs.getBoolean("aa_hide_controls", false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("aa_hide_controls", isChecked).apply()
            }
        }

        findViewById<SwitchCompat>(R.id.switch_aa_show_cover_art).apply {
            isChecked = prefs.getBoolean("aa_show_cover_art", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("aa_show_cover_art", isChecked).apply()
            }
        }

        findViewById<Button>(R.id.btn_aa_delay_minus).setOnClickListener {
            aaOffsetMs -= 100
            prefs.edit().putLong("aa_offset_ms", aaOffsetMs).apply()
            updateAaDelayDisplay()
        }
        findViewById<Button>(R.id.btn_aa_delay_plus).setOnClickListener {
            aaOffsetMs += 100
            prefs.edit().putLong("aa_offset_ms", aaOffsetMs).apply()
            updateAaDelayDisplay()
        }
        findViewById<Button>(R.id.btn_aa_delay_reset).setOnClickListener {
            aaOffsetMs = 0L
            prefs.edit().putLong("aa_offset_ms", 0L).apply()
            updateAaDelayDisplay()
        }

        btnTapSync = findViewById(R.id.btn_tap_sync)
        tvSyncStatus = findViewById(R.id.tv_sync_status)

        btnTapSync.setOnClickListener { onTapSyncPressed() }

        findViewById<Button>(R.id.btn_quick_minus_1s).setOnClickListener {
            mediaTracker.adjustOffset(-1000)
            showSyncStatus("Phone timing: ${formatOffset(mediaTracker.state.value.offsetMs)}")
        }
        findViewById<Button>(R.id.btn_quick_minus_half).setOnClickListener {
            mediaTracker.adjustOffset(-500)
            showSyncStatus("Phone timing: ${formatOffset(mediaTracker.state.value.offsetMs)}")
        }
        findViewById<Button>(R.id.btn_quick_plus_half).setOnClickListener {
            mediaTracker.adjustOffset(500)
            showSyncStatus("Phone timing: ${formatOffset(mediaTracker.state.value.offsetMs)}")
        }
        findViewById<Button>(R.id.btn_quick_plus_1s).setOnClickListener {
            mediaTracker.adjustOffset(1000)
            showSyncStatus("Phone timing: ${formatOffset(mediaTracker.state.value.offsetMs)}")
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mediaTracker.state.collect { state ->
                    updatePermissionUi()
                    updateDelayDisplay(state.offsetMs)
                    updateAlbumArt(state)
                    applyThemeColors(state.albumColors)

                    val track = state.track
                    if (track != null) {
                        tvTrack.text = track.title
                        tvArtist.text = track.artist.ifBlank { "Unknown artist" }
                        tvTrack.visibility = View.VISIBLE
                        tvArtist.visibility = View.VISIBLE
                    } else {
                        tvTrack.text = ""
                        tvArtist.text = ""
                        tvTrack.visibility = View.GONE
                        tvArtist.visibility = View.GONE
                    }

                    when {
                        track == null -> btnWrongLyrics.visibility = View.GONE
                        state.status == LyricsStatus.FOUND || state.status == LyricsStatus.PLAIN_ONLY -> {
                            btnWrongLyrics.text = "Wrong lyrics?"
                            btnWrongLyrics.visibility = View.VISIBLE
                        }
                        state.status == LyricsStatus.NOT_FOUND || state.status == LyricsStatus.ERROR -> {
                            btnWrongLyrics.text = "Search lyrics"
                            btnWrongLyrics.visibility = View.VISIBLE
                        }
                        else -> btnWrongLyrics.visibility = View.GONE
                    }

                    if (state.status != LyricsStatus.PLAIN_ONLY) {
                        stopPlainScroll()
                    } else {
                        plainScrollAnimator?.let { anim ->
                            if (state.isPlaying && anim.isPaused) anim.resume()
                            else if (!state.isPlaying && anim.isRunning) anim.pause()
                        }
                    }

                    when (state.status) {
                        LyricsStatus.NO_MEDIA -> {
                            showMessage("Play a song in Spotify or any music app. The lyrics will show up here and on Android Auto.")
                        }
                        LyricsStatus.LOADING -> {
                            showMessage("Finding lyrics…")
                        }
                        LyricsStatus.NOT_FOUND -> {
                            showMessage("No lyrics found for this song. Tap Search lyrics to look it up by name.")
                        }
                        LyricsStatus.ERROR -> {
                            showMessage("Couldn't load lyrics. Check your internet connection and try the song again.")
                        }
                        LyricsStatus.FOUND -> {
                            renderSyncedLyrics(state)
                        }
                        LyricsStatus.PLAIN_ONLY -> {
                            stopPlainScroll()
                            renderPlainLyrics(state)
                            startPlainScroll(state)
                        }
                    }
                }
            }
        }
    }

    private fun showMessage(message: String) {
        currentLineCharStart = -1
        lastScrolledIndex = -1
        tvLyrics.text = message
    }

    private fun toggleSettingsPanel() {
        val opening = fontSettingsPanel.visibility != View.VISIBLE
        if (opening) {
            fontSettingsPanel.alpha = 0f
            fontSettingsPanel.translationY = -12f * resources.displayMetrics.density
            fontSettingsPanel.visibility = View.VISIBLE
            fontSettingsPanel.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            fontSettingsPanel.animate().cancel()
            fontSettingsPanel.visibility = View.GONE
        }
        btnSettingsToggle.imageTintList = ColorStateList.valueOf(if (opening) SODIUM else HEADLIGHT)
    }

    private fun updateAlbumArt(state: LyricsState) {
        val art = state.albumArt
        if (art != null) {
            ivAlbumArt.setImageBitmap(art)
            ivAlbumArt.visibility = View.VISIBLE
        } else {
            ivAlbumArt.setImageDrawable(null)
            ivAlbumArt.visibility = View.GONE
        }
    }

    private fun applyThemeColors(colors: AlbumColors?) {
        if (colorsApplied && colors == currentColors) return
        colorsApplied = true
        currentColors = colors

        val top = colors?.dominant ?: NIGHT
        val bottom = if (colors != null) {
            ColorUtils.blendARGB(colors.dominantDark, NIGHT_DEEP, 0.5f)
        } else NIGHT_DEEP
        val middle = ColorUtils.blendARGB(top, bottom, 0.55f)

        rootLayout.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, middle, bottom)
        )
        window.statusBarColor = top
        window.navigationBarColor = bottom

        val primary = colors?.textPrimary ?: HEADLIGHT
        tvAppTitle.setTextColor(primary)
        tvTrack.setTextColor(primary)
        tvArtist.setTextColor(if (colors != null) withAlpha(primary, 0.62f) else DUSK)
        tvLyrics.setTextColor(primary)
    }

    private fun updateDelayDisplay(offsetMs: Long) {
        tvDelay.text = formatOffset(offsetMs)
    }

    private fun renderSyncedLyrics(state: LyricsState) {
        val ssb = SpannableStringBuilder()
        val colors = state.albumColors
        val primary = colors?.textPrimary ?: HEADLIGHT
        val accent = colors?.vibrant ?: SODIUM
        val upcoming = withAlpha(primary, 0.5f)
        val past = withAlpha(primary, 0.28f)
        currentLineCharStart = -1

        state.lines.forEachIndexed { i, line ->
            val isCurrentLine = i == state.currentIndex

            if (state.countdownDots > 0 && i == state.countdownLineIndex) {
                appendCountdown(ssb, state.countdownDots, accent)
                ssb.append("\n")
            }

            val lineStart = ssb.length
            if (isCurrentLine) currentLineCharStart = lineStart

            if (isCurrentLine && line.words.isNotEmpty() && state.currentWordIndex >= 0) {
                // Karaoke fill: words already sung light up in the accent color.
                line.words.forEachIndexed { wi, word ->
                    val wordStart = ssb.length
                    ssb.append(word.text)
                    val color = if (wi <= state.currentWordIndex) accent else primary
                    ssb.setSpan(
                        ForegroundColorSpan(color),
                        wordStart, ssb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    if (wi < line.words.size - 1) ssb.append(" ")
                }
            } else {
                ssb.append(line.text.ifBlank { "♪" })
                val color = when {
                    isCurrentLine -> primary
                    i < state.currentIndex -> past
                    else -> upcoming
                }
                ssb.setSpan(
                    ForegroundColorSpan(color),
                    lineStart, ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            val romanized = state.romanizedLines?.getOrNull(i)
            if (!romanized.isNullOrBlank()) {
                ssb.append("\n")
                val rStart = ssb.length
                ssb.append(romanized)
                ssb.setSpan(
                    RelativeSizeSpan(0.82f),
                    rStart, ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                val romanColor = when {
                    isCurrentLine -> withAlpha(primary, 0.85f)
                    i < state.currentIndex -> past
                    else -> upcoming
                }
                ssb.setSpan(
                    ForegroundColorSpan(romanColor),
                    rStart, ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            val translatedLine = state.translatedLines?.getOrNull(i)
            if (!translatedLine.isNullOrBlank()) {
                ssb.append("\n")
                val tlStart = ssb.length
                ssb.append(translatedLine)
                ssb.setSpan(
                    RelativeSizeSpan(0.78f),
                    tlStart, ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                ssb.setSpan(
                    ForegroundColorSpan(withAlpha(primary, if (isCurrentLine) 0.7f else 0.24f)),
                    tlStart, ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            if (i < state.lines.size - 1) appendLineGap(ssb)
        }

        tvLyrics.text = ssb

        if (state.currentIndex != lastScrolledIndex && state.currentIndex >= 0) {
            lastScrolledIndex = state.currentIndex
            if (!userScrolling) {
                autoScrollToCurrentLine(state.currentIndex, state.lines.size)
            }
        }
    }

    private fun renderPlainLyrics(state: LyricsState) {
        currentLineCharStart = -1
        val primary = state.albumColors?.textPrimary ?: HEADLIGHT
        val lineColor = withAlpha(primary, 0.85f)
        val sb = SpannableStringBuilder()

        val noteStart = sb.length
        sb.append("These lyrics aren't timed to the song, so they scroll at an even pace.")
        sb.setSpan(RelativeSizeSpan(0.7f), noteStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(
            ForegroundColorSpan(withAlpha(primary, 0.55f)),
            noteStart, sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        appendLineGap(sb)
        appendLineGap(sb)

        state.lines.forEachIndexed { i, line ->
            val lineStart = sb.length
            sb.append(line.text.ifBlank { "♪" })
            sb.setSpan(ForegroundColorSpan(lineColor), lineStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            val roman = state.romanizedLines?.getOrNull(i)
            if (!roman.isNullOrBlank()) {
                sb.append("\n")
                val rStart = sb.length
                sb.append(roman)
                sb.setSpan(RelativeSizeSpan(0.82f), rStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(
                    ForegroundColorSpan(withAlpha(primary, 0.7f)),
                    rStart, sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            val tl = state.translatedLines?.getOrNull(i)
            if (!tl.isNullOrBlank()) {
                sb.append("\n")
                val tlStart = sb.length
                sb.append(tl)
                sb.setSpan(RelativeSizeSpan(0.78f), tlStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(
                    ForegroundColorSpan(withAlpha(primary, 0.45f)),
                    tlStart, sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (i < state.lines.size - 1) appendLineGap(sb)
        }
        tvLyrics.text = sb
    }

    /** Karaoke countdown: remaining dots in the accent color, spent ones faded. */
    private fun appendCountdown(ssb: SpannableStringBuilder, dots: Int, accent: Int) {
        for (k in 1..COUNTDOWN_DOTS) {
            val start = ssb.length
            ssb.append("●")
            val color = if (k <= dots) accent else withAlpha(accent, 0.22f)
            ssb.setSpan(ForegroundColorSpan(color), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (k < COUNTDOWN_DOTS) ssb.append("  ")
        }
    }

    // --- "Wrong lyrics?" picker ---

    private fun openLyricsPicker(query: String?) {
        val track = mediaTracker.state.value.track ?: return
        val searching = MaterialAlertDialogBuilder(this)
            .setTitle("Looking for other lyrics…")
            .setMessage(if (query.isNullOrBlank()) "${track.title} — ${track.artist}" else "“$query”")
            .setNegativeButton("Cancel", null)
            .show()

        lifecycleScope.launch {
            val results = mediaTracker.searchAlternatives(query)
            if (!searching.isShowing || isFinishing) return@launch
            searching.dismiss()
            if (mediaTracker.state.value.track != track) return@launch
            showLyricsChoices(results)
        }
    }

    private fun showLyricsChoices(results: List<LyricsCandidate>) {
        val track = mediaTracker.state.value.track ?: return
        val targetSec = (track.durationMs / 1000).toInt()
        val hasPick = mediaTracker.hasPickedLyrics()

        // Fixed actions first, then the matches.
        val actions = mutableListOf<Pair<CharSequence, () -> Unit>>()
        actions += optionLabel("Search by name…", "Type a different title or artist") to {
            askForSearchText(track.title, track.artist)
        }
        if (hasPick) {
            actions += optionLabel("Use the automatic match", "Forget the lyrics you picked for this song") to {
                mediaTracker.clearPickedLyrics()
            }
        }
        results.forEach { candidate ->
            actions += candidateLabel(candidate, targetSec) to {
                if (!mediaTracker.applyCandidate(candidate)) {
                    MaterialAlertDialogBuilder(this)
                        .setMessage("Those lyrics couldn't be read. Try another one.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (results.isEmpty()) "No other versions found" else "Pick the right lyrics")
            .setNegativeButton("Close", null)
        builder.setItems(actions.map { it.first }.toTypedArray()) { _, which ->
            actions.getOrNull(which)?.second?.invoke()
        }
        builder.show()
    }

    private fun askForSearchText(title: String, artist: String) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText("$title $artist".trim())
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
            setTextColor(HEADLIGHT)
            setHintTextColor(DUSK)
            hint = "Song title and artist"
        }
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Search lyrics by name")
            .setView(container)
            .setPositiveButton("Search") { _, _ ->
                openLyricsPicker(input.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                dialog.dismiss()
                openLyricsPicker(input.text.toString().trim())
                true
            } else false
        }
    }

    private fun optionLabel(title: String, detail: String): CharSequence {
        val sb = SpannableStringBuilder(title)
        sb.setSpan(ForegroundColorSpan(SODIUM), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n")
        val start = sb.length
        sb.append(detail)
        sb.setSpan(RelativeSizeSpan(0.85f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(DUSK), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun candidateLabel(c: LyricsCandidate, targetSec: Int): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append(c.title.ifBlank { "Untitled" })
        if (c.artist.isNotBlank()) sb.append(" — ").append(c.artist)
        sb.append("\n")
        val start = sb.length
        val details = mutableListOf<String>()
        details += if (!c.syncedLyrics.isNullOrBlank()) "Timed" else "Not timed"
        if (c.durationSec > 0) {
            val sameLength = targetSec > 0 && kotlin.math.abs(c.durationSec - targetSec) <= 3
            details += formatDuration(c.durationSec) + if (sameLength) " (same length as your song)" else ""
        }
        if (c.album.isNotBlank()) details += c.album
        sb.append(details.joinToString(", "))
        sb.setSpan(RelativeSizeSpan(0.85f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(DUSK), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun formatDuration(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

    /** Ends the current line and adds a short blank line before the next one. */
    private fun appendLineGap(ssb: SpannableStringBuilder) {
        ssb.append("\n")
        val gapStart = ssb.length
        ssb.append("\n")
        ssb.setSpan(RelativeSizeSpan(0.55f), gapStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUi()
    }

    override fun onDestroy() {
        stopPlainScroll()
        super.onDestroy()
    }

    private fun updatePermissionUi() {
        val enabled = isNotificationListenerEnabled()
        if (enabled) {
            tvStatus.visibility = View.GONE
            btnPermission.visibility = View.GONE
        } else {
            tvStatus.text = "Auto Lyrics needs notification access to see which song is playing."
            tvStatus.setBackgroundResource(R.drawable.bg_status_warn)
            tvStatus.visibility = View.VISIBLE
            btnPermission.visibility = View.VISIBLE
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        val component = ComponentName(this, MediaListenerService::class.java)
        return flat?.contains(component.flattenToString()) == true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupScrollDetection() {
        scrollView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    userTouching = true
                    handler.removeCallbacks(scrollResetRunnable)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    userTouching = false
                    if (userScrolling) {
                        handler.removeCallbacks(scrollResetRunnable)
                        handler.postDelayed(scrollResetRunnable, 8000)
                    }
                }
            }
            false
        }

        scrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            if (userTouching && !userScrolling) {
                userScrolling = true
                btnJumpToCurrent.visibility = View.VISIBLE
            }
        }

        btnJumpToCurrent.setOnClickListener {
            userScrolling = false
            btnJumpToCurrent.visibility = View.GONE
            handler.removeCallbacks(scrollResetRunnable)
            val state = mediaTracker.state.value
            if (state.currentIndex >= 0) {
                lastScrolledIndex = state.currentIndex
                autoScrollToCurrentLine(state.currentIndex, state.lines.size)
            }
        }
    }

    private fun autoScrollToCurrentLine(currentIndex: Int, totalLines: Int) {
        if (totalLines == 0) return
        scrollView.post {
            val layout = tvLyrics.layout
            val charStart = currentLineCharStart
            val targetY = if (layout != null && charStart >= 0 && charStart <= tvLyrics.text.length) {
                tvLyrics.paddingTop + layout.getLineTop(layout.getLineForOffset(charStart))
            } else {
                (currentIndex.toFloat() / totalLines * tvLyrics.height).toInt()
            }
            val offset = scrollView.height / 3
            scrollView.smoothScrollTo(0, maxOf(0, targetY - offset))
        }
    }

    private fun updateAaDelayDisplay() {
        tvAaDelay.text = formatOffset(aaOffsetMs)
    }

    private fun selectFont(family: String) {
        lyricsFontFamily = family
        saveFontSettings()
        applyFontSettings()
        updateFontButtonHighlights()
    }

    private fun saveFontSettings() {
        prefs.edit()
            .putInt("lyrics_font_size", lyricsFontSizeSp)
            .putString("lyrics_font_family", lyricsFontFamily)
            .apply()
    }

    private fun applyFontSettings() {
        tvLyrics.textSize = lyricsFontSizeSp.toFloat()
        tvLyrics.typeface = lyricsTypeface(lyricsFontFamily)
        tvFontSize.text = lyricsFontSizeSp.toString()
    }

    private fun lyricsTypeface(key: String): Typeface {
        val fontRes = when (key) {
            "serif" -> R.font.fraunces_semibold
            "monospace" -> R.font.jetbrains_mono_medium
            "cursive" -> R.font.nunito_extrabold
            else -> R.font.jakarta_bold
        }
        return try {
            ResourcesCompat.getFont(this, fontRes) ?: Typeface.DEFAULT_BOLD
        } catch (_: Exception) {
            Typeface.DEFAULT_BOLD
        }
    }

    private fun updateFontButtonHighlights() {
        fontButtons.forEach { (family, button) ->
            val selected = family == lyricsFontFamily
            button.backgroundTintList = ColorStateList.valueOf(if (selected) SODIUM else LANE)
            button.setTextColor(if (selected) ON_SODIUM else HEADLIGHT)
        }
    }

    private fun onTapSyncPressed() {
        val state = mediaTracker.state.value
        if (state.status != LyricsStatus.FOUND || !state.isPlaying) {
            showSyncStatus("Play a song with timed lyrics first.")
            return
        }

        if (!tapSyncActive) {
            tapSyncActive = true
            tapSyncOffsets.clear()
            val nextIdx = (state.currentIndex + 1).coerceAtMost(state.lines.size - 1)
            tapSyncTargetLineIndex = nextIdx
            setTapSyncActiveStyle(true)
            btnTapSync.text = "Tap when this line starts (1 of 3)"
            val lineText = state.lines.getOrNull(nextIdx)?.text?.take(60) ?: "…"
            showSyncStatus("“$lineText”")
        } else {
            val targetLine = state.lines.getOrNull(tapSyncTargetLineIndex)
            if (targetLine != null && targetLine.timeMs > 0) {
                val rawPos = getCurrentRawPositionMs()
                val offset = rawPos - targetLine.timeMs
                tapSyncOffsets.add(offset)
            }

            if (tapSyncOffsets.size >= 3) {
                val avgOffset = tapSyncOffsets.average().toLong()
                mediaTracker.setOffset(avgOffset)
                showSyncStatus("Synced. Phone timing is now ${formatOffset(avgOffset)}.")
                tapSyncActive = false
                setTapSyncActiveStyle(false)
                btnTapSync.text = "Sync"
            } else {
                tapSyncTargetLineIndex = (tapSyncTargetLineIndex + 1).coerceAtMost(state.lines.size - 1)
                btnTapSync.text = "Tap when this line starts (${tapSyncOffsets.size + 1} of 3)"
                val lineText = state.lines.getOrNull(tapSyncTargetLineIndex)?.text?.take(60) ?: "…"
                showSyncStatus("“$lineText”")
            }
        }
    }

    private fun setTapSyncActiveStyle(active: Boolean) {
        btnTapSync.backgroundTintList = ColorStateList.valueOf(if (active) SODIUM else LANE)
        btnTapSync.setTextColor(if (active) ON_SODIUM else HEADLIGHT)
    }

    private fun getCurrentRawPositionMs(): Long {
        return try {
            mediaTracker.getCurrentPositionMs() - mediaTracker.state.value.offsetMs
        } catch (_: Exception) { 0L }
    }

    private val hideSyncStatusRunnable = Runnable {
        if (!tapSyncActive) {
            tvSyncStatus.visibility = View.GONE
        }
    }

    private fun showSyncStatus(text: String) {
        tvSyncStatus.text = text
        tvSyncStatus.visibility = View.VISIBLE
        handler.removeCallbacks(hideSyncStatusRunnable)
        handler.postDelayed(hideSyncStatusRunnable, 8000)
    }

    private fun formatOffset(ms: Long): String = when {
        ms > 0 -> "+$ms ms"
        ms < 0 -> "−${-ms} ms"
        else -> "0 ms"
    }

    private fun startPlainScroll(state: LyricsState) {
        val durationMs = state.track?.durationMs ?: 0
        if (durationMs <= 0 || state.lines.isEmpty()) {
            scrollView.scrollTo(0, 0)
            return
        }

        val trackTitle = state.track?.title
        val isNewTrack = trackTitle != lastPlainTrackTitle
        lastPlainTrackTitle = trackTitle

        tvLyrics.post {
            val maxScroll = tvLyrics.height - scrollView.height
            if (maxScroll <= 0) return@post

            val posMs = try {
                mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
            } catch (_: Exception) { 0L }
            val remainingMs = (durationMs - posMs).coerceAtLeast(0)
            val startFraction = posMs.toFloat() / durationMs
            val startScrollY = (startFraction * maxScroll).toInt().coerceIn(0, maxScroll)

            if (isNewTrack || !userScrolling) {
                scrollView.scrollTo(0, startScrollY)
            }

            plainScrollAnimator?.cancel()
            plainScrollAnimator = ValueAnimator.ofInt(startScrollY, maxScroll).apply {
                duration = remainingMs
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    if (!userScrolling) {
                        scrollView.scrollTo(0, anim.animatedValue as Int)
                    }
                }
                start()
                if (!state.isPlaying) pause()
            }
        }
    }

    private fun stopPlainScroll() {
        plainScrollAnimator?.cancel()
        plainScrollAnimator = null
        lastPlainTrackTitle = null
    }

    companion object {
        private const val COUNTDOWN_DOTS = 3
        private const val DEFAULT_FONT_SIZE = 20
        private const val MIN_FONT_SIZE = 14
        private const val MAX_FONT_SIZE = 34

        // Night-drive palette (mirrors res/values/colors.xml)
        private val NIGHT = Color.parseColor("#151726")
        private val NIGHT_DEEP = Color.parseColor("#0E0F1C")
        private val LANE = Color.parseColor("#2B2F45")
        private val HEADLIGHT = Color.parseColor("#F4EFE6")
        private val DUSK = Color.parseColor("#9A9DB5")
        private val SODIUM = Color.parseColor("#FFB547")
        private val ON_SODIUM = Color.parseColor("#2A1A00")

        private fun withAlpha(color: Int, alpha: Float): Int {
            val a = (alpha * 255).toInt().coerceIn(0, 255)
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }
    }
}
