package com.autolyrics.lyrics

import android.content.SharedPreferences
import android.util.Log
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import com.autolyrics.model.TrackInfo
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Musixmatch lyrics through the anonymous mobile API (ported from the
 * whoxamxl/auto-lyrics fork).
 *
 *  1. token.get -> short-lived anonymous user token.
 *  2. macro.subtitles.get -> matched track + line-synced subtitle (+ RichSync).
 *  3. track.richsync.get -> word timings if the macro call left them out.
 *
 * This is an unofficial endpoint, so every failure just returns null.
 */
object MusixmatchClient {

    private const val TAG = "Musixmatch"
    private const val BASE_URL = "https://apic-appmobile.musixmatch.com/ws/1.1/"
    private const val APP_ID = "mac-ios-v2.0"
    private const val APP_VERSION = "10.1.1"
    private const val USER_AGENT = "Musixmatch/2025120901 CFNetwork/3860.300.31 Darwin/25.2.0"

    private const val TOKEN_PREF_KEY = "musixmatch_mobile_user_token"
    private const val TOKEN_TIME_PREF_KEY = "musixmatch_mobile_user_token_time_ms"
    private const val TOKEN_TTL_MS = 60_000L

    /** Same tolerance LRCLIB lookups use. */
    private const val MAX_DURATION_DIFF_SEC = 10.0
    private const val MIN_TITLE_SIMILARITY = 0.6

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val tokenLock = Any()
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedTokenAtMs = 0L

    data class MusixmatchResult(
        val lines: List<LyricLine>,
        val isRichSync: Boolean
    )

    private data class Candidate(
        val commonTrackId: Long?,
        val title: String,
        val artist: String,
        val durationSec: Double?,
        val hasRichSync: Boolean,
        val instrumental: Boolean
    )

    private data class MacroMatch(
        val candidate: Candidate,
        val subtitleBody: String,
        val richSyncJson: String?
    )

    fun getLyrics(track: TrackInfo, prefs: SharedPreferences? = null): MusixmatchResult? {
        if (track.title.isBlank()) return null

        val macro = authenticatedRequest("macro.subtitles.get", macroParams(track), prefs) ?: return null
        val match = parseMacro(macro) ?: return null
        val candidate = match.candidate

        if (candidate.instrumental || !matches(track, candidate)) {
            Log.d(TAG, "rejected '${candidate.title}' by '${candidate.artist}'")
            return null
        }

        if (candidate.hasRichSync) {
            val richSync = match.richSyncJson?.let(::parseRichSync).orEmpty().ifEmpty {
                candidate.commonTrackId?.let { id ->
                    authenticatedRequest(
                        "track.richsync.get",
                        linkedMapOf("commontrack_id" to id.toString()),
                        prefs
                    )?.let { parseRichSync(it.toString()) }
                }.orEmpty()
            }
            if (richSync.isNotEmpty()) return MusixmatchResult(richSync, isRichSync = true)
        }

        val synced = if (match.subtitleBody.isBlank()) emptyList()
        else LrcParser.parse(match.subtitleBody).filter { it.timeMs >= 0L }.distinctBy { it.timeMs to it.text }
        return if (synced.isNotEmpty()) MusixmatchResult(synced, isRichSync = false) else null
    }

    private fun macroParams(track: TrackInfo): LinkedHashMap<String, String> {
        val params = linkedMapOf(
            "namespace" to "lyrics_richsynched",
            "optional_calls" to "track.richsync",
            "subtitle_format" to "lrc",
            "q_artist" to track.artist,
            "q_track" to track.title
        )
        if (track.album.isNotBlank()) params["q_album"] = track.album
        if (track.durationMs > 0L) params["q_duration"] = (track.durationMs / 1000.0).roundToLong().toString()
        return params
    }

