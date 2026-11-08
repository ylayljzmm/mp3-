package com.suisheng.player.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.suisheng.player.lrc.LyricProviderConfig
import com.suisheng.player.lrc.LyricProviders
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.LyricFontFamily
import com.suisheng.player.model.LyricThemePreset
import com.suisheng.player.model.PlaybackMode
import com.suisheng.player.model.ThemeAccent
import com.suisheng.player.theme.NightMode
import com.suisheng.player.theme.ThemeController
import com.suisheng.player.util.MediaPath
import java.util.Calendar

class SettingsRepository(context: Context) {
    /** Application Context 留一份给 [applyTheme]：它需要读资源数组算夜间区间。 */
    private val appContext: Context = context.applicationContext
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

    /**
     * 横屏双栏的「左右栏间距」（dp，范围 0~32，默认 8）。
     *
     * 管的是**中轴（黑胶 + 控件行）右边界到歌词文本起点**这段空白，映射到右栏
     * 歌词 View 的 `paddingStart`；`paddingEnd`（歌词区右边界留白）与上下边界
     * 不吃这个值。竖屏没有右栏，本项不生效。
     */
    var lyricPaneGapDp: Int
        get() = prefs.getInt(KEY_LYRIC_PANE_GAP, 8).coerceIn(MIN_LYRIC_PANE_GAP, MAX_LYRIC_PANE_GAP)
        set(value) = prefs.edit {
            putInt(KEY_LYRIC_PANE_GAP, value.coerceIn(MIN_LYRIC_PANE_GAP, MAX_LYRIC_PANE_GAP))
        }

    var currentLineScale: Float
        get() = prefs.getFloat(KEY_CURRENT_SCALE, 1.05f)
        set(value) = prefs.edit { putFloat(KEY_CURRENT_SCALE, value) }

    var nextLineScale: Float
        get() = prefs.getFloat(KEY_NEXT_SCALE, 0.92f)
        set(value) = prefs.edit { putFloat(KEY_NEXT_SCALE, value) }

    /**
     * 是否显示歌词翻译（全局偏好，**默认开**）。
     *
     * 默认开是需求 4 的原话「默认汉语」：拿到译文就该显示，不该让用户先去翻开关。
     * 关掉之后译文仍留在文档里（`.lrcx` 的 `#@t` 轨），只是不画出来。
     */
    var lyricShowTranslation: Boolean
        get() = prefs.getBoolean(KEY_LYRIC_SHOW_TRANSLATION, true)
        set(value) = prefs.edit { putBoolean(KEY_LYRIC_SHOW_TRANSLATION, value) }

    /**
     * 是否显示注音（全局偏好，**默认关**）。
     *
     * 注音是给「听不懂方言/外语」的人用的辅助行，对大多数人只是多占一行高度，
     * 所以默认不显示；有注音轨时歌词调节面板里的开关会亮起。
     */
    var lyricShowPhonetic: Boolean
        get() = prefs.getBoolean(KEY_LYRIC_SHOW_PHONETIC, false)
        set(value) = prefs.edit { putBoolean(KEY_LYRIC_SHOW_PHONETIC, value) }

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

    /**
     * 联网歌词渠道的顺序（逗号分隔的 id，见 [LyricProviderConfig]）。
     *
     * 存成字符串而不是序列化对象，是为了让「旧版本写进去的顺序」在升级后仍能解析 ——
     * 新版本多出来的渠道由 [LyricProviderConfig.parse] 自动补到末尾。
     */
    var lyricProviderOrderCsv: String
        get() = prefs.getString(KEY_LYRIC_PROVIDER_ORDER, "") ?: ""
        set(value) = prefs.edit { putString(KEY_LYRIC_PROVIDER_ORDER, value) }

