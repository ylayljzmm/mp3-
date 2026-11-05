package com.suisheng.player.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import com.suisheng.player.MusicPlaybackService
import com.suisheng.player.R
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.databinding.DialogLyricSearchBinding
import com.suisheng.player.databinding.FragmentPlayerBinding
import com.suisheng.player.model.LrcLine
import com.suisheng.player.model.PlaybackMode
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder
import kotlin.math.max

/**
 * 播放页（`design/05` 方案 D）。
 *
 * 版式在 `fragment_player.xml` / `layout-port/fragment_player.xml` 里，
 * 四态显隐在 [PlayerChromeController]，背景色块在 [BackdropView]，黑胶在 [VinylRecordView]。
 * 本类只做三件事：**绑事件、把播放状态映射到界面、维护倍速链路**。
 *
 * 关键约束（都在 05 里有出处）：
 *  - 四态切换只由 [PlayerChromeController] 决定，这里不直接改 visibility；
 *  - `renderedStateKey` 节流保留：进度回调约 8Hz，不加节流会在低端车机上抖；
 *  - 主题在 inflate 前套 overlay（§6），所以下面的 `?attr/` 全部按播放页分控色解析。
 */
class PlayerFragment : Fragment(), PlaybackStateHolder.Listener {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: SettingsRepository
    private lateinit var chrome: PlayerChromeController

    private var userSeeking = false

    /** 上一帧渲染的状态关键字，挡住每秒 8 次的进度回调（保留自 2c-2，别删）。 */
    private var renderedStateKey: String? = null

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
        bindVinyl()
        setupSlider()

        binding.scrollLyricView.setFadeColor(
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
        )
        binding.scrollLyricView.setOnLongClickListener {
            // 既有功能：长按歌词搜歌词。别在这里弄丢（期 5 才做手动纠错）
            showLyricSearchDialog()
            true
        }

        applyPrimaryButtonTint()
        updateSpeedLabel()
        resetInteractionOnTouch(binding.root)