    /** Guards against Musixmatch's matcher returning a different song. */
    private fun matches(track: TrackInfo, candidate: Candidate): Boolean {
        if (track.durationMs > 0 && candidate.durationSec != null &&
            abs(track.durationMs / 1000.0 - candidate.durationSec) > MAX_DURATION_DIFF_SEC
        ) return false

        if (similarity(track.title, candidate.title) < MIN_TITLE_SIMILARITY) return false

        if (track.artist.isNotBlank() && candidate.artist.isNotBlank()) {
            val a = normalize(track.artist)
            val b = normalize(candidate.artist)
            val shared = a.split(' ').toSet().intersect(b.split(' ').toSet()).any { it.length > 1 }
            if (!a.contains(b) && !b.contains(a) && !shared) return false
        }
        return true
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    /** Normalized Levenshtein similarity, 1.0 = identical. */
    private fun similarity(left: String, right: String): Double {
        val a = normalize(left)
        val b = normalize(right)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            cur.copyInto(prev)
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }

    // --- HTTP ---

    private fun authenticatedRequest(
        endpoint: String,
        params: LinkedHashMap<String, String>,
        prefs: SharedPreferences?
    ): JsonObject? {
        var token = getToken(prefs, force = false) ?: return null
        var response = request(endpoint, LinkedHashMap(params).apply { put("usertoken", token) })

        if (response.first == 401 || apiStatus(response.second) == 401) {
            invalidateToken(prefs)
            token = getToken(prefs, force = true) ?: return null
            response = request(endpoint, LinkedHashMap(params).apply { put("usertoken", token) })
        }
        return response.second?.takeIf { apiStatus(it) == 200 }
    }

    private fun getToken(prefs: SharedPreferences?, force: Boolean): String? = synchronized(tokenLock) {
        val now = System.currentTimeMillis()
        if (cachedToken == null && prefs != null) {
            cachedToken = prefs.getString(TOKEN_PREF_KEY, null)
            cachedTokenAtMs = prefs.getLong(TOKEN_TIME_PREF_KEY, 0L)
        }
        val memoryToken = cachedToken
        if (!force && !memoryToken.isNullOrBlank() && now - cachedTokenAtMs in 0 until TOKEN_TTL_MS) {
            return memoryToken
        }

        val json = request("token.get", linkedMapOf("user_language" to "en")).second
        val token = json?.takeIf { apiStatus(it) == 200 }
            ?.getAsJsonObject("message")?.getAsJsonObject("body")?.string("user_token")
            ?.takeIf { it.isNotBlank() && !it.startsWith("UpgradeOnly") }
        if (token != null) {
            cachedToken = token
            cachedTokenAtMs = now
            prefs?.edit()?.putString(TOKEN_PREF_KEY, token)?.putLong(TOKEN_TIME_PREF_KEY, now)?.apply()
            return token
        }
        return cachedToken
    }

    private fun invalidateToken(prefs: SharedPreferences?) {
        cachedToken = null
        cachedTokenAtMs = 0L
        prefs?.edit()?.remove(TOKEN_PREF_KEY)?.remove(TOKEN_TIME_PREF_KEY)?.apply()
    }

    /** Returns the HTTP code (-1 on network failure) and the parsed body. */
    private fun request(endpoint: String, params: LinkedHashMap<String, String>): Pair<Int, JsonObject?> {
        val all = linkedMapOf("app_id" to APP_ID, "format" to "json", "t" to System.currentTimeMillis().toString())
        all.putAll(params)
        val query = all.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

        val request = Request.Builder()
            .url("$BASE_URL$endpoint?$query")
            .header("x-mxm-app-version", APP_VERSION)
            .header("X-User-Agent", USER_AGENT)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val json = parseObject(response.body?.string().orEmpty())
                Log.d(TAG, "${response.code} api=${apiStatus(json)} $endpoint")
                response.code to json
            }
        } catch (e: Exception) {
            Log.d(TAG, "$endpoint failed: ${e.message}")
            -1 to null
        }
    }

    // --- Parsing ---

    private fun parseMacro(root: JsonObject): MacroMatch? {
        val calls = root.getAsJsonObject("message")?.getAsJsonObject("body")
            ?.getAsJsonObject("macro_calls") ?: return null

        val matcher = calls.getAsJsonObject("matcher.track.get")?.takeIf { apiStatus(it) == 200 } ?: return null
        val track = matcher.getAsJsonObject("message")?.getAsJsonObject("body")
            ?.getAsJsonObject("track") ?: return null
        val title = track.string("track_name").ifBlank { return null }

        val candidate = Candidate(
            commonTrackId = track.longOrNull("commontrack_id"),
            title = title,
            artist = track.string("artist_name"),
            durationSec = track.longOrNull("track_length")?.toDouble(),
            hasRichSync = track.longOrNull("has_richsync") == 1L,
            instrumental = track.longOrNull("instrumental") == 1L
        )

        val subtitleBody = calls.getAsJsonObject("track.subtitles.get")
            ?.takeIf { apiStatus(it) == 200 }
            ?.getAsJsonObject("message")?.getAsJsonObject("body")
            ?.getAsJsonArray("subtitle_list")?.firstOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
            ?.getAsJsonObject("subtitle")?.string("subtitle_body")
            .orEmpty()

        val richSyncJson = calls.getAsJsonObject("track.richsync.get")
            ?.takeIf { apiStatus(it) == 200 }?.toString()

        return MacroMatch(candidate, subtitleBody, richSyncJson)
    }

    /**
     * RichSync is a JSON array of lines: {"ts": start, "x": text, "l": [{"c": chunk, "o": offset}]}
     * with times in seconds and chunk offsets relative to the line start.
     */
    private fun parseRichSync(json: String): List<LyricLine> {
        val root = parseObject(json) ?: return emptyList()
        if (apiStatus(root) != 200) return emptyList()
        val body = deepFind(root, "richsync_body")?.asStringOrNull().orEmpty()
        if (body.isBlank()) return emptyList()

        return try {
            JsonParser.parseString(body).asJsonArray.mapNotNull { element ->
                val line = element.asJsonObject
                val startSec = line.doubleOrNull("ts") ?: return@mapNotNull null
                LyricLine(
                    timeMs = (startSec * 1000).roundToLong(),
                    text = line.string("x").ifBlank { "♪" },
                    words = parseRichSyncWords(line, startSec)
                )
            }
                .filter { it.timeMs >= 0L }
                .sortedBy { it.timeMs }
                .distinctBy { it.timeMs to it.text }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseRichSyncWords(line: JsonObject, lineStartSec: Double): List<LyricWord> {
        val chunks = line.getAsJsonArray("l") ?: return emptyList()
        val words = mutableListOf<LyricWord>()
        for (chunkElement in chunks) {
            val chunk = chunkElement.asJsonObject
            val text = chunk.string("c").trim()
            if (text.isBlank()) continue
            val timeMs = ((lineStartSec + (chunk.doubleOrNull("o") ?: 0.0)) * 1000).roundToLong()
            // Glue punctuation to the word before it so it isn't highlighted on its own.
            if (text.none { it.isLetterOrDigit() } && words.isNotEmpty()) {
                val previous = words.removeAt(words.lastIndex)
                words += previous.copy(text = previous.text + text)
            } else {
                words += LyricWord(timeMs, text)
            }
        }
        return words
    }

    private fun deepFind(element: JsonElement?, key: String): JsonElement? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            obj.get(key)?.let { return it }
            for ((_, value) in obj.entrySet()) deepFind(value, key)?.let { return it }
        } else if (element.isJsonArray) {
            for (value in element.asJsonArray) deepFind(value, key)?.let { return it }
        }
        return null
    }

    private fun parseObject(json: String): JsonObject? {
        if (json.isBlank() || json.trimStart().startsWith("<")) return null
        return try {
            JsonParser.parseString(json).asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun apiStatus(root: JsonObject?): Int? = try {
        root?.getAsJsonObject("message")?.getAsJsonObject("header")?.get("status_code")?.asInt
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.string(name: String): String = get(name)?.asStringOrNull().orEmpty()

    private fun JsonObject.longOrNull(name: String): Long? = try {
        get(name)?.takeUnless { it.isJsonNull }?.asLong
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.doubleOrNull(name: String): Double? = try {
        get(name)?.takeUnless { it.isJsonNull }?.asDouble
    } catch (_: Exception) {
        null
    }

    private fun JsonElement.asStringOrNull(): String? = try {
        if (isJsonNull) null else asString
    } catch (_: Exception) {
        null
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