    /**
     * 关掉的渠道 id（逗号分隔）。
     *
     * `null` 表示用户从没动过开关，此时按 [LyricProviders.defaultDisabled] 走；
     * 一旦写过（哪怕是空串）就以写入的内容为准，否则用户「把默认关的打开」下次启动会被打回去。
     */
    var lyricProviderDisabledCsv: String?
        get() = prefs.getString(KEY_LYRIC_PROVIDER_DISABLED, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LYRIC_PROVIDER_DISABLED)
            else putString(KEY_LYRIC_PROVIDER_DISABLED, value)
        }

    fun lyricProviderConfig(): LyricProviderConfig =
        LyricProviderConfig.parse(lyricProviderOrderCsv, lyricProviderDisabledCsv)

    fun saveLyricProviderConfig(config: LyricProviderConfig) {
        prefs.edit {
            putString(KEY_LYRIC_PROVIDER_ORDER, config.orderCsv())
            putString(KEY_LYRIC_PROVIDER_DISABLED, config.disabledCsv())
        }
    }

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

    // ---------- 主题（`design/04 §2.2` / §2.6） ----------

    /**
     * 明暗档位，4 档（`04 §2.2`）。出厂默认 [NightMode.DEFAULT] = **深色**（方案 I「车机默认深色」裁定）。
     *
     * 键名带 `_v2` 是防后人误读，**不代表需要迁移**：期 2a 已把 `applicationId` 改成
     * `com.suisheng.player`，旧包名的 SharedPreferences 文件在物理上不可达，
     * 全新安装从默认值起步即可，这里不要写任何迁移代码。
     * 旧键 `theme_mode` 存的是 `ThemeMode` 的 ordinal，语义（3 档 / 4 档）与编码（int / 字符串）
     * 都不同，所以也不复用同一个键。
     */
    var nightMode: NightMode
        get() = NightMode.fromId(prefs.getString(KEY_THEME_MODE_V2, null))
        set(value) = prefs.edit { putString(KEY_THEME_MODE_V2, value.id) }

    /** 全局强调色，非法值回落 [ThemeAccent.DEFAULT]。 */
    var themeAccent: ThemeAccent
        get() = ThemeAccent.fromId(prefs.getString(KEY_THEME_ACCENT, null))
        set(value) = prefs.edit { putString(KEY_THEME_ACCENT, value.id) }

    /** 播放页独立强调色（仅在 [playerThemeSplitEnabled] 为 true 时生效）。 */
    var playerThemeAccent: ThemeAccent
        get() = ThemeAccent.fromId(prefs.getString(KEY_THEME_ACCENT_PLAYER, null))
        set(value) = prefs.edit { putString(KEY_THEME_ACCENT_PLAYER, value.id) }

    /** 播放页分控开关：是否允许播放页用与全局不同的强调色（`04 §2.6`）。 */
    var playerThemeSplitEnabled: Boolean
        get() = prefs.getBoolean(KEY_THEME_SPLIT_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_THEME_SPLIT_ENABLED, value) }

    /** 播放页封面取色：封面主色是否参与播放页背景（`04 §2.6`）。 */
    var playerCoverTintEnabled: Boolean
        get() = prefs.getBoolean(KEY_THEME_COVER_TINT, true)
        set(value) = prefs.edit { putBoolean(KEY_THEME_COVER_TINT, value) }

    /** 夜间开始时刻的手动覆盖（当日第几分钟）；`null` 表示「用内置日出日落表」。 */
    var nightStartMinuteOverride: Int?
        get() = prefs.getInt(KEY_THEME_NIGHT_START, NO_MINUTE_OVERRIDE)
            .takeIf { it != NO_MINUTE_OVERRIDE }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_THEME_NIGHT_START)
            } else {
                putInt(KEY_THEME_NIGHT_START, ThemeController.normalizeMinute(value))
            }
        }

    /** 夜间结束时刻的手动覆盖（当日第几分钟）；`null` 表示「用内置日出日落表」。 */
    var nightEndMinuteOverride: Int?
        get() = prefs.getInt(KEY_THEME_NIGHT_END, NO_MINUTE_OVERRIDE)
            .takeIf { it != NO_MINUTE_OVERRIDE }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_THEME_NIGHT_END)
            } else {
                putInt(KEY_THEME_NIGHT_END, ThemeController.normalizeMinute(value))
            }
        }

    /**
     * 文件夹浏览「记忆上次层级」（`design/04 §3.5` 第 5 条）。
     * 存绝对路径；目录不存在或为空串时页面回到根。
     */
    var folderBrowsePath: String?
        get() = prefs.getString(KEY_FOLDER_BROWSE, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove(KEY_FOLDER_BROWSE) else putString(KEY_FOLDER_BROWSE, value)
        }

    /**
     * 播放倍速（`design/05 §12 Q3`：全局记住、不随切歌重置）。
     *
     * 合法区间钉在 0.5x ~ 2.5x：ExoPlayer 允许更极端的值，但车机上超出这个范围
     * 人耳已经无法接受，夹住可以挡住脏数据把播放拖成「尖叫声」。
     */
    var playbackSpeed: Float
        get() = prefs.getFloat(KEY_SPEED, 1.0f).coerceIn(0.5f, 2.5f)
        set(value) = prefs.edit { putFloat(KEY_SPEED, value.coerceIn(0.5f, 2.5f)) }

    /**
     * 解析播放页实际生效的强调色（`04 §2.6` 三条降级规则见 [ThemeController.resolvePlayerAccent]）。
     */
    fun resolvePlayerAccent(): ThemeController.PlayerAccentResolution =
        ThemeController.resolvePlayerAccent(
            global = themeAccent,
            playerAccentId = prefs.getString(KEY_THEME_ACCENT_PLAYER, null),
            splitEnabled = playerThemeSplitEnabled,
        )

    /** 全局主题样式（浅/深由资源限定符自动切换，调用方不必判断日夜）。 */
    @StyleRes
    fun themeStyleRes(): Int = ThemeController.accentStyleRes(themeAccent)

    /** 播放页分控主题 overlay；分控关闭时返回全局样式。 */
    @StyleRes
    fun playerThemeStyleRes(): Int =
        ThemeController.accentStyleRes(resolvePlayerAccent().accent, forPlayer = true)

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

    /**
     * 把当前明暗档位落到 `AppCompatDelegate`。
     *
     * 签名保持无参（与 2b 之前一致），Context 用构造时拿到的 [appContext]：
     * [NightMode.TIME_AUTO] 要用内置日出日落表（`R.array.night_schedule_*`）
     * 与当前日期算出夜间区间，而资源只能从 Context 取。
     *
     * 内部一律包 `runCatching`：这是启动路径上的第一行代码之一，
     * 主题算错最多导致配色不对，绝不能让车机开机时闪退。
     */
    fun applyTheme() {
        runCatching {
            val mode = ThemeController.nightModeConstant(
                mode = nightMode,
                isNightNow = ThemeController.isNightNow(
                    context = appContext,
                    calendar = Calendar.getInstance(),
                    overrideStart = nightStartMinuteOverride,
                    overrideEnd = nightEndMinuteOverride,
                ),
            )
            AppCompatDelegate.setDefaultNightMode(mode)
        }
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
        /** 横屏双栏的左右栏间距（dp），见 [lyricPaneGapDp]。 */
        const val KEY_LYRIC_PANE_GAP = "lyric_pane_gap_dp"
        /** [lyricPaneGapDp] 的取值区间：0 是骨架留白决定的下限，32 是上限（再大会回到旧的过宽观感）。 */
        const val MIN_LYRIC_PANE_GAP = 0
        const val MAX_LYRIC_PANE_GAP = 32
        const val KEY_CURRENT_SCALE = "current_line_scale"
        const val KEY_NEXT_SCALE = "next_line_scale"
        /** 是否显示歌词翻译（默认开），见 [lyricShowTranslation]。 */
        const val KEY_LYRIC_SHOW_TRANSLATION = "lyric_show_translation"
        /** 是否显示注音（默认关），见 [lyricShowPhonetic]。 */
        const val KEY_LYRIC_SHOW_PHONETIC = "lyric_show_phonetic"
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
        const val KEY_THEME_MODE_V2 = "theme_mode_v2"
        const val KEY_THEME_ACCENT = "theme_accent"
        const val KEY_THEME_ACCENT_PLAYER = "theme_accent_player"
        const val KEY_THEME_SPLIT_ENABLED = "theme_split_enabled"
        const val KEY_THEME_COVER_TINT = "theme_cover_tint_enabled"
        const val KEY_THEME_NIGHT_START = "theme_night_start"
        const val KEY_THEME_NIGHT_END = "theme_night_end"
        /** 文件夹浏览上次所在的绝对路径（`design/04 §3.5` 的「记忆上次层级」）。 */
        const val KEY_FOLDER_BROWSE = "folder_browse_path"

        /** 播放倍速（Float，默认 1.0；`design/05 §12 Q3`）。 */
        const val KEY_SPEED = "playback_speed"
        /** [nightStartMinuteOverride] / [nightEndMinuteOverride] 的「未设置」哨兵值。 */
        const val NO_MINUTE_OVERRIDE = -1
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
        /** 联网歌词渠道顺序（逗号分隔 id），见 [lyricProviderOrderCsv]。 */
        const val KEY_LYRIC_PROVIDER_ORDER = "lyric_provider_order"
        /** 关掉的联网歌词渠道（逗号分隔 id），见 [lyricProviderDisabledCsv]。 */
        const val KEY_LYRIC_PROVIDER_DISABLED = "lyric_provider_disabled"
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
