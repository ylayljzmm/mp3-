package com.suisheng.player.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import com.suisheng.player.MusicPlaybackService
import com.suisheng.player.R
import com.suisheng.player.data.EqSettings
import com.suisheng.player.data.FavoriteStore
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.databinding.DialogLyricSearchBinding
import com.suisheng.player.databinding.FragmentPlayerBinding
import com.suisheng.player.model.LrcLine
import com.suisheng.player.model.PlaybackMode
import com.suisheng.player.model.Song
import com.suisheng.player.playback.AudioEffectController
import com.suisheng.player.playback.PlaybackStateHolder
import kotlin.math.max

/**
 * 播放页（`design/05` 方案 D）。
 *
 * 版式在 `fragment_player.xml` / `layout-port/fragment_player.xml` 里，
 * 双态显隐在 [PlayerChromeController]，背景色块在 [BackdropView]，黑胶在 [VinylRecordView]。
 * 本类只做三件事：**绑事件、把播放状态映射到界面、维护歌曲信息与倍速链路**。
 *
 * 关键约束（都在 05 里有出处）：
 *  - 状态切换只由 [PlayerChromeController] 决定，这里不直接改 visibility；
 *  - `renderedStateKey` 节流保留：进度回调约 8Hz，不加节流会在低端车机上抖；
 *  - 主题在 inflate 前套 overlay（§6），所以下面的 `?attr/` 全部按播放页分控色解析。
 *
 * 5.5.0 车机整改：沉浸态（自动隐藏顶栏/功能行）与「仅封面」放大态整体删除，
 * 播放页只保留 [PlayerChromeController.State.DUO] / [PlayerChromeController.State.LYRICS_FULL]
 * 两态；相应地不再有交互计时、不再需要遍历视图树挂触摸监听。
 */
class PlayerFragment : Fragment(), PlaybackStateHolder.Listener {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: SettingsRepository
    private lateinit var chrome: PlayerChromeController

    /** 本地收藏（`filesDir/favorites.json`）；首次用到时才创建，避免 onCreateView 里多一次 IO。 */
    private val favorites by lazy { FavoriteStore(requireContext()) }

    /** 音效（均衡器）设置（独立 prefs 文件 `eq_settings`），同样懒创建。 */
    private val eqSettings by lazy { EqSettings(requireContext()) }

    private var userSeeking = false

    /** 上一帧渲染的状态关键字，挡住每秒 8 次的进度回调（保留自 2c-2，别删）。 */
    private var renderedStateKey: String? = null

    /** 收藏图标的去重关键字（`Song.path|是否已收藏`），View 重建时置空。 */
    private var favoriteUiKey: String? = null

    private var singleColumn = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        settings = SettingsRepository(requireContext())
        // §6 播放页分控：以全局主题为底，再把播放页 overlay 叠上去。
        // 直接用 overlay 当 theme 会丢掉 colorSurface 等其余 token（overlay 没有 parent），
        // 所以必须是「全局主题 + applyStyle(overlay)」两步。
        val wrapper = ContextThemeWrapper(requireContext(), settings.themeStyleRes())
        runCatching { wrapper.theme.applyStyle(settings.playerThemeStyleRes(), true) }
        _binding = FragmentPlayerBinding.inflate(inflater.cloneInContext(wrapper), container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        singleColumn =
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        chrome = PlayerChromeController(
            binding = binding,
            singleColumn = singleColumn,
            onStateChanged = ::onChromeStateChanged,
        )

        bindTransport()
        bindActionRow()
        bindTopBar()
        setupSlider()

        binding.scrollLyricView.setFadeColor(
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
        )
        binding.scrollLyricView.setOnLongClickListener {
            // 既有功能：长按歌词搜歌词。别在这里弄丢（期 5 才做手动纠错）
            showLyricSearchDialog()
            true
        }

        updateFavoriteUi(PlaybackStateHolder.currentSong)

        renderState()
        renderCover(PlaybackStateHolder.coverArtPath)
        updateProgressUi(PlaybackStateHolder.positionMs, PlaybackStateHolder.durationMs)
    }

