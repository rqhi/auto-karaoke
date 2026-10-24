# Auto Lyrics — Development Reference

## Terminology

| Term | Meaning |
|---|---|
| **Full lyrics view** (AA) | The scrollable browse-tree list of lyric lines on Android Auto, powered by `onLoadChildren()`. Shows a windowed subset of lines. |
| **Fast-updating screen** / **Now-playing card** (AA) | The Android Auto now-playing card. Displays title, artist, album art, and a subtitle (current lyric) via `MediaMetadataCompat`. Updated every 300ms. |
| **Small fast-updating screen** | The now-playing card when AA is in the *minimized* container (e.g. maps in the larger panel). |
| **Large fast-updating screen** | The now-playing card when it fills the larger AA container. |
| **Performance Mode** (Phone) | A separate full-screen immersive activity with large karaoke text and word pop-out. |
| **Karaoke** | Word-level timestamps (ELRC format). On the phone, the current word is highlighted; on AA, shown via bracket notation `【word】`. |
| **Synced lyrics** | Line-level timestamps (standard LRC). `LyricsStatus.FOUND`. |
| **Plain lyrics** / **Unsynced** | No timestamps. `LyricsStatus.PLAIN_ONLY`. Scrolled proportionally based on song duration. |
| **SyncLRC** | Primary lyrics API: `synclrc.tharuk.pro`. Provides karaoke, synced, and plain lyrics. |
| **LRCLIB** | Fallback lyrics API: `lrclib.net`. Provides synced and plain lyrics. |
| **Musixmatch** | Unofficial anonymous mobile API (`apic-appmobile.musixmatch.com`). Provides RichSync (word timing) and line-synced lyrics. |
| **Player image** (AA) | The bitmap sent as album art: song + artist at the top, the previous lyric line below. Fills the slot where the full-screen player would draw a placeholder. |
| **AA offset** | A separate lyrics delay setting specific to Android Auto, stored as `aa_offset_ms` in SharedPreferences. |
| **Phone sync** | The global lyrics offset applied on the phone, managed by `MediaTracker.offsetMs`. |

## Architecture

```
AutoLyricsApp (Application)
  └─ MediaTracker (singleton) — playback tracking, lyrics fetching, state emission
       ├─ MediaListenerService (NotificationListenerService) — intercepts media notifications
       ├─ SyncLrcClient / LrcLibClient — lyrics API clients
       ├─ LrcParser — LRC/ELRC parsing
       ├─ MetadataCleaner — cleans track metadata for API queries
       ├─ LyricsCache — in-memory + background refresh + queue pre-fetch
       └─ AlbumColorExtractor — Palette API color extraction

  Phone UI
  ├─ MainActivity — main lyrics view, settings, sync tools
  └─ PerformanceActivity — full-screen immersive karaoke view

  Android Auto
  ├─ LyricsBrowserService (MediaBrowserServiceCompat)
  │   ├─ Browse tree (onLoadChildren) — flat single-level: track header + windowed lyrics + sync controls
  │   ├─ MediaSession — metadata (title, art, subtitle), playback state
  │   ├─ Karaoke bracket builder — buildKaraokeText()
  │   └─ Transport controls proxy — forwards play/pause/seek to source player
  └─ BootReceiver — starts service on boot/package replace
```

## File Map

### Kotlin Sources (`app/src/main/java/com/autolyrics/`)

