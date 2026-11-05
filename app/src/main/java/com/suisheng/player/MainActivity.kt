package com.suisheng.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.suisheng.player.data.HiddenFolderStore
import com.suisheng.player.data.PlaybackBootstrap
import com.suisheng.player.data.PlaylistCache
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.databinding.ActivityMainBinding
import com.suisheng.player.model.LibraryGrouper
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder
import com.suisheng.player.ui.AboutFragment
import com.suisheng.player.ui.AppThemeManager
import com.suisheng.player.ui.Destination
import com.suisheng.player.ui.DrawerPanelController
import com.suisheng.player.ui.FavoriteFragment
import com.suisheng.player.ui.GroupedLibraryFragment
import com.suisheng.player.ui.ImmersiveHelper
import com.suisheng.player.ui.LocalPlaylistFragment
import com.suisheng.player.ui.MainHost
import com.suisheng.player.ui.MiniPlayerController
import com.suisheng.player.ui.OnlineMusicFragment
import com.suisheng.player.ui.PageBackHandler
import com.suisheng.player.ui.PageHeaderController
import com.suisheng.player.ui.PlayerFragment
import com.suisheng.player.ui.PodcastFragment
import com.suisheng.player.ui.RadioFragment
import com.suisheng.player.ui.SettingsFragment
import com.suisheng.player.ui.ThemeFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面。导航是「左侧抽屉 + Fragment 事务 + 一条全局页头」：
 *
 * - 抽屉里是 10 条真正的目的地（歌曲 / 专辑 / 艺术家 / 文件夹 / 收藏 / 在线 / 电台 / 播客 /
 *   设置 / 关于）。原来的「首页」已经拆掉，它内部的 4 个分区标签各自变成了条目；
 * - 页面之间用 Fragment 事务（`replace`）切换，不再保留常驻 Fragment；
 * - 页面切换走**显式目的地栈** [destStack]：从抽屉跳页也会入栈，所以「连跳三页」
 *   能「连退三次」，退到栈空才退出应用。页面内部如果还有一层（歌手详情 / 歌单详情 /
 *   文件夹下钻），由页面自己实现 [PageBackHandler] 先消费掉；
 * - 播放状态不在 Fragment 里，而在 `PlaybackStateHolder` + `MusicPlaybackService`，
 *   所以页面被销毁重建不会打断播放。
 */
class MainActivity : AppCompatActivity(), MainHost {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsRepository
    private lateinit var drawer: DrawerPanelController
    private lateinit var miniPlayer: MiniPlayerController

    /**
     * 对外可见的曲库：**已经**过「隐藏文件夹」过滤。
     *
     * 全项目只有这一个列表是「用户能看到的歌」——`allSongs()`、播放队列、抽屉计数
     * 都从它出发，所以隐藏的效果只需要在这里体现一次，不会出现某个页面忘了过滤。
     */
    private var musicSongs: List<Song> = emptyList()

    /**
     * 未过滤的原始曲库（缓存恢复 / 扫描结果）。
     *
     * 必须单独留一份：可见列表是「原始列表的函数」。如果在 [musicSongs] 上反复过滤，
     * 恢复某个文件夹时就没法把歌找回来了（被过滤掉的歌已经不在列表里）。
     */
    private var rawMusicSongs: List<Song> = emptyList()

    /** 隐藏文件夹的持久化；首次访问时才开 prefs（[onCreate] 早期用不到）。 */
    private val hiddenFolders by lazy { HiddenFolderStore(this) }

    /** 当前挂在 `contentHost` 上的页面；Fragment 被销毁重建时由 FragmentManager 负责，这里只做引用。 */
    private var currentFragment: Fragment? = null

    /**
     * 当前目的地。冷启动兜底是**播放页**，不是任何一个抽屉条目
     * （`design/04` 的「播放页为中心」模型，用户在期 2 方案确认时明确选过「默认落播放页」）。
     * 播放页本身不在抽屉里（[Destination.shownInDrawer] = false），所以它只作为
     * 「什么时候都不在别的页」时的归宿存在。
     */
    private var currentDestination: Destination = Destination.PLAYER