    override fun onStart() {
        super.onStart()
        PlaybackStateHolder.addListener(this)
        if (PlaybackStateHolder.isPlaying) binding.vinylRecord.resumeSpin()
    }

    override fun onStop() {
        PlaybackStateHolder.removeListener(this)
        // 后台/息屏不再转；不清角度，回来接着转
        binding.vinylRecord.pauseSpin()
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        // 视图重建后图标从 XML 的默认态开始，去重键必须跟着失效，否则不会重画
        favoriteUiKey = null
        super.onDestroyView()
    }

    // ---------------------------------------------------------------- 绑定

    private fun bindTransport() {
        binding.btnPlayPause.setOnClickListener { sendAction(MusicPlaybackService.ACTION_TOGGLE) }
        binding.btnNext.setOnClickListener { sendAction(MusicPlaybackService.ACTION_NEXT) }
        binding.btnPrev.setOnClickListener { sendAction(MusicPlaybackService.ACTION_PREV) }
    }

    /** 方案 I 功能行 5 键：播放模式 / 收藏 / 音效 / 队列 / 歌曲信息（纯图标，无文字标签）。 */
    private fun bindActionRow() {
        binding.btnMode.setOnClickListener { toggleMode() }
        binding.btnActionFavorite.setOnClickListener { toggleFavorite() }
        binding.btnActionSound.setOnClickListener { openEqPanel() }
        binding.btnQueue.setOnClickListener { openQueuePanel() }
        binding.btnActionInfo.setOnClickListener { openSongInfoPanel() }
    }

    private fun bindTopBar() {
        binding.btnDrawerMenu.setOnClickListener {
            (activity as? MainHost)?.openDrawer()
        }
        binding.btnLyricsBack.setOnClickListener {
            chrome.exitFullLyrics()
        }
        binding.btnMore.setOnClickListener { openMorePanel() }
    }