| File | Purpose |
|---|---|
| `AutoLyricsApp.kt` | Application class. Initializes `MediaTracker`, starts `LyricsBrowserService`. |
| `MainActivity.kt` | Phone UI: lyrics display, font/delay settings, tap-to-sync, audio sync, scroll handling, theme colors. |
| `PerformanceActivity.kt` | Full-screen immersive karaoke mode with large text and word pop-out effect. |
| `model/Models.kt` | Data classes: `TrackInfo`, `LyricWord`, `LyricLine`, `AlbumColors`, `LyricsStatus`, `LyricsState`. |
| `media/MediaTracker.kt` | Singleton. Tracks active media session, fetches lyrics, manages offset, emits `StateFlow<LyricsState>`. |
| `media/MediaListenerService.kt` | `NotificationListenerService` — detects media sessions. |
| `lyrics/SyncLrcClient.kt` | HTTP client for SyncLRC API (primary source). |
| `lyrics/LrcLibClient.kt` | HTTP client for LRCLIB API (fallback source). |
| `lyrics/MusixmatchClient.kt` | Musixmatch mobile API: token, macro.subtitles.get, RichSync. Ported from the whoxamxl/auto-lyrics fork. |
| `lyrics/LrcParser.kt` | Parses LRC and ELRC format strings into `LyricLine` lists. |
| `lyrics/MetadataCleaner.kt` | Normalizes track/artist strings for API queries. |
| `lyrics/LyricsCache.kt` | In-memory lyrics cache with background refresh and queue pre-fetching. |
| `util/AlbumColorExtractor.kt` | Extracts `AlbumColors` from album art bitmaps using AndroidX Palette. |
| `util/AudioSyncHelper.kt` | Audio-based auto-sync using `SpeechRecognizer`. Calculates offset from recognized speech. |
| `auto/LyricsBrowserService.kt` | Android Auto integration. Browse tree, MediaSession metadata/playback, karaoke brackets, transport proxy. |
| `auto/BootReceiver.kt` | Starts `LyricsBrowserService` on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`. |

### Resources (`app/src/main/res/`)

| File | Purpose |
|---|---|
| `layout/activity_main.xml` | Phone main UI layout. |
| `layout/activity_performance.xml` | Full-screen performance mode layout. |
| `xml/automotive_app_desc.xml` | Android Auto app descriptor. |
| `values/themes.xml` | App theme (Material dark, no action bar). |
| `values/colors.xml` | Color resources. |
| `values/strings.xml` | String resources. |
| `drawable/` | Icons, backgrounds, shapes. |

### Key Configuration

| File | Purpose |
|---|---|
| `app/build.gradle.kts` | Dependencies, SDK versions, signing config, version management. |
| `AndroidManifest.xml` | Components, permissions, AA metadata. |
| `.github/workflows/build.yml` | CI: build APK, create GitHub release with versioned filename. |
| `app/signing.p12` | Shared signing keystore (avoids version conflicts across CI runs). |

## Key Constants (`LyricsBrowserService`)

| Constant | Value | Purpose |
|---|---|---|
| `WINDOW_SIZE` | 3 | Number of synced lyric lines in the AA browse tree window. |
| `PLAIN_WINDOW_SIZE` | 4 | Number of unsynced lyric lines in the AA browse tree window. |
| `PAD_WIDTH` | 60 | Character padding for browse tree items (reduces choppiness). |
| `NOTIFY_THROTTLE_MS` | 500ms | Minimum interval between browse tree refreshes. |
| `BROWSE_KARAOKE_WINDOW_MS` | 600ms | Karaoke bracket time window for browse tree items. |
| `SUBTITLE_KARAOKE_WINDOW_MS` | 300ms | Karaoke bracket time window for now-playing subtitle. |
| `SESSION_REFRESH_MS` | 1500ms | Periodic MediaSession playback state refresh interval. |
| `PLAIN_LOOP_DELAY_MS` | 2000ms | Plain lyrics browse tree periodic advance interval. |

## SharedPreferences Keys (`auto_lyrics_prefs`)

| Key | Type | Default | Purpose |
|---|---|---|---|
| `lyrics_font_size` | Int | 16 | Phone lyrics text size (sp). |
| `lyrics_font_family` | String | `sans-serif` | Phone lyrics font family. |
| `aa_karaoke_enabled` | Boolean | true | Toggle karaoke bracket display on AA. |
| `aa_offset_ms` | Long | 0 | Android Auto-specific lyrics delay (ms). |
| `aa_hide_controls` | Boolean | false | Hide play/pause/skip and the progress bar on the car's player and media card. |
| `musixmatch_enabled` | Boolean | true | Include Musixmatch in the lookup. |

## Karaoke Bracket Logic (AA)

The bracket display uses **monotonic advancement** to avoid visual jitter:
- Opening bracket `【` is placed at the current word index.
- Closing bracket `】` is placed at the last word within the karaoke window.
- Both `lastKaraokeWordIdx` and `endIdx` only advance forward within the same line.
- `resetKaraokeState()` is called on track change and line change to allow the brackets to start fresh.

## Lyrics Fetch Priority

1. A match the user picked with "Wrong lyrics?" (LRCLIB id).
2. **SyncLRC** karaoke (ELRC word timing).
3. **Musixmatch** RichSync or line-synced lyrics (if `musixmatch_enabled`).
4. **SyncLRC** synced/plain, then **LRCLIB** synced/plain.

## Android Auto screens

What Android Auto shows from the MediaSession, as tested on the Desktop Head Unit:

- The full-screen player and the small media card show only `DISPLAY_TITLE` and `DISPLAY_SUBTITLE`,
  one line each; line breaks are cut off. Album, description and queue title are not shown.
- Without album art the player draws an empty placeholder square. `ALBUM_ART` is therefore always
  the player image (transparent background), which removes the square and carries the extra lines.
- The small media card uses the same image as a dimmed, center-cropped background, so the
  previous line is drawn narrow and kept clear of the card's own two lines.
- Play/pause/skip disappear when the playback state offers no transport actions; the progress
  bar disappears without `METADATA_KEY_DURATION` and with an unknown position.

## Testing Android Auto on the PC

One-time setup (JDK 17, SDK, adb, Desktop Head Unit):

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\install-sdk.ps1
```

