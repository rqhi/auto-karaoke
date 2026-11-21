package com.autolyrics.spotify

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Spotify Web API: what is playing on the user's account, on any device (phone,
 * car, speaker), and the player controls. Sign-in is Authorization Code with PKCE,
 * so the app only needs a client ID, no secret. See [SpotifyLogin].
 *
 * Every failure returns null / false; the caller falls back to the phone's player.
 */
object SpotifyClient {

    private const val TAG = "Spotify"

    /** Spotify accepts HTTP redirects only on loopback IPs; [SpotifyLogin] listens here. */
    const val REDIRECT_PORT = 43821
    const val REDIRECT_URI = "http://127.0.0.1:$REDIRECT_PORT/callback"

    const val PREF_ENABLED = "spotify_sync_enabled"
    const val PREF_CLIENT_ID = "spotify_client_id"
    private const val PREF_ACCESS_TOKEN = "spotify_access_token"
    private const val PREF_REFRESH_TOKEN = "spotify_refresh_token"
    private const val PREF_EXPIRES_AT = "spotify_token_expires_at_ms"

    private const val SCOPES =
        "user-read-currently-playing user-read-playback-state user-modify-playback-state"
    private const val ACCOUNTS_URL = "https://accounts.spotify.com"
    private const val API_URL = "https://api.spotify.com/v1"

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val tokenLock = Any()

    /**
     * A snapshot of the user's playback. [progressMs] was the position at
     * [atElapsedMs] (SystemClock.elapsedRealtime), the middle of the request.
     */
    data class Playback(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val progressMs: Long,
        val isPlaying: Boolean,
        val imageUrl: String?,
        val atElapsedMs: Long
    )

    fun isConnected(prefs: SharedPreferences): Boolean =
        prefs.getString(PREF_REFRESH_TOKEN, null) != null

    fun disconnect(prefs: SharedPreferences) {
        prefs.edit()
            .remove(PREF_ACCESS_TOKEN)
            .remove(PREF_REFRESH_TOKEN)
            .remove(PREF_EXPIRES_AT)
            .apply()
    }

    // --- Sign-in (PKCE) ---

    fun newVerifier(): String {
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        return base64Url(bytes)
    }

    fun authorizeUrl(clientId: String, verifier: String, state: String): String {
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return "$ACCOUNTS_URL/authorize?response_type=code" +
            "&client_id=${enc(clientId)}" +
            "&scope=${enc(SCOPES)}" +
            "&redirect_uri=${enc(REDIRECT_URI)}" +
            "&code_challenge_method=S256&code_challenge=$challenge" +
            "&state=${enc(state)}"
    }

    fun exchangeCode(prefs: SharedPreferences, clientId: String, code: String, verifier: String): Boolean =
        requestToken(
            prefs,
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", clientId)
                .add("code_verifier", verifier)
                .build()
        )

    private fun refresh(prefs: SharedPreferences): Boolean {
        val refreshToken = prefs.getString(PREF_REFRESH_TOKEN, null) ?: return false
        val clientId = prefs.getString(PREF_CLIENT_ID, null) ?: return false
        return requestToken(
            prefs,
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .build()
        )
    }

    private fun requestToken(prefs: SharedPreferences, body: FormBody): Boolean {
        val request = Request.Builder().url("$ACCOUNTS_URL/api/token").post(body).build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "token ${response.code}: $text")
                    // A revoked or unknown refresh token won't recover; sign in again.
                    if (response.code == 400 && text.contains("invalid_grant")) disconnect(prefs)
                    return false
                }
                val json = JsonParser.parseString(text).asJsonObject
                val editor = prefs.edit()
                    .putString(PREF_ACCESS_TOKEN, json.get("access_token").asString)
                    .putLong(
                        PREF_EXPIRES_AT,
                        System.currentTimeMillis() + json.get("expires_in").asLong * 1000
                    )
                // Refreshes may or may not hand out a new refresh token.
                json.get("refresh_token")?.takeIf { !it.isJsonNull }?.let {
                    editor.putString(PREF_REFRESH_TOKEN, it.asString)
                }
                editor.apply()
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "token request failed: ${e.message}")
            false
        }
    }

    private fun accessToken(prefs: SharedPreferences): String? = synchronized(tokenLock) {
        if (!isConnected(prefs)) return null
        val expiresAt = prefs.getLong(PREF_EXPIRES_AT, 0L)
        if (System.currentTimeMillis() > expiresAt - 60_000 && !refresh(prefs)) return null
        prefs.getString(PREF_ACCESS_TOKEN, null)
    }

    // --- Playback ---

    /** The track playing on the account, or null when nothing (or no song) is playing. */
    fun currentlyPlaying(prefs: SharedPreferences): Playback? {
        val token = accessToken(prefs) ?: return null
        val request = Request.Builder()
            .url("$API_URL/me/player/currently-playing")
            .header("Authorization", "Bearer $token")
            .build()
        return try {
            val sentAt = SystemClock.elapsedRealtime()
            client.newCall(request).execute().use { response ->
                val receivedAt = SystemClock.elapsedRealtime()
                if (response.code == 204) return null
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "currently-playing ${response.code}: $text")
                    if (response.code == 401) prefs.edit().putLong(PREF_EXPIRES_AT, 0L).apply()
                    return null
                }
                parsePlayback(JsonParser.parseString(text).asJsonObject, (sentAt + receivedAt) / 2)
            }
        } catch (e: Exception) {
            Log.w(TAG, "currently-playing failed: ${e.message}")
            null
        }
    }

    private fun parsePlayback(json: JsonObject, atElapsedMs: Long): Playback? {
        if (json.get("currently_playing_type")?.asString != "track") return null
        val item = json.get("item")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val album = item.getAsJsonObject("album")
        // Images come largest first; the middle one (~300 px) is plenty for the car.
        val images = album?.getAsJsonArray("images")
        val image = images?.let { it.get(minOf(1, it.size() - 1)) }?.asJsonObject
        return Playback(
            title = item.get("name").asString,
            artist = item.getAsJsonArray("artists").joinToString(", ") {
                it.asJsonObject.get("name").asString
            },
            album = album?.get("name")?.asString.orEmpty(),
            durationMs = item.get("duration_ms").asLong,
            progressMs = json.get("progress_ms")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
            isPlaying = json.get("is_playing").asBoolean,
            imageUrl = image?.get("url")?.asString,
            atElapsedMs = atElapsedMs
        )
    }

    fun loadImage(url: String): Bitmap? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            response.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "cover failed: ${e.message}")
        null
    }

    // --- Player controls (Spotify Premium only) ---

    fun play(prefs: SharedPreferences) = command(prefs, "PUT", "play")
    fun pause(prefs: SharedPreferences) = command(prefs, "PUT", "pause")
    fun next(prefs: SharedPreferences) = command(prefs, "POST", "next")
    fun previous(prefs: SharedPreferences) = command(prefs, "POST", "previous")
    fun seek(prefs: SharedPreferences, positionMs: Long) =
        command(prefs, "PUT", "seek?position_ms=${positionMs.coerceAtLeast(0)}")

    private fun command(prefs: SharedPreferences, method: String, path: String): Boolean {
        val token = accessToken(prefs) ?: return false
        val request = Request.Builder()
            .url("$API_URL/me/player/$path")
            .header("Authorization", "Bearer $token")
            .method(method, ByteArray(0).toRequestBody())
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) Log.w(TAG, "$path ${response.code}: ${response.body?.string()}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "$path failed: ${e.message}")
            false
        }
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
