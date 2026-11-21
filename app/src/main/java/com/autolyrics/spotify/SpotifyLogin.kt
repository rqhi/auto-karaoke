package com.autolyrics.spotify

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.UUID

/**
 * Signs in to Spotify in the browser. Spotify redirects back to
 * [SpotifyClient.REDIRECT_URI] on this phone, where a one-off local server picks
 * up the code; the redirect URI must be registered exactly like that in the
 * Spotify developer dashboard.
 */
object SpotifyLogin {

    private const val TAG = "Spotify"
    private const val TIMEOUT_MS = 5 * 60_000

    /** Returns null when connected, or what went wrong. */
    suspend fun login(context: Context, prefs: SharedPreferences, clientId: String): String? {
        val verifier = SpotifyClient.newVerifier()
        val state = UUID.randomUUID().toString()

        val server = withContext(Dispatchers.IO) {
            try {
                ServerSocket(SpotifyClient.REDIRECT_PORT, 1, InetAddress.getByName("127.0.0.1"))
                    .apply { soTimeout = TIMEOUT_MS }
            } catch (e: Exception) {
                Log.w(TAG, "local server failed: ${e.message}")
                null
            }
        } ?: return "Couldn't listen on port ${SpotifyClient.REDIRECT_PORT}. Try again."

        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(SpotifyClient.authorizeUrl(clientId, verifier, state)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        val params = withContext(Dispatchers.IO) {
            server.use { waitForCallback(it) }
        } ?: return "Sign-in timed out."

        if (params["state"] != state) return "Sign-in didn't match. Try again."
        params["error"]?.let { return "Spotify said: $it" }
        val code = params["code"] ?: return "Spotify sent no code."

        val ok = withContext(Dispatchers.IO) {
            SpotifyClient.exchangeCode(prefs, clientId, code, verifier)
        }
        return if (ok) null else "Couldn't get a token. Check the client ID and redirect URI."
    }

    /** Waits for the browser's request to /callback and answers it with a short page. */
    private fun waitForCallback(server: ServerSocket): Map<String, String>? {
        while (true) {
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                return null
            }
            socket.use {
                val requestLine = it.getInputStream().bufferedReader().readLine().orEmpty()
                // "GET /callback?code=...&state=... HTTP/1.1"
                val target = requestLine.split(" ").getOrNull(1).orEmpty()
                val isCallback = target.startsWith("/callback")
                val page = if (isCallback) {
                    "<h2>Back to Auto Lyrics</h2><p>Spotify answered. The app shows whether sign-in worked.</p>"
                } else {
                    ""
                }
                val body = "<html><body style=\"font-family:sans-serif\">$page</body></html>"
                    .toByteArray()
                it.getOutputStream().apply {
                    write(
                        ("HTTP/1.1 ${if (isCallback) "200 OK" else "404 Not Found"}\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    write(body)
                    flush()
                }
                if (isCallback) return parseQuery(target.substringAfter('?', ""))
            }
        }
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
}
