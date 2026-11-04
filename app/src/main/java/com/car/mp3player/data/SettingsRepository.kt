package com.car.mp3player.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.car.mp3player.model.AppThemePreset
import com.car.mp3player.model.LibraryKind
import com.car.mp3player.model.LyricFontFamily
import com.car.mp3player.model.LyricThemePreset
import com.car.mp3player.model.PlaybackMode
import com.car.mp3player.model.ThemeMode
import com.car.mp3player.util.MediaPath

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var fontSizeSp: Float
        get() = prefs.getFloat(KEY_FONT_SIZE, 24f)
        set(value) = prefs.edit { putFloat(KEY_FONT_SIZE, value) }

    var playerFontSizeSp: Float
        get() = prefs.getFloat(KEY_PLAYER_FONT_SIZE, 20f)
        set(value) = prefs.edit { putFloat(KEY_PLAYER_FONT_SIZE, value) }

    var playerNextFontSizeSp: Float
        get() = prefs.getFloat(KEY_PLAYER_NEXT_FONT_SIZE, 17f)
        set(value) = prefs.edit { putFloat(KEY_PLAYER_NEXT_FONT_SIZE, value) }

    /** 播放页歌词行距系数：2.4 表示单行占 2.4 倍字号的垂直空间（越小越紧凑） */
    var playerLyricLineSpacing: Float
        get() = prefs.getFloat(KEY_PLAYER_LINE_SPACING, 2.4f).coerceIn(1.4f, 4.0f)
        set(value) = prefs.edit { putFloat(KEY_PLAYER_LINE_SPACING, value.coerceIn(1.4f, 4.0f)) }

    var currentLineScale: Float
        get() = prefs.getFloat(KEY_CURRENT_SCALE, 1.05f)
        set(value) = prefs.edit { putFloat(KEY_CURRENT_SCALE, value) }

    var nextLineScale: Float
        get() = prefs.getFloat(KEY_NEXT_SCALE, 0.92f)
        set(value) = prefs.edit { putFloat(KEY_NEXT_SCALE, value) }

    var maxLyricVisualLines: Int
        get() = prefs.getInt(KEY_MAX_VISUAL_LINES, 2).coerceIn(1, 4)
        set(value) = prefs.edit { putInt(KEY_MAX_VISUAL_LINES, value.coerceIn(1, 4)) }

    var smoothLyrics: Boolean
        get() = prefs.getBoolean(KEY_SMOOTH_LYRICS, true)
        set(value) = prefs.edit { putBoolean(KEY_SMOOTH_LYRICS, value) }

    var highlightColor: Int
        get() = prefs.getInt(KEY_HIGHLIGHT, LyricThemePreset.NETEASE.highlightColor)
        set(value) = prefs.edit { putInt(KEY_HIGHLIGHT, value) }

    var pendingColor: Int
        get() = prefs.getInt(KEY_PENDING, LyricThemePreset.NETEASE.pendingColor)
        set(value) = prefs.edit { putInt(KEY_PENDING, value) }

    var nextLineColor: Int
        get() = prefs.getInt(KEY_NEXT, LyricThemePreset.NETEASE.nextLineColor)
        set(value) = prefs.edit { putInt(KEY_NEXT, value) }

    var overlayPosition: Int
        get() = prefs.getInt(KEY_POSITION, POSITION_CENTER)
        set(value) = prefs.edit { putInt(KEY_POSITION, value) }

    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, true)
        set(value) = prefs.edit { putBoolean(KEY_OVERLAY, value) }

    var autoResumePlayback: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESUME, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_RESUME, value) }

    var bootAutoStart: Boolean
        get() = prefs.getBoolean(KEY_BOOT_AUTO_START, false)
        set(value) = prefs.edit { putBoolean(KEY_BOOT_AUTO_START, value) }

    var bootOpenApp: Boolean
        get() = prefs.getBoolean(KEY_BOOT_OPEN_APP, false)
        set(value) = prefs.edit { putBoolean(KEY_BOOT_OPEN_APP, value) }

    var bootReturnHome: Boolean
        get() = prefs.getBoolean(KEY_BOOT_RETURN_HOME, true)
        set(value) = prefs.edit { putBoolean(KEY_BOOT_RETURN_HOME, value) }

    var overlayLyricBold: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_BOLD, false)
        set(value) = prefs.edit { putBoolean(KEY_OVERLAY_BOLD, value) }

    var overlayStrokeEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_STROKE, true)
        set(value) = prefs.edit { putBoolean(KEY_OVERLAY_STROKE, value) }

    var overlayStrokeWidth: Int
        get() = prefs.getInt(KEY_OVERLAY_STROKE_WIDTH, 3).coerceIn(1, 10)
        set(value) = prefs.edit { putInt(KEY_OVERLAY_STROKE_WIDTH, value.coerceIn(1, 10)) }

    var playMode: PlaybackMode
        // Guarded: a stored ordinal can fall outside the enum after a version change,
        // and this getter is read on the main thread before the UI exists.
        get() = PlaybackMode.entries.getOrElse(
            prefs.getInt(KEY_PLAY_MODE, PlaybackMode.SHUFFLE.ordinal)
        ) { PlaybackMode.SHUFFLE }
        set(value) = prefs.edit { putInt(KEY_PLAY_MODE, value.ordinal) }

    var clusterLyricsEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLUSTER_LYRICS, false)
        set(value) = prefs.edit { putBoolean(KEY_CLUSTER_LYRICS, value) }

    var onlineLyricsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ONLINE_LYRICS, true)
        set(value) = prefs.edit { putBoolean(KEY_ONLINE_LYRICS, value) }

    var onlineCoverEnabled: Boolean
        get() = prefs.getBoolean(KEY_ONLINE_COVER, true)
        set(value) = prefs.edit { putBoolean(KEY_ONLINE_COVER, value) }

    var skipUnplayableVip: Boolean
        get() = prefs.getBoolean(KEY_SKIP_VIP, false)
        set(value) = prefs.edit { putBoolean(KEY_SKIP_VIP, value) }

    var podcastShowDescription: Boolean
        get() = prefs.getBoolean(KEY_PODCAST_DESC, true)
        set(value) = prefs.edit { putBoolean(KEY_PODCAST_DESC, value) }

    var onlineMusicApiUrl: String
        get() = prefs.getString(KEY_ONLINE_API, DEFAULT_ONLINE_API) ?: DEFAULT_ONLINE_API
        set(value) = prefs.edit { putString(KEY_ONLINE_API, value.trim()) }

    var onlineMusicApiBackup: String
        get() = prefs.getString(KEY_ONLINE_API_BACKUP, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ONLINE_API_BACKUP, value.trim()) }

    var onlineMusicQuality: Int
        get() = prefs.getInt(KEY_ONLINE_QUALITY, 320)
        set(value) = prefs.edit { putInt(KEY_ONLINE_QUALITY, value) }

    var radioBrowserApiUrl: String
        get() = prefs.getString(KEY_RADIO_API, DEFAULT_RADIO_API) ?: DEFAULT_RADIO_API
        set(value) = prefs.edit { putString(KEY_RADIO_API, value.trim()) }

    var podcastRssText: String
        get() = prefs.getString(KEY_PODCAST_RSS, DEFAULT_PODCAST_RSS) ?: DEFAULT_PODCAST_RSS
        set(value) = prefs.edit { putString(KEY_PODCAST_RSS, value) }

    var lastActiveLibrary: LibraryKind
        get() = runCatching {
            LibraryKind.valueOf(prefs.getString(KEY_LAST_ACTIVE_LIB, LibraryKind.MUSIC.name)!!)
        }.getOrDefault(LibraryKind.MUSIC)
        set(value) = prefs.edit { putString(KEY_LAST_ACTIVE_LIB, value.name) }

    var lastSongPath: String?
        get() = lastSongPath(lastActiveLibrary)
        set(value) = setLastSong(lastActiveLibrary, value, lastPositionMs)

    var lastPositionMs: Long
        get() = lastPositionMs(lastActiveLibrary)
        set(value) = setLastSong(lastActiveLibrary, lastSongPath, value)

    fun lastSongPath(library: LibraryKind): String? =
        prefs.getString(lastSongKey(library), null) ?: migrateLegacyLastSong(library)

    fun lastPositionMs(library: LibraryKind): Long =
        prefs.getLong(lastPositionKey(library), 0L)

    fun setLastSong(library: LibraryKind, path: String?, positionMs: Long) {
        prefs.edit {
            if (path == null) remove(lastSongKey(library)) else putString(lastSongKey(library), path)
            putLong(lastPositionKey(library), positionMs.coerceAtLeast(0L))
            putString(KEY_LAST_ACTIVE_LIB, library.name)
        }
    }

    fun lyricSearchTitle(path: String): String? =
        prefs.getString(lyricTitleKey(path), null)

    fun lyricSearchArtist(path: String): String? =
        prefs.getString(lyricArtistKey(path), null)

    fun setLyricSearchOverride(path: String, title: String, artist: String) {
        prefs.edit {
            putString(lyricTitleKey(path), title.trim())
            putString(lyricArtistKey(path), artist.trim())
        }
    }

    fun clearLyricSearchOverride(path: String) {
        prefs.edit {
            remove(lyricTitleKey(path))
            remove(lyricArtistKey(path))
        }
    }

    var themeMode: ThemeMode
        get() = ThemeMode.entries.getOrElse(
            prefs.getInt(KEY_THEME, ThemeMode.SYSTEM.ordinal)
        ) { ThemeMode.SYSTEM }
        set(value) = prefs.edit { putInt(KEY_THEME, value.ordinal) }

    fun appTheme(): AppThemePreset = AppThemePreset.fromId(
        prefs.getString(KEY_APP_THEME, AppThemePreset.NETEASE.id) ?: AppThemePreset.NETEASE.id
    )

    fun setAppTheme(preset: AppThemePreset) {
        prefs.edit { putString(KEY_APP_THEME, preset.id) }
    }

    fun lyricTheme(): LyricThemePreset = LyricThemePreset.fromId(
        prefs.getString(KEY_LYRIC_THEME, LyricThemePreset.NETEASE.id) ?: LyricThemePreset.NETEASE.id
    )

    fun lyricFontFamily(): LyricFontFamily = LyricFontFamily.fromId(
        prefs.getString(KEY_LYRIC_FONT, LyricFontFamily.DEFAULT.id) ?: LyricFontFamily.DEFAULT.id
    )

    fun setLyricTheme(preset: LyricThemePreset) {
        prefs.edit {
            putString(KEY_LYRIC_THEME, preset.id)
            putInt(KEY_HIGHLIGHT, preset.highlightColor)
            putInt(KEY_PENDING, preset.pendingColor)
            putInt(KEY_NEXT, preset.nextLineColor)
            putFloat(KEY_PLAYER_FONT_SIZE, preset.playerCurrentSizeSp)
            putFloat(KEY_PLAYER_NEXT_FONT_SIZE, preset.playerNextSizeSp)
            putFloat(KEY_FONT_SIZE, preset.overlaySizeSp)
        }
    }

    fun setLyricFontFamily(family: LyricFontFamily) {
        prefs.edit { putString(KEY_LYRIC_FONT, family.id) }
    }

    var scanPathsText: String
        get() = prefs.getString(KEY_SCAN_PATHS, DEFAULT_SCAN_PATHS) ?: DEFAULT_SCAN_PATHS
        set(value) = prefs.edit { putString(KEY_SCAN_PATHS, value) }

    var scanTreeUrisText: String
        get() = prefs.getString(KEY_SCAN_TREE_URIS, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SCAN_TREE_URIS, value) }

    fun scanPaths(): List<String> =
        scanPathsText.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun scanTreeUris(): List<String> =
        scanTreeUrisText.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun allScanEntries(): List<String> = scanPaths() + scanTreeUris()

    fun podcastRssUrls(): List<String> {
        val custom = podcastRssText.lines().map { it.trim() }.filter { it.startsWith("http") }
        return custom.ifEmpty { PodcastDefaults.feedUrls() }
    }

    fun inferLibrary(path: String?): LibraryKind = MediaPath.libraryKind(path)

    fun addScanPath(path: String) {
        val paths = scanPaths().toMutableSet()
        if (paths.add(path)) scanPathsText = paths.joinToString("\n")
    }

    fun addScanTreeUri(uri: String) {
        val uris = scanTreeUris().toMutableSet()
        if (uris.add(uri)) scanTreeUrisText = uris.joinToString("\n")
    }

    fun removeScanEntry(entry: String) {
        if (entry.startsWith("content://")) {
            val uris = scanTreeUris().toMutableSet()
            uris.remove(entry)
            scanTreeUrisText = uris.joinToString("\n")
        } else {
            val paths = scanPaths().toMutableSet()
            paths.remove(entry)
            scanPathsText = paths.joinToString("\n")
        }
    }

    fun applyTheme() {
        val mode = when (themeMode) {
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun migrateLegacyLastSong(library: LibraryKind): String? {
        if (library != LibraryKind.MUSIC) return null
        val legacy = prefs.getString(KEY_LAST_SONG_LEGACY, null) ?: return null
        setLastSong(LibraryKind.MUSIC, legacy, prefs.getLong(KEY_LAST_POSITION_LEGACY, 0L))
        return legacy
    }

    private fun lastSongKey(library: LibraryKind): String = when (library) {
        LibraryKind.MUSIC -> KEY_LAST_SONG_MUSIC
        LibraryKind.ONLINE -> KEY_LAST_SONG_ONLINE
        LibraryKind.RADIO -> KEY_LAST_SONG_RADIO
        LibraryKind.PODCAST -> KEY_LAST_SONG_PODCAST
    }

    private fun lastPositionKey(library: LibraryKind): String = when (library) {
        LibraryKind.MUSIC -> KEY_LAST_POS_MUSIC
        LibraryKind.ONLINE -> KEY_LAST_POS_ONLINE
        LibraryKind.RADIO -> KEY_LAST_POS_RADIO
        LibraryKind.PODCAST -> KEY_LAST_POS_PODCAST
    }

    private fun lyricTitleKey(path: String) = "lyric_title_${path.hashCode()}"
    private fun lyricArtistKey(path: String) = "lyric_artist_${path.hashCode()}"

    companion object {
        const val PREFS_NAME = "mp3_player_settings"
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_PLAYER_FONT_SIZE = "player_font_size"
        const val KEY_PLAYER_NEXT_FONT_SIZE = "player_next_font_size"
        const val KEY_PLAYER_LINE_SPACING = "player_line_spacing"
        const val KEY_CURRENT_SCALE = "current_line_scale"
        const val KEY_NEXT_SCALE = "next_line_scale"
        const val KEY_MAX_VISUAL_LINES = "max_visual_lines"
        const val KEY_SMOOTH_LYRICS = "smooth_lyrics"
        const val KEY_HIGHLIGHT = "highlight_color"
        const val KEY_PENDING = "pending_color"
        const val KEY_NEXT = "next_line_color"
        const val KEY_POSITION = "overlay_position"
        const val KEY_OVERLAY = "overlay_enabled"
        const val KEY_AUTO_RESUME = "auto_resume"
        const val KEY_BOOT_AUTO_START = "boot_auto_start"
        const val KEY_BOOT_OPEN_APP = "boot_open_app"
        const val KEY_BOOT_RETURN_HOME = "boot_return_home"
        const val KEY_OVERLAY_BOLD = "overlay_lyric_bold"
        const val KEY_OVERLAY_STROKE = "overlay_stroke_enabled"
        const val KEY_OVERLAY_STROKE_WIDTH = "overlay_stroke_width"
        const val KEY_PLAY_MODE = "play_mode"
        const val KEY_CLUSTER_LYRICS = "cluster_lyrics"
        const val KEY_THEME = "theme_mode"
        const val KEY_APP_THEME = "app_theme"
        const val KEY_LYRIC_THEME = "lyric_theme"
        const val KEY_LYRIC_FONT = "lyric_font"
        const val KEY_LAST_SONG_LEGACY = "last_song_path"
        const val KEY_LAST_POSITION_LEGACY = "last_position_ms"
        const val KEY_LAST_SONG_MUSIC = "last_song_music"
        const val KEY_LAST_POS_MUSIC = "last_pos_music"
        const val KEY_LAST_SONG_ONLINE = "last_song_online"
        const val KEY_LAST_POS_ONLINE = "last_pos_online"
        const val KEY_LAST_SONG_RADIO = "last_song_radio"
        const val KEY_LAST_POS_RADIO = "last_pos_radio"
        const val KEY_LAST_SONG_PODCAST = "last_song_podcast"
        const val KEY_LAST_POS_PODCAST = "last_pos_podcast"
        const val KEY_LAST_ACTIVE_LIB = "last_active_library"
        const val KEY_SCAN_PATHS = "scan_paths"
        const val KEY_SCAN_TREE_URIS = "scan_tree_uris"
        const val KEY_ONLINE_LYRICS = "online_lyrics"
        const val KEY_ONLINE_COVER = "online_cover"
        const val KEY_SKIP_VIP = "skip_unplayable_vip"
        const val KEY_PODCAST_DESC = "podcast_show_desc"
        const val KEY_ONLINE_API = "online_music_api"
        const val KEY_ONLINE_API_BACKUP = "online_music_api_backup"
        const val KEY_ONLINE_QUALITY = "online_music_quality"
        const val KEY_RADIO_API = "radio_browser_api"
        const val KEY_PODCAST_RSS = "podcast_rss_urls"

        const val POSITION_TOP = 0
        const val POSITION_CENTER = 1
        const val POSITION_BOTTOM = 2

        const val DEFAULT_SCAN_PATHS = "/sdcard/Music\n/storage/emulated/0/Music"
        const val DEFAULT_ONLINE_API = "https://music-api.gdstudio.xyz/api.php"
        const val DEFAULT_RADIO_API = "https://de1.api.radio-browser.info"
        val DEFAULT_PODCAST_RSS = PodcastDefaults.defaultRssText()
    }
}