    private fun setupSlider() {
        val slider = binding.progressSlider
        // §3.2：拖动中把两条时间都转成主色、滑块从 13dp 放大到 16dp；
        // 静止态的颜色与半径取自 XML（@dimen/player_slider_thumb_radius = 6.5dp 半径 = 13dp 直径）。
        val idleTextColor = MaterialColors.getColor(
            binding.timeCurrent,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
        val activeTextColor = MaterialColors.getColor(
            binding.timeCurrent,
            com.google.android.material.R.attr.colorPrimary,
        )
        // BaseSlider 的 thumbRadius 是 @Px int，不是 float
        val thumbIdlePx = resources.getDimensionPixelSize(R.dimen.player_slider_thumb_radius)
        val thumbActivePx = resources.getDimensionPixelSize(R.dimen.player_slider_thumb_radius_active)

        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                userSeeking = true
                slider.thumbRadius = thumbActivePx
                binding.timeCurrent.setTextColor(activeTextColor)
                binding.timeTotal.setTextColor(activeTextColor)
            }

            override fun onStopTrackingTouch(slider: Slider) {
                userSeeking = false
                slider.thumbRadius = thumbIdlePx
                binding.timeCurrent.setTextColor(idleTextColor)
                binding.timeTotal.setTextColor(idleTextColor)
                // 只有抬手才 seek，拖动过程中不打断播放（§3.2 末段）
                val target = slider.value.toLong()
                seekTo(target)
                // 先把界面钉到手抬起的值，避免下一次状态回调把滑块弹回旧位置
                updateProgressUi(target, PlaybackStateHolder.durationMs)
            }
        })

        // §3.2：拖动过程中「当前时间」实时跟手（只动文字，不动播放位置）
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && userSeeking) {
                binding.timeCurrent.text = formatTime(value.toLong())
            }
        }
    }

    // ---------------------------------------------------------------- 双态

    private fun onChromeStateChanged(state: PlayerChromeController.State) {
        val fullLyrics = state == PlayerChromeController.State.LYRICS_FULL
        binding.scrollLyricView.setFullscreen(fullLyrics)
        applyLyricsWidthClamp(fullLyrics)
        // 全屏歌词态下歌词列占满整屏，单击退出全屏；双栏态不需要这层点击
        if (singleColumn) {
            binding.scrollLyricView.isClickable = fullLyrics
        }
    }

    /**
     * 全屏歌词态行宽 clamp 到 900dp 居中（§7 第三行）。
     *
     * 用内边距实现而不是改 layout_width：改宽度会触发重新布局，
     * 而歌词视图在两种形态之间切换时不应出现「字先横移再淡入」。
     */
    private fun applyLyricsWidthClamp(fullLyrics: Boolean) {
        val view = binding.scrollLyricView
        val density = resources.displayMetrics.density
        val baseStart = resources.getDimensionPixelSize(R.dimen.player_lyrics_padding_start)
        val baseEnd = if (singleColumn) {
            baseStart
        } else {
            resources.getDimensionPixelSize(R.dimen.player_lyrics_padding_end)
        }
        val top = view.paddingTop
        val bottom = view.paddingBottom
        if (!fullLyrics) {
            view.setPadding(baseStart, top, baseEnd, bottom)
            return
        }
        view.post {
            if (_binding == null) return@post
            val maxContent = (MAX_LYRICS_WIDTH_DP * density).toInt()
            val extra = ((view.width - maxContent) / 2).coerceAtLeast(baseStart)
            view.setPadding(extra, top, extra, bottom)
        }
    }

    // ---------------------------------------------------------------- 状态

    override fun onPlaybackChanged(
        song: Song?,
        playing: Boolean,
        positionMs: Long,
        lines: List<LrcLine>,
    ) {
        renderState()
        binding.scrollLyricView.update(lines, positionMs)
        if (!userSeeking) updateProgressUi(positionMs, PlaybackStateHolder.durationMs)
        // 切歌 / 恢复播放后收藏图标要跟着当前曲目走（收藏是按 Song.path 记的）
        updateFavoriteUi(song)
    }

    override fun onPlayModeChanged(mode: PlaybackMode) {
        updateModeUi(mode)
    }

    /**
     * 队列增删 / 清空后回调。
     *
     * 面板本身每次打开都重新读 [PlaybackStateHolder.songs] 快照，所以这里不缓存队列；
     * 只把「有没有队列」映射到功能行队列键的透明度上——空队列时点开是个空面板，先给个暗示。
     */
    override fun onPlaylistChanged(songs: List<Song>) {
        val view = _binding ?: return
        val alpha = if (songs.isEmpty()) QUEUE_EMPTY_ALPHA else 1f
        view.btnQueue.alpha = alpha
    }

    override fun onCoverChanged(coverPath: String?) {
        renderCover(coverPath)
    }

    override fun onDurationChanged(durationMs: Long) {
        if (!userSeeking) updateProgressUi(PlaybackStateHolder.positionMs, durationMs)
    }

    private fun renderState() {
        val song = PlaybackStateHolder.currentSong
        val key = "${song?.path}|${PlaybackStateHolder.isPlaying}|${PlaybackStateHolder.playMode}|" +
            "${song?.title}|${song?.artist}"
        if (key != renderedStateKey) {
            renderedStateKey = key
            // 无歌时给专门的静默态：歌名位出现 App 名、歌手位塞带 \n 的 no_songs 都会像 bug
            if (song == null) {
                binding.songTitle.setText(R.string.player_idle_title)
                binding.songArtist.setText(R.string.player_idle_hint)
            } else {
                binding.songTitle.text = song.title
                binding.songArtist.text = song.artist
            }
            binding.btnPlayPause.setImageResource(
                if (PlaybackStateHolder.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            binding.btnPlayPause.contentDescription =
                getString(if (PlaybackStateHolder.isPlaying) R.string.pause else R.string.play)
            if (PlaybackStateHolder.isPlaying) {
                binding.vinylRecord.startSpin()
            } else {
                binding.vinylRecord.pauseSpin()
            }
            updateModeUi(PlaybackStateHolder.playMode)
        }
        binding.scrollLyricView.update(PlaybackStateHolder.lrcLines, PlaybackStateHolder.positionMs)
    }

    private fun updateProgressUi(positionMs: Long, durationMs: Long) {
        binding.timeCurrent.text = formatTime(positionMs)
        binding.timeTotal.text = formatTime(durationMs)
        val max = max(durationMs, 1L).toFloat()
        if (binding.progressSlider.valueTo != max) {
            binding.progressSlider.valueTo = max
        }
        binding.progressSlider.value = positionMs.coerceIn(0L, durationMs).toFloat()
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        return "$min:${sec.toString().padStart(2, '0')}"
    }

    private fun renderCover(coverPath: String?) {
        if (coverPath.isNullOrBlank()) {
            binding.vinylRecord.setCoverBitmap(null)
            return
        }
        binding.vinylRecord.setCoverBitmap(decodeCover(coverPath))
    }

    private fun decodeCover(path: String): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return@runCatching null
        var sample = 1
        while (longest / sample > COVER_MAX_PX) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** 三态模式：图标与无障碍描述一起换（功能行已改纯图标，没有文字标签可换）。 */
    private fun updateModeUi(mode: PlaybackMode) {
        val (icon, description) = when (mode) {
            PlaybackMode.ORDER -> Pair(R.drawable.ic_mode_order, R.string.mode_order)
            PlaybackMode.SHUFFLE -> Pair(R.drawable.ic_mode_shuffle, R.string.mode_shuffle)
            PlaybackMode.REPEAT_ONE -> Pair(R.drawable.ic_mode_repeat_one, R.string.mode_repeat_one)
        }
        binding.iconActionMode.setImageResource(icon)
        binding.btnMode.contentDescription = getString(description)
    }

    // ---------------------------------------------------------------- 队列 / 收藏 / 更多

    /** 功能行第 4 格：弹出真实队列面板（顶栏队列按钮已按 5.5.0 整改删除）。 */
    private fun openQueuePanel() {
        QueuePanel.show(
            requireContext(),
            onJump = { index -> sendQueueCommand(MusicPlaybackService.ACTION_QUEUE_JUMP, index) },
            onRemove = { index -> sendQueueCommand(MusicPlaybackService.ACTION_QUEUE_REMOVE, index) },
            onClear = {
                sendQueueCommand(MusicPlaybackService.ACTION_QUEUE_CLEAR, null)
                Toast.makeText(requireContext(), R.string.queue_cleared, Toast.LENGTH_SHORT).show()
            },
        )
    }

    /** 顶栏「更多」：收藏 / 从队列移除 / 歌曲信息。 */
    private fun openMorePanel() {
        val song = PlaybackStateHolder.currentSong
        if (song == null) {
            Toast.makeText(requireContext(), R.string.no_songs, Toast.LENGTH_SHORT).show()
            return
        }
        val queue = PlaybackStateHolder.songs
        MorePanel.show(
            requireContext(),
            song = song,
            isFavorite = favorites.isFavorite(song.path),
            // 只剩一首时「从队列移除」等于停播，没有意义，直接收起来
            canRemoveFromQueue = queue.size > 1 && queue.any { it.path == song.path },
            onToggleFavorite = ::toggleFavorite,
            onRemoveFromQueue = ::removeCurrentFromQueue,
        )
    }

    /** 功能行第 2 格：切换当前曲目的收藏状态（立即落盘 + 立即换图标）。 */
    private fun toggleFavorite() {
        val song = PlaybackStateHolder.currentSong
        if (song == null) {
            Toast.makeText(requireContext(), R.string.no_songs, Toast.LENGTH_SHORT).show()
            return
        }
        val favorite = favorites.toggle(song.path)
        updateFavoriteUi(song)
        Toast.makeText(
            requireContext(),
            if (favorite) R.string.favorite_added else R.string.favorite_removed,
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** 更多面板里的「从播放队列移除」：按当前曲目在队列里的下标发指令。 */
    private fun removeCurrentFromQueue() {
        val song = PlaybackStateHolder.currentSong ?: return
        val index = PlaybackStateHolder.songs.indexOfFirst { it.path == song.path }
        if (index < 0) return
        sendQueueCommand(MusicPlaybackService.ACTION_QUEUE_REMOVE, index)
    }

    /**
     * 收藏按钮的两种态：已收藏 = 实心 + 主色；未收藏 = 空心 + onSurfaceVariant。
     * 两态 `alpha` 都固定 1f（置灰只发生在空队列时的队列键上，收藏态不置灰）。
     *
     * `onPlaybackChanged` 每秒来 8 次，`setImageResource` 会重建 drawable，
     * 所以按「曲目路径 + 收藏态」做一次去重，只有真变了才碰图标。
     */
    private fun updateFavoriteUi(song: Song?) {
        val view = _binding ?: return
        val favorite = song != null && favorites.isFavorite(song.path)
        val key = "${song?.path}|$favorite"
        if (key == favoriteUiKey) return
        favoriteUiKey = key
        view.iconActionFavorite.setImageResource(
            if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
        val colorAttr = if (favorite) {
            com.google.android.material.R.attr.colorPrimary
        } else {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        view.iconActionFavorite.imageTintList =
            ColorStateList.valueOf(MaterialColors.getColor(view.iconActionFavorite, colorAttr))
        view.btnActionFavorite.alpha = 1f
    }

    /**
     * 队列指令统一出口。
     *
     * @param index `null` 时不带 [MusicPlaybackService.EXTRA_INDEX]（清空队列用）
     */
    private fun sendQueueCommand(action: String, index: Int?) {
        runCatching {
            ContextCompat.startForegroundService(
                requireContext(),
                Intent(requireContext(), MusicPlaybackService::class.java).apply {
                    this.action = action
                    if (index != null) putExtra(MusicPlaybackService.EXTRA_INDEX, index)
                },
            )
        }.onFailure {
            Toast.makeText(requireContext(), R.string.playback_start_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- 歌曲信息 / 倍速

    /**
     * 功能行第 5 格：歌曲信息 + 播放速度。
     * 独立倍速格已按方案 I 删除，倍速链路整体收进这个面板，功能一个不减。
     */
    private fun openSongInfoPanel() {
        val song = PlaybackStateHolder.currentSong
        if (song == null) {
            Toast.makeText(requireContext(), R.string.no_songs, Toast.LENGTH_SHORT).show()
            return
        }
        SongInfoPanel.show(requireContext(), song, settings.playbackSpeed) { speed ->
            // 全局记住、不随切歌重置 —— 写设置而不是只改播放器
            settings.playbackSpeed = speed
            sendSpeed(speed)
        }
    }

    private fun sendSpeed(speed: Float) {
        runCatching {
            ContextCompat.startForegroundService(
                requireContext(),
                Intent(requireContext(), MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_SET_SPEED
                    putExtra(MusicPlaybackService.EXTRA_SPEED, speed)
                },
            )
        }
    }

    // ---------------------------------------------------------------- 音效

    /**
     * 功能行第 3 格：弹出音效（均衡器）面板。
     *
     * 面板就地改 [eqSettings] 这份实例（setter 同时落盘），回调里只需要**通知服务**重新施加。
     *
     * `supported` 取 [AudioEffectController.lastKnownSupported]：服务里第一次施加过之后就有结论，
     * 设备不支持时面板把滑块 / chip / 开关全置灰并提示；还没探测过（`null`）按「支持」处理，
     * 面板照常可用，服务端静默降级。
     */
    private fun openEqPanel() {
        val supported = AudioEffectController.lastKnownSupported != false
        EqPanel.show(requireContext(), eqSettings, supported = supported) { updated ->
            // 面板已经改完内存值（同一份实例），这里只补一次落盘 + 通知服务
            eqSettings.applyFrom(updated)
            sendEqCommand()
        }
    }

    /**
     * 音效指令单独发，**不走 [sendAction]**：后者在 `PlaybackStateHolder.songs` 为空时直接
     * Toast「没有歌曲」并 return，而音效在没歌时也应该能设（下次播放生效）。
     *
     * 用 `startService` 而不是 `startForegroundService`：`ACTION_APPLY_EQ` 在服务端
     * `requiresForeground()` 返回 false、不会 `startForeground()`，而 `startForegroundService`
     * 要求 5 秒内必须起通知，否则 ANR/异常。
     */
    private fun sendEqCommand() {
        runCatching {
            requireContext().startService(
                Intent(requireContext(), MusicPlaybackService::class.java)
                    .setAction(MusicPlaybackService.ACTION_APPLY_EQ)
            )
        }
    }

    // ---------------------------------------------------------------- 播放控制

    private fun toggleMode() {
        val next = when (PlaybackStateHolder.playMode) {
            PlaybackMode.ORDER -> PlaybackMode.SHUFFLE
            PlaybackMode.SHUFFLE -> PlaybackMode.REPEAT_ONE
            PlaybackMode.REPEAT_ONE -> PlaybackMode.ORDER
        }
        val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_SET_MODE
            putExtra(MusicPlaybackService.EXTRA_MODE, next.ordinal)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun seekTo(positionMs: Long) {
        val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_SEEK
            putExtra(MusicPlaybackService.EXTRA_SEEK, positionMs)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun sendAction(action: String) {
        if (PlaybackStateHolder.songs.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_songs, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                requireContext(),
                Intent(requireContext(), MusicPlaybackService::class.java).apply { this.action = action }
            )
        }.onFailure {
            Toast.makeText(requireContext(), R.string.playback_start_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** 由 `MainActivity.notifyLyricStyleChanged()` 调用（歌词字号/配色变了要重排）。 */
    fun refreshLyricStyle() {
        binding.scrollLyricView.refreshStyle()
        binding.scrollLyricView.update(PlaybackStateHolder.lrcLines, PlaybackStateHolder.positionMs)
    }

    private fun showLyricSearchDialog() {
        val song = PlaybackStateHolder.currentSong ?: return
        val dialogBinding = DialogLyricSearchBinding.inflate(layoutInflater)
        dialogBinding.inputTitle.setText(settings.lyricSearchTitle(song.path) ?: song.title)
        dialogBinding.inputArtist.setText(settings.lyricSearchArtist(song.path) ?: song.artist)

        AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.lyric_search_action) { _, _ ->
                val title = dialogBinding.inputTitle.text?.toString()?.trim().orEmpty()
                val artist = dialogBinding.inputArtist.text?.toString()?.trim().orEmpty()
                if (title.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.lyric_search_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_RELOAD_LYRICS
                    putExtra(MusicPlaybackService.EXTRA_SEARCH_TITLE, title)
                    putExtra(MusicPlaybackService.EXTRA_SEARCH_ARTIST, artist)
                }
                ContextCompat.startForegroundService(requireContext(), intent)
                Toast.makeText(requireContext(), R.string.lyric_searching, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private companion object {
        /** 解码上限：768px 最长边（与原实现一致，避免 3000×3000 封面在车机上 OOM）。 */
        const val COVER_MAX_PX = 768

        /** 全屏歌词行宽上限（`design/05 §7` 第三行「行宽 clamp 900dp 居中」）。 */
        const val MAX_LYRICS_WIDTH_DP = 900f

        /** 队列为空时功能行队列键的透明度（有队列时回到 1f）。 */
        const val QUEUE_EMPTY_ALPHA = 0.5f
    }
}