    /**
     * 目的地返回栈，栈顶 = 「上一页」。
     *
     * 规则（见 [goBack]）：
     * ① 只有**真正发生了切页**才入栈；
     * ② 入栈前去重相邻重复项 —— 抽屉连点同一页、页面内反复点同一个入口，
     *    都不该在栈里堆出一串相同项，否则「返回」要按好几次才有反应；
     * ③ 从抽屉直接跳页**不清空**栈。用户的心理模型是「我点了三页，就能连退三次」，
     *    清栈会让返回键直接退出应用。
     */
    private val destStack = ArrayDeque<Destination>()

    /** 全局页头；在 [setupPageHeader] 里创建，之后由 [showDestination] / 页面回写驱动。 */
    private var header: PageHeaderController? = null

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { scanMusic() }

    /**
     * 唯一的返回键处理者：抽屉开着 → 收抽屉；否则 → [goBack]（先让页面退内层，
     * 再弹目的地栈，栈空则退出）。
     *
     * 边缘滑动已经在 2c-2 关闭（见 `setupDrawer` 里的 `LOCK_MODE_LOCKED_CLOSED`），
     * 所以返回键是回退的主通道，必须常驻启用。
     */
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                return
            }
            goBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = SettingsRepository(this)
        settings.applyTheme()
        // 主题在 super.onCreate 之前套用：此时 inflate 出的每个 View 都会按新 theme 取色，
        // 不存在「先按旧色画一遍再刷新」的中间帧（`04 §2.5`）。
        setTheme(settings.themeStyleRes())
        super.onCreate(savedInstanceState)
        ImmersiveHelper.apply(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMiniPlayer()
        // 页头必须先于抽屉建好：抽屉里恢复出来的目的地会立刻走 showDestination()，
        // 那里要 header.bind(...)，晚一步就会出现「冷启动后页头空白」。
        setupPageHeader()
        setupDrawer(savedInstanceState)
        applyAppTheme()

        restoreCachedLibraries(resumePlayback = false)
        requestPermissionsAndScan()
    }

    override fun onStart() {
        super.onStart()
        PlaybackStateHolder.addListener(miniPlayer)
    }

    override fun onStop() {
        PlaybackStateHolder.removeListener(miniPlayer)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        ImmersiveHelper.apply(this)
        applyAppTheme()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ImmersiveHelper.apply(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DESTINATION, currentDestination.name)
        // 返回栈也要一起存：主题页里换配色会 recreate()，不存的话「设置 → 外观 → 返回」
        // 会退到错误的地方（重建后栈是空的，返回键直接退出应用）。
        outState.putStringArrayList(KEY_DEST_STACK, ArrayList(destStack.map { it.name }))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleSteeringWheelKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- 抽屉

    /** 建全局页头：返回 → 退一层，汉堡 → 开抽屉。 */
    private fun setupPageHeader() {
        header = PageHeaderController(
            activity = this,
            root = binding.pageHeader,
            onBack = { goBack() },
            onMenu = { openDrawer() },
        )
    }

    private fun setupDrawer(savedInstanceState: Bundle?) {
        binding.drawerLayout.setScrimColor(DRAWER_SCRIM_COLOR)
        // 只认按钮、不做边缘滑动（03 §6.1 的裁定）。LOCK_MODE_LOCKED_CLOSED 只拦手势，
        // openDrawer() 仍然有效，所以页头的 ☰ 与代码调用不受影响。
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        applyDrawerWidth()
        drawer = DrawerPanelController(
            activity = this,
            binding = binding,
            onSelect = { selectDestination(it) },
            onScan = {
                scanMusic { count ->
                    if (count > 0) {
                        Toast.makeText(this, getString(R.string.scan_done, count), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onTheme = { openTheme() },
        )
        drawer.bind()

        currentDestination = restoreDestination(savedInstanceState)
        restoreDestinationStack(savedInstanceState)
        if (savedInstanceState == null) {
            showDestination(currentDestination)
        } else {
            // FragmentManager 已经恢复了上次的页面，这里只把引用抓回来、并把页头对齐。
            currentFragment = supportFragmentManager.findFragmentById(R.id.contentHost)
            header?.bind(currentDestination, canGoBack = destStack.isNotEmpty())
        }
        drawer.select(currentDestination)

        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    /**
     * 恢复当前目的地。
     *
     * **老版本存档里存的是 "HOME"，而这个枚举值已经删掉了** —— `Destination.fromName`
     * 会返回 null，如果直接把结果塞进非空字段，升级后第一次冷启动就 NPE。
     * 所以这里三档兜底：拿不到名字（含 "HOME"）→ 歌曲页；拿到了但页面未落地 → 播放页；
     * 冷启动（没有存档）→ 播放页。
     */
    private fun restoreDestination(savedInstanceState: Bundle?): Destination {
        val restored = Destination.fromName(savedInstanceState?.getString(KEY_DESTINATION))
        return when {
            restored == null ->
                if (savedInstanceState == null) Destination.PLAYER else Destination.SONGS

            !restored.available -> Destination.PLAYER
            else -> restored
        }
    }

    /** 恢复返回栈：只保留仍然存在的目的地（旧存档里可能残留已删除的名字）。 */
    private fun restoreDestinationStack(savedInstanceState: Bundle?) {
        destStack.clear()
        savedInstanceState?.getStringArrayList(KEY_DEST_STACK)
            ?.mapNotNull { Destination.fromName(it) }
            ?.filter { it.available }
            ?.forEach { destStack.addLast(it) }
    }

    /** 迷你条：点整条回播放页，点播放键就地播放/暂停。 */
    private fun setupMiniPlayer() {
        miniPlayer = MiniPlayerController(this, binding) {
            selectDestination(Destination.PLAYER)
        }
        miniPlayer.bind()
    }

    /** 横屏 400dp，竖屏 300dp；屏宽不足 360dp 时取 0.84 × 屏宽（`design/04 §3.5`）。 */
    private fun applyDrawerWidth() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val width = if (landscape) {
            resources.getDimensionPixelSize(R.dimen.drawer_width_land)
        } else {
            minOf(
                resources.getDimensionPixelSize(R.dimen.drawer_width_port),
                (resources.displayMetrics.widthPixels * PORTRAIT_DRAWER_RATIO).toInt(),
            )
        }
        val params = binding.drawerPanel.layoutParams
        if (params.width != width) {
            params.width = width
            binding.drawerPanel.layoutParams = params
        }
    }

    override fun openDrawer() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) return
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    override fun selectDestination(destination: Destination) {
        if (!destination.available) {
            // 抽屉保持打开：让用户直接改选别的，而不是「点一下什么都没发生」。
            Toast.makeText(this, R.string.drawer_coming_soon, Toast.LENGTH_SHORT).show()
            return
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        if (destination == currentDestination) return
        pushDestination(currentDestination)
        currentDestination = destination
        showDestination(destination)
        drawer.select(destination)
        applyAppTheme()
    }

    /**
     * 入栈并去重。
     *
     * 去重只比**栈顶**：相邻重复项没有任何信息量（返回时会立刻被 [goBack] 跳过一个），
     * 而隔开的重复项（`A → B → A`）是有意义的，必须保留。
     */
    private fun pushDestination(destination: Destination) {
        if (destStack.lastOrNull() == destination) return
        destStack.addLast(destination)
    }

    override fun openTheme() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        if (currentDestination == Destination.THEME) return
        // 走 selectDestination 而不是自己改 currentDestination：主题页也要入栈，
        // 这样「设置 → 外观 → 返回」能回到设置，而不是一律回播放页。
        selectDestination(Destination.THEME)
    }

    /**
     * 退一层。
     *
     * 先问当前页面要不要自己消费（歌手详情 / 歌单详情 / 文件夹下钻），
     * 再弹目的地栈。栈空就直接退出应用 —— 播放页栈空时同样退，与改造前一致。
     */
    private fun goBack() {
        val inner = currentFragment as? PageBackHandler
        if (inner != null && inner.handlePageBack()) {
            header?.setCanGoBack(destStack.isNotEmpty() || inner.hasInnerLevel())
            return
        }
        while (destStack.isNotEmpty()) {
            val previous = destStack.removeLast()
            if (previous == currentDestination) continue
            currentDestination = previous
            showDestination(previous)
            drawer.select(previous)
            applyAppTheme()
            return
        }
        finish()
    }

    override fun navigateUp() {
        // 主题页 / 设置页等还在用这个入口，语义与页头的返回箭头保持完全一致。
        goBack()
    }

    /**
     * 切页。
     *
     * 用 `commit()`（异步）而不是 `commitNow()`：歌曲列表的处理顺序是
     * 「先切到播放页、再读自己的列表算播放队列」，同步销毁会立刻把它的 `_binding` 置空。
     */
    private fun showDestination(destination: Destination) {
        val fragment = createFragment(destination)
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.contentHost, fragment, destination.tag)
            .commit()
        currentFragment = fragment
        // 播放页自己不挂迷你条：那一页已经有整套控件，再挂一条是重复
        miniPlayer.setHiddenForDestination(destination == Destination.PLAYER)
        header?.bind(destination, canGoBack = destStack.isNotEmpty())
    }

    private fun createFragment(destination: Destination): Fragment = when (destination) {
        Destination.SONGS -> LocalPlaylistFragment()
        Destination.ALBUMS -> GroupedLibraryFragment.newInstance(GroupedLibraryFragment.Grouping.ALBUM)
        Destination.ARTISTS -> GroupedLibraryFragment.newInstance(GroupedLibraryFragment.Grouping.ARTIST)
        Destination.FOLDERS -> GroupedLibraryFragment.newInstance(GroupedLibraryFragment.Grouping.FOLDER)
        Destination.FAVORITES -> FavoriteFragment()
        Destination.ONLINE -> OnlineMusicFragment()
        Destination.RADIO -> RadioFragment()
        Destination.PODCAST -> PodcastFragment()
        Destination.SETTINGS -> SettingsFragment()
        Destination.ABOUT -> AboutFragment()
        Destination.PLAYER -> PlayerFragment()
        Destination.THEME -> ThemeFragment()
    }

    // ---------------------------------------------------------------- 页头回写

    /**
     * 供页面把子层级的说明交给页头副标题（歌手名 / 歌单名 / 「共 N 张专辑」）。
     *
     * 页面通过 `Fragment.setPageSubtitle` 这个扩展调用，不需要 import 具体 Activity 类型。
     */
    fun setPageSubtitle(text: CharSequence?) {
        header?.setSubtitle(text)
    }

    /**
     * 页面内部层级变化后重算左上角按钮。
     *
     * 有内层（歌单详情 / 歌手详情 / 文件夹子目录）时也要显示返回箭头，
     * 否则用户在这一层只看到一个汉堡键，「退一层」无处可点。
     */
    fun refreshPageHeaderBack() {
        val inner = (currentFragment as? PageBackHandler)?.hasInnerLevel() == true
        header?.setCanGoBack(destStack.isNotEmpty() || inner)
    }

    // ------------------------------------------------------------ 播放/扫描

    private fun handleSteeringWheelKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (PlaybackStateHolder.songs.isEmpty()) return false
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> MusicPlaybackService.ACTION_NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MusicPlaybackService.ACTION_PREV
            KeyEvent.KEYCODE_MEDIA_PLAY -> MusicPlaybackService.ACTION_PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> MusicPlaybackService.ACTION_PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> MusicPlaybackService.ACTION_TOGGLE
            KeyEvent.KEYCODE_MEDIA_STOP -> MusicPlaybackService.ACTION_STOP
            else -> return false
        }
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MusicPlaybackService::class.java).apply { this.action = action }
            )
        }
        return true
    }

    private fun restoreCachedLibraries(resumePlayback: Boolean = true) {
        // 缓存里的曲库同样要过一遍隐藏过滤：隐藏项存在 prefs 里，冷启动时列表必须一致，
        // 否则会先闪出一批「已隐藏」的歌、等扫描完才消失。
        val loaded = PlaybackBootstrap.loadCachedMusic(this)
        rawMusicSongs = loaded
        musicSongs = LibraryGrouper.visibleSongs(loaded, hiddenFolders.hiddenDirs())
        drawer.updateLibrary(musicSongs)
        binding.root.post {
            refreshCurrentLibraryPage()
            if (!resumePlayback || PlaybackStateHolder.isPlaying) return@post
            val library = settings.lastActiveLibrary
            val resumeList = resolveResumeList(library)
            if (resumeList.isNotEmpty()) {
                PlaybackBootstrap.resumeIfNeeded(this, resumeList, settings, library)
            }
        }
    }

    private fun resolveResumeList(library: LibraryKind): List<Song> = when (library) {
        LibraryKind.MUSIC -> musicSongs
        else -> PlaylistCache.loadQueue(this, library).ifEmpty { musicSongs }
    }

    override fun playSongAt(index: Int) {
        playSongSubset(musicSongs, index, LibraryKind.MUSIC)
    }

    override fun playSongSubset(subset: List<Song>, index: Int, library: LibraryKind) {
        if (subset.isEmpty() || index !in subset.indices) return
        settings.lastActiveLibrary = library
        PlaybackStateHolder.setActiveLibrary(library)
        if (PlaybackStateHolder.songs === subset) {
            PlaybackStateHolder.setCurrentIndex(index)
        } else {
            PlaybackStateHolder.setPlaylist(subset, index, library)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            PlaylistCache.saveQueue(this@MainActivity, subset, library)
        }
        val intent = Intent(this, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_PLAY_INDEX
            putExtra(MusicPlaybackService.EXTRA_INDEX, index)
            putExtra(MusicPlaybackService.EXTRA_LIBRARY, library.name)
        }
        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.onFailure {
            Toast.makeText(this, R.string.playback_start_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun notifyLyricStyleChanged() {
        val fragment = currentFragment as? PlayerFragment ?: return
        if (fragment.view == null) return
        fragment.refreshLyricStyle()
    }

    override fun scanMusic(onDone: ((Int) -> Unit)?) {
        // lifecycleScope instead of an orphan CoroutineScope: the old scope outlived the
        // activity, so a scan started before the head unit went to sleep could resume
        // against a destroyed FragmentManager. The whole body is also guarded — a scan
        // failure must degrade to "no songs", never to a crash on launch.
        drawer.setScanning(true)
        lifecycleScope.launch {
            val musicCount = runCatching {
                withContext(Dispatchers.IO) {
                    val scanned = PlaybackBootstrap.scanMusicLibrary(this@MainActivity, settings)
                    // 原始结果先落 raw，再派生出可见列表；顺序不能反，
                    // 否则下次重算过滤时只能在「已过滤」的列表上做减法。
                    rawMusicSongs = scanned
                    musicSongs = LibraryGrouper.visibleSongs(scanned, hiddenFolders.hiddenDirs())
                    // 回报的是**可见**曲目数而不是扫描命中数：紧跟着的 Toast 和抽屉计数
                    // 都读这个口径，两个数字不一致会让用户以为扫描漏歌了。
                    musicSongs.size
                }
            }.getOrElse {
                android.util.Log.e("MainActivity", "scan failed", it)
                0
            }
            runCatching {
                if (PlaybackStateHolder.activeLibrary == LibraryKind.MUSIC && musicSongs.isNotEmpty()) {
                    PlaybackStateHolder.setPlaylist(musicSongs, library = LibraryKind.MUSIC)
                }
                refreshCurrentLibraryPage()
                if (!PlaybackStateHolder.isPlaying) {
                    val library = settings.lastActiveLibrary
                    val resumeList = resolveResumeList(library)
                    if (resumeList.isNotEmpty()) {
                        PlaybackBootstrap.resumeIfNeeded(this@MainActivity, resumeList, settings, library)
                    }
                }
            }.onFailure {
                android.util.Log.e("MainActivity", "post-scan refresh failed", it)
            }
            drawer.setScanning(false)
            drawer.updateLibrary(musicSongs)
            onDone?.invoke(musicCount)
        }
    }

    override fun allSongs(): List<Song> = musicSongs

    /**
     * 重算「原始曲库 − 隐藏目录」并刷新 UI。
     *
     * 刻意**不重扫全库**：隐藏只是可见性开关，曲目一首都没变，
     * 重扫一遍（车机上要几秒到几十秒）会让长按隐藏这个动作变得不可接受。
     *
     * 也刻意**不动播放队列**：正在播放的歌可能正好在刚被隐藏的目录里，
     * 把这批歌从队列里抽走会让当前曲目位置错位甚至跳歌。队列是「按当时的曲库生成的快照」，
     * 下次选歌时自然会用新的可见列表。
     */
    private fun applyHiddenFilter() {
        musicSongs = LibraryGrouper.visibleSongs(rawMusicSongs, hiddenFolders.hiddenDirs())
        drawer.updateLibrary(musicSongs)
        refreshCurrentLibraryPage()
    }

    override fun refreshHiddenFilter() {
        applyHiddenFilter()
    }

    override fun refreshAppTheme() {
        applyAppTheme()
    }

    /**
     * 曲库变化后刷新「正在看的那一页」。
     *
     * 首页那个壳（过去承载 4 个分区标签的 Fragment）删掉之后，「歌曲列表」直接挂在
     * SONGS 目的地上，而曲库变化（扫描完成 / 隐藏文件夹）要影响的不止一页：
     * 歌曲列表、三个分组页的计数、在线页「我的」里的本地收藏。所以这里按当前 Fragment
     * 的类型分发，而不是像原来那样只认「首页壳」一个类。
     *
     * `view == null` 的页面（已切走但事务还没走完）直接跳过：它下次 `onViewCreated`
     * 会自己读一遍最新的 `allSongs()`，不需要外部推动。
     */
    private fun refreshCurrentLibraryPage() {
        val fragment = currentFragment ?: return
        if (fragment.view == null) return
        when (fragment) {
            is LocalPlaylistFragment -> fragment.refreshFromHost()
            is GroupedLibraryFragment -> fragment.refreshFromHost()
            // 收藏页的列表是「曲库 ∩ 收藏」，曲库变了（扫描完成 / 隐藏文件夹）它也得重算；
            // 同理它自己长按取消收藏之后也调这个方法刷新自己。
            is FavoriteFragment -> fragment.refreshFromHost()
            is OnlineMusicFragment -> fragment.refreshLibrary()
            else -> Unit
        }
    }

    private fun applyAppTheme() {
        // 颜色已由 theme 在 inflate 阶段解析，这里只剩「窗口级」的两条栏色需要同步：
        // 它们不属于任何 View，布局里的 ?attr/ 管不到。
        val palette = AppThemeManager.palette(this, settings)
        // API 29+ 系统会在浅色底上给导航栏强加一条深色对比带，页面底部出现黑缝
        // （`design/04 §2.9` 第③条）。关掉它，导航栏颜色完全由我们决定。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        window.navigationBarColor = palette.background
        window.statusBarColor = android.graphics.Color.TRANSPARENT
    }

    private fun requestPermissionsAndScan() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            storagePermission.launch(needed.toTypedArray())
        } else {
            scanMusic { count ->
                if (count > 0) {
                    Toast.makeText(this, getString(R.string.scan_done, count), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun requiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private companion object {
        /** 抽屉遮罩：纯黑 alpha 0.62（`design/04 §3.5`）。0x9E = 158 ≈ 255 × 0.62。 */
        private val DRAWER_SCRIM_COLOR: Int = 0x9E000000.toInt()

        /** 保存当前目的地的键，值取 [Destination.name]。 */
        const val KEY_DESTINATION = "main_destination"

        /** 保存返回栈的键，值取 [Destination.name] 的列表（栈底在前）。 */
        const val KEY_DEST_STACK = "dest_stack"

        /** 竖屏抽屉宽与屏宽的比例上限（屏宽 < 360dp 时生效）。 */
        const val PORTRAIT_DRAWER_RATIO = 0.84f
    }
}
