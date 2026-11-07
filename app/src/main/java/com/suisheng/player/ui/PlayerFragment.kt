package com.suisheng.player.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import com.suisheng.player.MusicPlaybackService
import com.suisheng.player.R
import com.suisheng.player.data.CoverArtFetcher
import com.suisheng.player.data.EqSettings
import com.suisheng.player.data.FavoriteStore
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.data.SongInfoLoader
import com.suisheng.player.databinding.DialogLyricSearchBinding
import com.suisheng.player.databinding.FragmentPlayerBinding
import com.suisheng.player.model.LrcLine
import com.suisheng.player.model.PlaybackMode
import com.suisheng.player.model.Song
import com.suisheng.player.playback.AudioEffectController
import com.suisheng.player.playback.PlaybackStateHolder
import com.suisheng.player.util.CoverBitmap
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放页。
 *
 * 版式在 `fragment_player.xml`（横屏 / 车机，双栏）与 `layout-port/fragment_player.xml`
 * （竖屏，三页滑动）里；背景色块在 [BackdropView]，黑胶在 [VinylRecordView]，
 * 翻页在 [SwipeDeckLayout]，信息页在 [SongInfoPageView]。
 *
 * ## 6.0 的三处结构性变化
 *
 * ① **删掉 [PlayerChromeController]**：它维护的「双栏 / 全屏歌词」两态里，
 *    全屏歌词的入口（点封面、长按封面）在 5.5.0 就已经删了，状态永远到不了；
 *    留着只是一个每帧都要判断一次的 `if`。现在歌词的呈现位置由**布局**决定：
 *    横屏在右栏，竖屏是 deck 的第 3 页 —— 不需要任何运行时状态。
 * ② **中轴拆成可横滑的页面**：信息 / 封面（/ 歌词）。
 * ③ **封面尺寸按容器动态算**（[setupCoverSizing]）：竖屏与横屏、手机与车机的
 *    格子边长差一个数量级，写死 dimen 必然在某一档上翻车。
 *
 * ## 保留的既有约束
 *
 * - `renderedStateKey` 节流：进度回调约 8Hz，不加节流会在低端车机上抖；
 * - 主题在 inflate 前套 overlay（`04 §6`），所以下面的 `?attr/` 按播放页分控色解析；
 * - 收藏图标的去重键、队列空时队列键置灰、长按歌词搜歌词 —— 全部保留。
 */
class PlayerFragment : Fragment(), PlaybackStateHolder.Listener {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: SettingsRepository

    /** 本地收藏（`filesDir/favorites.json`）；首次用到时才创建，避免 onCreateView 里多一次 IO。 */
    private val favorites by lazy { FavoriteStore(requireContext()) }

    /** 音效（均衡器）设置（独立 prefs 文件 `eq_settings`），同样懒创建。 */
    private val eqSettings by lazy { EqSettings(requireContext()) }

    private var userSeeking = false

    /** 上一帧渲染的状态关键字，挡住每秒 8 次的进度回调。 */
    private var renderedStateKey: String? = null

    /** 收藏图标的去重关键字（`Song.path|是否已收藏`），View 重建时置空。 */
    private var favoriteUiKey: String? = null

    /**
     * 歌曲信息页**已经读进界面**的曲目路径。
     *
     * `MediaMetadataRetriever.setDataSource` 要开文件、解容器头，车机上 U 盘里的 FLAC
     * 一次几十毫秒 —— 不能跟着 8Hz 的播放回调跑。所以用它做去重：路径没变就直接返回；
     * 路径变了但用户不在信息页时也先不读，等切回信息页由 `onPageChanged` 补一次。
     *
     * 注意这是**完成态**：只在 `bind()` 真的画上去之后才写（见 [loadSongInfo]）。
     * 早先是在起协程之前就写，一旦协程被 `lifecycleScope` 取消（转屏、立刻滑走），
     * 这个键就已经落下，之后再滑到信息页会命中 `return` 而**永远不再重试**，
     * 那一页就一直显示「—」。在途状态单独用 [songInfoLoading] 表达。
     */
    private var songInfoKey: String? = null

