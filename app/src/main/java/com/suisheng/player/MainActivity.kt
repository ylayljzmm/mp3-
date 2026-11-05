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
import com.suisheng.player.ui.GroupedLibraryFragment
import com.suisheng.player.ui.ImmersiveHelper
import com.suisheng.player.ui.LocalPlaylistFragment
import com.suisheng.player.ui.MainHost
import com.suisheng.player.ui.MiniPlayerController
import com.suisheng.player.ui.OnlineMusicFragment
import com.suisheng.player.ui.PlayerFragment
import com.suisheng.player.ui.PlaylistFragment
import com.suisheng.player.ui.SettingsFragment
import com.suisheng.player.ui.ThemeFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面。期 2c-1 起导航从「底部 3 tab + ViewPager2」换成「左侧抽屉 + Fragment 事务」：
 *
 * - 抽屉条目只有 4 条在本期真正落地（首页 / 歌曲 / 在线 / 设置），其余 4 条置灰占位；
 * - 页面之间用 [FragmentTransaction.replace] 切换，不再保留 3 个常驻 Fragment；
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
     * 当前目的地。冷启动兜底是**播放页**，不是首页（`design/04` 的「播放页为中心」模型，
     * 用户在期 2 方案确认时明确选过「默认落播放页」）。
     * 播放页本身不在抽屉里（[Destination.shownInDrawer] = false），所以它只作为
     * 「什么时候都不在别的页」时的归宿存在。
     */
    private var currentDestination: Destination = Destination.PLAYER

    /** 主题页是从哪一页进来的；返回时回到那里，而不是一律回播放页。 */
    private var themeOrigin: Destination? = null

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { scanMusic() }

    /**
     * 唯一的返回键处理者（`design/04 §3.0` 的「播放页为中心」模型）：
     * 抽屉开着 → 收抽屉；主题页 → 回来源页；其它二级页 → 回播放页；播放页 → 退出。
     *
     * 边缘滑动已经在 2c-2 关闭（见 `setupDrawer` 里的 `LOCK_MODE_LOCKED_CLOSED`），
     * 所以返回键是二级页回退的主通道，必须常驻启用。
     */
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                return
            }
            if (currentDestination == Destination.PLAYER) {
                finish()
                return
            }
            navigateUp()
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
        // 「主题页是从哪来的」也要一起存：主题页里换配色会 `recreate()`，
        // 不存的话重建后 themeOrigin 丢了，「设置 → 外观 → 返回」会错误地落到播放页。
        outState.putString(KEY_THEME_ORIGIN, themeOrigin?.name)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleSteeringWheelKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- 抽屉

    private fun setupDrawer(savedInstanceState: Bundle?) {
        binding.drawerLayout.setScrimColor(DRAWER_SCRIM_COLOR)
        // 只认按钮、不做边缘滑动（03 §6.1 的裁定）。LOCK_MODE_LOCKED_CLOSED 只拦手势，
        // openDrawer() 仍然有效，所以播放页的 ☰ 与代码调用不受影响。
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

        // 冷启动落播放页；进程被杀后重建沿用上次的目的地。未落地的条目一律回落播放页。
        // 只改 null 时的兜底 —— 存档里有值（哪怕就是 HOME）必须以存档为准。
        currentDestination = Destination.fromName(savedInstanceState?.getString(KEY_DESTINATION))
            ?.takeIf { it.available }
            ?: Destination.PLAYER
        // 只有「重建时正停在主题页」才需要恢复来源页；其余情况来源上下文已作废
        themeOrigin = if (currentDestination == Destination.THEME) {
            Destination.fromName(savedInstanceState?.getString(KEY_THEME_ORIGIN))
                ?.takeIf { it.available && it != Destination.THEME }
        } else {
            null
        }
        if (savedInstanceState == null) {
            showDestination(currentDestination)
        } else {
            // FragmentManager 已经恢复了上次的页面，这里只把引用抓回来。
            currentFragment = supportFragmentManager.findFragmentById(R.id.contentHost)
        }
        drawer.select(currentDestination)

        onBackPressedDispatcher.addCallback(this, backCallback)
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
        // 主动从抽屉切页 → 主题页的来源上下文作废（返回键按「二级页回播放页」走）
        themeOrigin = null
        currentDestination = destination
        showDestination(destination)
        drawer.select(destination)
        applyAppTheme()
    }

    override fun openTheme() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        if (currentDestination == Destination.THEME) return
        // 从播放页进来时不需要记来源：返回键本来就是「非播放页回播放页」
        themeOrigin = if (currentDestination == Destination.PLAYER) null else currentDestination
        currentDestination = Destination.THEME
        showDestination(Destination.THEME)
        drawer.select(Destination.THEME)
    }

    override fun navigateUp() {
        val origin = themeOrigin
        themeOrigin = null
        if (currentDestination == Destination.THEME && origin != null && origin.available) {
            currentDestination = origin
            showDestination(origin)
            drawer.select(origin)
            return
        }
        selectDestination(Destination.PLAYER)
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
    }

    private fun createFragment(destination: Destination): Fragment = when (destination) {
        Destination.HOME -> PlaylistFragment()
        Destination.SONGS -> LocalPlaylistFragment()
        Destination.ALBUMS -> GroupedLibraryFragment.newInstance(GroupedLibraryFragment.Grouping.ALBUM)
        Destination.ARTISTS -> GroupedLibraryFragment.newInstance(GroupedLibraryFragment.Grouping.ARTIST)
        Destination.FOLDERS -> GroupedLibraryFragment.newInstance(GroupedLibraryFragment.Grouping.FOLDER)
        Destination.ONLINE -> OnlineMusicFragment()
        Destination.SETTINGS -> SettingsFragment()
        Destination.ABOUT -> AboutFragment()
        Destination.PLAYER -> PlayerFragment()
        Destination.THEME -> ThemeFragment()
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
            refreshPlaylistFragment()
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
                refreshPlaylistFragment()
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
        refreshPlaylistFragment()
    }

    override fun refreshHiddenFilter() {
        applyHiddenFilter()
    }

    override fun refreshAppTheme() {
        applyAppTheme()
    }

    private fun refreshPlaylistFragment() {
        val fragment = currentFragment as? PlaylistFragment ?: return
        if (fragment.view == null) return
        fragment.refreshFromHost()
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

        /** 保存主题页来源页的键，值取 [Destination.name]；null 表示从播放页进来的。 */
        const val KEY_THEME_ORIGIN = "theme_origin"

        /** 竖屏抽屉宽与屏宽的比例上限（屏宽 < 360dp 时生效）。 */
        const val PORTRAIT_DRAWER_RATIO = 0.84f
    }
}