**With a phone over USB**

1. Phone: enable *USB debugging*; in Android Auto settings tap *Version* 10× → ⋮ → *Developer settings* → *Unknown sources*.
   Then ⋮ → *Start head unit server* (German: „Server für Haupteinheit starten“).
2. `.\tools\run.ps1 -Serial <serial>` builds, installs, restarts the app and tails its logs.
3. `.\tools\start-dhu.ps1 -Serial <serial>` opens the car screen (`-Config widescreen|portrait|default`).

**Without a phone**: `.\tools\start-emulator.ps1` boots the `AutoTest` AVD (Android 14 + Play Store).
Sign in, install Android Auto from the Play Store, then use the same steps with `-Serial emulator-5554`.

**Test Player** (`testplayer/`): a silent fake music app, so no music subscription or sign-in is needed.

```powershell
adb shell am start-foreground-service -n com.autolyrics.testplayer/.PlayerService -a play --ei song 0
adb shell am start-foreground-service -n com.autolyrics.testplayer/.PlayerService -a seek --el pos 60000
```

**Head unit console**: the DHU window's terminal accepts commands such as `screenshot out.png`,
`tap 640 360`, `day` / `night` and `help`.

**Debugging**: open the project in Android Studio and use *Attach Debugger to Android Process* →
`com.autolyrics`, or watch `adb logcat -s AutoLyricsAA Musixmatch`.

### 16 KB page size

Android 15+ devices may use 16 KB memory pages. The ML Kit native libraries are 16 KB-aligned ELF
files, and AGP 8.5.1+ stores them 16 KB-aligned in the APK. Check a build with build-tools 35+:

```powershell
zipalign -c -P 16 -v 4 app\build\outputs\apk\debug\app-debug.apk
```

To run it on 16 KB pages, use the `system-images;android-35;google_apis_ps16k;x86_64` emulator image.

### Probing the car screen

Debug builds accept a broadcast that replaces the media metadata until cleared, to see how
Android Auto lays out a field without changing code:

```powershell
adb shell "am broadcast -a com.autolyrics.DEBUG_METADATA --es display_title 'Big line' --es display_subtitle 'Small line' --es image_song 'Song' --es image_artist 'Artist' --es image_previous 'Previous line' --el duration 200000"
adb shell am broadcast -a com.autolyrics.DEBUG_METADATA --ez clear true
```

Other extras: `title`, `artist`, `album`, `display_description`, `queue_title`, `art` (real album art),
`actions` (playback actions bitmask).
