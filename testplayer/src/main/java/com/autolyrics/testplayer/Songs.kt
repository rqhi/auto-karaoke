package com.autolyrics.testplayer

data class Song(val title: String, val artist: String, val album: String, val durationMs: Long)

/** Songs with synced lyrics on LRCLIB, SyncLRC and Musixmatch, incl. one with pronunciation. */
val SONGS = listOf(
    Song("Bohemian Rhapsody", "Queen", "A Night at the Opera", 354_000),
    Song("Shape of You", "Ed Sheeran", "÷ (Deluxe)", 233_000),
    Song("Rolling in the Deep", "Adele", "21", 228_000),
    Song("Blinding Lights", "The Weeknd", "After Hours", 200_000),
    Song("Dynamite", "BTS", "BE", 199_000),
    Song("Lemon", "Kenshi Yonezu", "Lemon", 255_000)
)
