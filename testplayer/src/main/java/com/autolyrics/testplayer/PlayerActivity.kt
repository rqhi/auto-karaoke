package com.autolyrics.testplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView

/** A list of buttons: one per test song, plus pause and skip ahead. */
class PlayerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }
        SONGS.forEachIndexed { i, song ->
            list.addView(button("▶  ${song.title} — ${song.artist}") { send("play") { putExtra("song", i) } })
        }
        list.addView(button("⏸  Pause") { send("pause") })
        list.addView(button("▶  Resume") { send("play") })
        list.addView(button("⏩  Jump to 1:00") { send("seek") { putExtra("pos", 60_000L) } })
        setContentView(ScrollView(this).apply { addView(list) })
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun send(action: String, extras: Intent.() -> Unit = {}) {
        startForegroundService(Intent(this, PlayerService::class.java).setAction(action).apply(extras))
    }
}