        renderState()
        renderCover(PlaybackStateHolder.coverArtPath)
        updateProgressUi(PlaybackStateHolder.positionMs, PlaybackStateHolder.durationMs)
    }

    override fun onStart() {
        super.onStart()
        PlaybackStateHolder.addListener(this)
        chrome.onUserInteraction()
        if (PlaybackStateHolder.isPlaying) binding.vinylRecord.resumeSpin()
    }

    override fun onStop() {
        PlaybackStateHolder.removeListener(this)
        // 后台/息屏不再转；不清角度，回来接着转
        binding.vinylRecord.pauseSpin()
        chrome.detach()
        super.onStop()
    }

    override fun onDestroyView() {
        chrome.detach()
        _binding = null
        super.onDestroyView()
    }

    // ---------------------------------------------------------------- 绑定

    private fun bindTransport() {
        binding.btnPlayPause.setOnClickListener { sendAction(MusicPlaybackService.ACTION_TOGGLE) }
        binding.btnNext.setOnClickListener { sendAction(MusicPlaybackService.ACTION_NEXT) }
        binding.btnPrev.setOnClickListener { sendAction(MusicPlaybackService.ACTION_PREV) }
        binding.btnMode.setOnClickListener { toggleMode() }
        // 第 5 位（播放队列）本期置灰占位，队列面板留期 4
        binding.btnQueue.setOnClickListener { toastComingSoon() }
    }

    private fun bindActionRow() {
        binding.btnActionLyrics.setOnClickListener {
            chrome.onUserInteraction()
            chrome.toggleFullLyrics()
        }
        binding.btnActionFavorite.setOnClickListener { toastComingSoon() }
        binding.btnActionSound.setOnClickListener { toastComingSoon() }
        binding.btnActionSpeed.setOnClickListener { openSpeedPanel() }
    }

    private fun bindTopBar() {
        binding.btnDrawerMenu.setOnClickListener {
            chrome.onUserInteraction()
            (activity as? MainHost)?.openDrawer()
        }
        binding.btnLyricsBack.setOnClickListener {
            chrome.onUserInteraction()
            chrome.exitFullLyrics()
        }
        binding.btnTopQueue.setOnClickListener { toastComingSoon() }
        binding.btnMore.setOnClickListener { toastComingSoon() }
    }

    private fun bindVinyl() {
        binding.vinylRecord.onTap = {
            chrome.onUserInteraction()
            chrome.setState(PlayerChromeController.State.LYRICS_FULL)
        }
        binding.vinylRecord.onLongPress = {
            chrome.onUserInteraction()
            chrome.toggleCoverOnly()
        }
    }

    private fun setupSlider() {
        binding.progressSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                userSeeking = true
                chrome.onUserInteraction()
            }

            override fun onStopTrackingTouch(slider: Slider) {
                userSeeking = false
                // 只有抬手才 seek，拖动过程中不打断播放（§3.2 末段）
                seekTo(slider.value.toLong())
            }
        })
    }

    // ------------------------------------------------------- 四态与交互计时

    private fun onChromeStateChanged(state: PlayerChromeController.State) {
        val fullLyrics = state == PlayerChromeController.State.LYRICS_FULL
        binding.scrollLyricView.setFullscreen(fullLyrics)
        applyLyricsWidthClamp(fullLyrics)
        // 沉浸态不是「全屏歌词」但顶栏会消失，此时歌词列仍要占满原始位置
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

    /**
     * 任何触摸都重置 8 秒沉浸计时（§12 Q1）。
     *
     * 两层覆盖：根布局接住「没打到任何控件的空白区」；每个可点控件各接一层。
     * 监听器一律返回 false，不消费事件，所以不改变任何既有行为。
     */
    private fun resetInteractionOnTouch(view: View) {
        // Slider 自己有一套触摸实现，套 OnTouchListener 有覆盖其内部监听的风险；
        // 它的「开始拖动」回调里已经调过 onUserInteraction()，这里跳过即可。
        if (view.id == R.id.progressSlider) return
        view.setOnTouchListener { _, _ ->
            chrome.onUserInteraction()
            false
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                if (child.isClickable || child is ViewGroup) resetInteractionOnTouch(child)
            }
        }
    }

    // ---------------------------------------------------------------- 状态

    override fun onPlaybackChanged(
        song: Song?,
        playing: Boolean,
        positionMs: Long,
        lines: List<LrcLine>,
    ) {
        val previous = renderedStateKey
        renderState()
        if (previous != renderedStateKey) {
            // 切歌算一次交互：把沉浸态拉回双栏（§12 Q1「切歌事件重置计时」）
            chrome.onUserInteraction()
        }
        binding.scrollLyricView.update(lines, positionMs)
        if (!userSeeking) updateProgressUi(positionMs, PlaybackStateHolder.durationMs)
    }

    override fun onPlayModeChanged(mode: PlaybackMode) {
        updateModeUi(mode)
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

    /**
     * 78dp 主键底色：浅色模式混 20% 白（§5.3）。
     *
     * 直接铺 `colorPrimary` 在浅灰底上会「发闷」——M3 的 primary 是为文字/图标设计的深色调。
     */
    private fun applyPrimaryButtonTint() {
        val primary = MaterialColors.getColor(
            binding.btnPlayPause,
            com.google.android.material.R.attr.colorPrimary,
        )
        val lightTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_NO
        val fill = if (lightTheme) ColorUtils.blendARGB(primary, Color.WHITE, 0.20f) else primary
        binding.btnPlayPause.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
        }
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

    private fun updateModeUi(mode: PlaybackMode) {
        if (mode == PlaybackMode.SHUFFLE) {
            binding.btnMode.setImageResource(R.drawable.ic_mode_shuffle)
            binding.btnMode.contentDescription = getString(R.string.mode_shuffle)
        } else {
            binding.btnMode.setImageResource(R.drawable.ic_mode_order)
            binding.btnMode.contentDescription = getString(R.string.mode_order)
        }
    }

    // ---------------------------------------------------------------- 倍速

    private fun updateSpeedLabel() {
        binding.labelSpeed.text = getString(R.string.speed_x, SpeedPanel.label(settings.playbackSpeed))
    }

    private fun openSpeedPanel() {
        chrome.onUserInteraction()
        SpeedPanel.show(requireContext(), settings.playbackSpeed) { speed ->
            // §12 Q3：全局记住、不随切歌重置 —— 所以写设置而不是只改播放器
            settings.playbackSpeed = speed
            updateSpeedLabel()
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

    // ---------------------------------------------------------------- 播放控制

    private fun toggleMode() {
        chrome.onUserInteraction()
        val next = if (PlaybackStateHolder.playMode == PlaybackMode.ORDER) {
            PlaybackMode.SHUFFLE
        } else {
            PlaybackMode.ORDER
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
        chrome.onUserInteraction()
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

    private fun toastComingSoon() {
        chrome.onUserInteraction()
        Toast.makeText(requireContext(), R.string.drawer_coming_soon, Toast.LENGTH_SHORT).show()
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
    }
}