    /** 正在读取中的路径（在途去重）：防止同一首歌连点两次滑页各起一个协程。 */
    private var songInfoLoading: String? = null

    // ---------------------------------------------------------------- 生命周期

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

        bindTransport()
        bindActionRow()
        bindTopBar()
        setupSlider()
        setupSwipeDeck()
        setupCoverSizing()
        setupSongInfo()

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
        // renderState() 只画标题 / 播放键 / 黑胶这类低频状态，歌词这一帧得单独补：
        // 进页面时还没有任何 onPlaybackChanged 回调，不补就是一片空白歌词。
        binding.scrollLyricView.update(
            PlaybackStateHolder.lrcLines,
            PlaybackStateHolder.positionMs,
        )
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
        // 同理：视图重建后歌曲信息页是空白 XML，下一次滑到它要重新读一遍
        songInfoKey = null
        // 在途键也一起清：viewLifecycleOwner 的协程此刻正在被取消，别让它挡住下一次读取
        songInfoLoading = null
        super.onDestroyView()
    }

    // ---------------------------------------------------------------- 事件绑定

    private fun bindTransport() {
        binding.btnPlayPause.setOnClickListener { sendAction(MusicPlaybackService.ACTION_TOGGLE) }
        binding.btnNext.setOnClickListener { sendAction(MusicPlaybackService.ACTION_NEXT) }
        binding.btnPrev.setOnClickListener { sendAction(MusicPlaybackService.ACTION_PREV) }
    }

    /** 功能行 5 键：播放模式 / 收藏 / 音效 / 队列 / 歌曲信息（纯图标，无文字标签）。 */
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
        binding.btnMore.setOnClickListener { openMorePanel() }
    }

    private fun setupSlider() {
        val slider = binding.progressSlider
        // 拖动中把两条时间都转成主色、滑块从 13dp 放大到 16dp；
        // 静止态的颜色与半径取自 XML（@dimen/player_slider_thumb_radius = 6.5dp 半径）。
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
                // 只有抬手才 seek，拖动过程中不打断播放
                val target = slider.value.toLong()
                seekTo(target)
                // 先把界面钉到手抬起的值，避免下一次状态回调把滑块弹回旧位置
                updateProgressUi(target, PlaybackStateHolder.durationMs)
            }
        })

        // 拖动过程中「当前时间」实时跟手（只动文字，不动播放位置）
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && userSeeking) {
                binding.timeCurrent.text = formatTime(value.toLong())
            }
        }
    }

    // ---------------------------------------------------------------- 滑动页

    /**
     * 页码点与信息页加载。
     *
     * 页数从 [SwipeDeckLayout.pageCount]（= 子 View 数）读：竖屏 3 页、横屏 2 页，
     * 两个布局共用一份 ViewBinding，写死数字必然在另一侧越界。
     *
     * **初始页不在这里定**：`SwipeDeckLayout` 自己会在 `onFinishInflate` 的 `post{}` 里
     * 定位（没有存档 → `pageCover`；有存档 → 旋转前停的那一页）。这里如果也 setCurrent
     * 一次，就会把刚恢复出来的页码覆盖回封面页 —— 转屏后从歌词页跳回封面就是这么来的。
     */
    private fun setupSwipeDeck() {
        binding.swipeDeck.onPageChanged = { index ->
            updateDots()
            if (index == infoPageIndex()) loadSongInfo()
        }
        // 圆点数量要等 deck 的子 View 就位才知道，所以同样推迟到 post
        binding.swipeDeck.post {
            if (_binding == null) return@post
            rebuildDots()
            updateDots()
        }
    }

    private fun infoPageIndex(): Int = binding.swipeDeck.indexOfPage(R.id.pageInfo)

    private fun currentPageIsInfo(): Boolean {
        val index = infoPageIndex()
        return index >= 0 && binding.swipeDeck.currentIndex == index
    }

    /** 按页数生成圆点；点本身没有 id（数量随布局变，ViewBinding 里也不该有）。 */
    private fun rebuildDots() {
        val container = binding.pageDots
        container.removeAllViews()
        val size = resources.getDimensionPixelSize(R.dimen.player_dot_size)
        val gap = resources.getDimensionPixelSize(R.dimen.player_dot_gap)
        for (i in 0 until binding.swipeDeck.pageCount) {
            val dot = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    if (i > 0) marginStart = gap
                }
                contentDescription = null
                setBackgroundResource(R.drawable.bg_page_dot_off)
                setOnClickListener { binding.swipeDeck.setCurrent(i, animate = true) }
            }
            container.addView(dot)
        }
    }

    /**
     * 选中态用 `colorPrimary`，其余用 `colorOnSurfaceVariant` 的 35% 透明。
     *
     * 两个 drawable 画的是同一个纯白实心圆，颜色全部由 `backgroundTintList` 决定 ——
     * 这样切主题时不用重新 setBackground（drawable 里的 `?attr/` 不会随运行时换主题更新）。
     *
     * **必须是 `backgroundTintList`，不能是 `imageTintList`**：圆点是用
     * `setBackgroundResource` 画的，从头到尾没设过 `src`，而 `ImageView.imageTintList`
     * 只对 `src` 的 drawable 上色 —— 写在那里是空操作，点在浅色主题上就是一坨
     * 看不见的纯白（默认主题 `Theme.Suisheng.Base` 的 `colorSurface` 是浅米色）。
     */
    private fun updateDots() {
        val container = binding.pageDots
        if (container.childCount == 0) return
        val active = ColorStateList.valueOf(
            MaterialColors.getColor(container, com.google.android.material.R.attr.colorPrimary)
        )
        val inactiveColor = MaterialColors.getColor(
            container,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
        val inactive = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(inactiveColor, DOT_INACTIVE_ALPHA)
        )
        val current = binding.swipeDeck.currentIndex
        for (i in 0 until container.childCount) {
            val dot = container.getChildAt(i) as? ImageView ?: continue
            val selected = i == current
            dot.setBackgroundResource(
                if (selected) R.drawable.bg_page_dot_on else R.drawable.bg_page_dot_off
            )
            dot.backgroundTintList = if (selected) active else inactive
        }
    }

    // ---------------------------------------------------------------- 封面尺寸

    /**
     * 封面边长跟着格子算：`min(可用宽, 可用高)`，两边各让 8dp 余量，夹在 120dp ~ 420dp。
     *
     * 为什么用 `addOnLayoutChangeListener` 而不是一次性的 `doOnLayout`：
     * 旋转屏幕、切分屏、抽屉展开都会让格子变成另一个尺寸，只算一次会留下一个
     * 「封面比格子大」或「封面缩成一小块」的旧值。
     *
     * 为什么必须先比对尺寸再写 `layoutParams`：改子 View 的布局参数会再触发一次
     * 该容器的 layout，不做去重就是「layout → 监听 → layout」的死循环。
     */
    private fun setupCoverSizing() {
        binding.pageCover.addOnLayoutChangeListener { view, _, _, right, bottom, _, _, _, _ ->
            val width = right - view.left
            val height = bottom - view.top
            if (width > 0 && height > 0) {
                val pad = dp(COVER_PADDING_DP)
                val size = min(width - 2 * pad, height - 2 * pad)
                    .coerceIn(dp(COVER_MIN_SIZE_DP), dp(COVER_MAX_SIZE_DP))
                applyCoverSize(size)
            }
        }
    }

    private fun applyCoverSize(size: Int) {
        if (size <= 0) return
        val params = binding.vinylRecord.layoutParams ?: return
        if (params.width == size && params.height == size) return
        params.width = size
        params.height = size
        binding.vinylRecord.layoutParams = params
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- 歌曲信息

    /**
     * 信息页的初始数据 + 封面。
     *
     * 第一次进页面就把数据读好（一次 IO 换一次「滑过去不是空白」），之后由
     * [onPlaybackChanged] 与 [setupSwipeDeck] 里的 `onPageChanged` 按需刷新。
     * 封面**不在这里读** —— 播放服务解出内嵌封面往往晚于曲目信息，
     * 由 [onCoverChanged] 单独补（见那里的注释）。
     */
    private fun setupSongInfo() {
        loadSongInfo()
    }

    /**
     * 读当前曲目的技术参数 + 封面。已完成 / 在途两个键都命中时直接返回
     * （详见 [songInfoKey] 与 [songInfoLoading] 的注释）。
     *
     * `MediaMetadataRetriever` 与封面解码都必须离开主线程；`bind()` 只做
     * setText / setImageBitmap，所以整段是「IO 读 → 主线程写」两段，
     * 没有跨线程共享的可变状态。
     */
    private fun loadSongInfo() {
        val song = PlaybackStateHolder.currentSong
        val key = song?.path
        if (key == songInfoKey || key == songInfoLoading) return
        if (song == null) {
            songInfoKey = null
            binding.songInfoPage.bind(null, null, null)
            return
        }
        val hasLyrics = PlaybackStateHolder.lrcLines.isNotEmpty()
        val onlineLyrics = settings.onlineLyricsEnabled
        val appContext = requireContext().applicationContext
        songInfoLoading = key
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val payload = withContext(Dispatchers.IO) {
                    SongInfoLoader.load(appContext, song, hasLyrics, onlineLyrics) to
                        loadInfoCover(appContext, song)
                }
                if (_binding == null) return@launch
                binding.songInfoPage.bind(song, payload.first, payload.second)
                // 只有真的画上去了才算"读过"。协程被取消时（旋转/切页）这一行不会执行，
                // 下次滑到信息页会重新读一遍，而不是永远停在空白。
                songInfoKey = key
            } finally {
                // 取消也会走到这里，所以在途键不会卡死；只清自己那一把，别误删别人的
                if (songInfoLoading == key) songInfoLoading = null
            }
        }
    }

    /**
     * 取信息页封面的位图。**只在 IO 线程调用。**
     *
     * 三级来源，由近到远：
     * ① 播放服务已经落盘的当前封面（`PlaybackStateHolder.coverArtPath`）——
     *    当前曲目必然已经解过一次，直接复用，不必再开一个 `MediaMetadataRetriever`；
     * ② 自己抽一次内嵌封面：`CoverArtFetcher.fetchEmbeddedOnly` 把 `embeddedPicture`
     *    写到 `cacheDir/song_info_cover/<hash>.img` 并返回该路径；
     * ③ 都没有 → null，界面保留占位图。
     *
     * 一律走 `CoverBitmap.decode(path, maxPx)`：它内部先 `inJustDecodeBounds`
     * 再按 `inSampleSize` 降采样。车机上的内嵌封面动辄 3000×3000（ARGB_8888 ≈ 36MB），
     * 直接 `BitmapFactory.decodeFile` 就是一次 OOM。
     *
     * 注意 ① 有一瞬间可能是**上一首**的封面（播放服务换图比状态回调晚一帧）。
     * 那种情况下 [onCoverChanged] 紧接着会送来新路径并再刷一次，所以不做去抖。
     */
    private fun loadInfoCover(appContext: Context, song: Song): Bitmap? {
        CoverBitmap.decode(PlaybackStateHolder.coverArtPath, INFO_COVER_PX)?.let { return it }
        return runCatching {
            val dir = File(appContext.cacheDir, INFO_COVER_DIR)
            // 先收拾一次再写：这张图是「为一首歌临时抽出来的一次性产物」，
            // 换歌之后基本不会再被用到（复用走的是 ① 那条 coverArtPath 快路），
            // 不清理就会在车机上无限增长 —— 每首几十 KB × 几千首是很实在的几百 MB。
            pruneInfoCoverCache(dir)
            val cacheFile = File(dir, "${song.path.hashCode()}.img")
            val path = CoverArtFetcher(appContext).fetchEmbeddedOnly(song, cacheFile)
            CoverBitmap.decode(path, INFO_COVER_PX)
        }.getOrNull()
    }

    /**
     * 把信息页封面缓存裁到 [INFO_COVER_CACHE_KEEP] 个以内。
     *
     * 只在文件数超过 [INFO_COVER_CACHE_MAX] 时才动手，按 `lastModified()` 升序删最老的。
     * 特意不引入 LRU 库：这里缓存的是可再生的缩略图，误删了下次滑到信息页重抽一次即可，
     * 为它加一个依赖不值。整段 `runCatching` —— 清理失败不该影响「把封面显示出来」。
     */
    private fun pruneInfoCoverCache(dir: File) {
        runCatching {
            val files = dir.listFiles() ?: return@runCatching
            if (files.size <= INFO_COVER_CACHE_MAX) return@runCatching
            files.sortedBy { it.lastModified() }
                .take(files.size - INFO_COVER_CACHE_KEEP)
                .forEach { it.delete() }
        }
    }

    // ---------------------------------------------------------------- 播放状态

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
        // 只在信息页可见时重读参数：停在封面页就让它保持旧值，
        // 切回信息页时 `onPageChanged` 会补一次（那边同样走 songInfoKey 去重）。
        if (currentPageIsInfo()) loadSongInfo()
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
        // 信息页的封面单独补：播放服务解出内嵌封面通常晚于曲目信息，
        // 那时只需要换一张图，不该把整页 12 行文字重读一遍。
        // 复用 viewLifecycleOwner 的作用域，不新开线程池。
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { CoverBitmap.decode(coverPath, INFO_COVER_PX) }
            if (_binding == null) return@launch
            binding.songInfoPage.setCoverBitmap(bitmap)
        }
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
        // 歌词**不在这里**刷：调用方 onPlaybackChanged 已经拿着最新的 positionMs 刷过一次。
        // 放在这里会让 8Hz 的进度回调每帧刷两遍（两次线性 findIndex + 两次 currentStyle()，
        // 后者内部还要新建 SettingsRepository 读 prefs），低端车机上就是白掉的 CPU。
        // 唯一不带位置的调用点 onViewCreated 自己在后面补一次 update。
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

    /** 功能行第 4 格：弹出真实队列面板。 */
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
     * 功能行第 5 格：歌曲信息 + 播放速度面板。
     *
     * 与滑动到第 1 页看到的「歌曲信息」不冲突：这里是**可操作的**面板
     * （能改倍速、能看时长格式），第 1 页是**只读的参数表**。
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
     * 面板就地改 [eqSettings] 这份实例（setter 同时落盘），回调里只需要**通知服务**重新施加。
     *
     * `supported` 取 [AudioEffectController.lastKnownSupported]：服务里第一次施加过之后就有结论，
     * 设备不支持时面板把滑块 / chip / 开关全置灰并提示；还没探测过（`null`）按「支持」处理。
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
     * 音效指令单独发，**不走 sendAction**：后者在队列为空时直接 Toast「没有歌曲」并 return，
     * 而音效在没歌时也应该能设（下次播放生效）。
     *
     * 用 `startService` 而不是 `startForegroundService`：`ACTION_APPLY_EQ` 在服务端
     * 不会 `startForeground()`，而 `startForegroundService` 要求 5 秒内必须起通知。
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
        /** 解码上限：768px 最长边（避免 3000×3000 封面在车机上 OOM）。 */
        const val COVER_MAX_PX = 768

        /**
         * 歌曲信息页头部封面的解码上限。
         *
         * 显示格子只有 96dp，×3 密度 = 288px；取 256 已经足够（略小于格子，
         * 由 `centerCrop` 补齐），再大只是白占内存 —— 黑色车机上这点余量很值钱。
         */
        const val INFO_COVER_PX = 256

        /** 信息页封面的落盘目录（`cacheDir/song_info_cover`）。 */
        const val INFO_COVER_DIR = "song_info_cover"

        /** 缓存文件数超过这个值就触发清理，清到 [INFO_COVER_CACHE_KEEP] 个。 */
        const val INFO_COVER_CACHE_MAX = 60
        const val INFO_COVER_CACHE_KEEP = 40

        /** 队列为空时功能行队列键的透明度（有队列时回到 1f）。 */
        const val QUEUE_EMPTY_ALPHA = 0.5f

        /** 页码点未选中态的 alpha（0.35 × 255 ≈ 89）。 */
        const val DOT_INACTIVE_ALPHA = 89

        /** 封面格子四周留白与尺寸上下限（dp）。 */
        const val COVER_PADDING_DP = 8
        const val COVER_MIN_SIZE_DP = 120
        const val COVER_MAX_SIZE_DP = 420
    }
}
