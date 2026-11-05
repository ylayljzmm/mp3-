package com.suisheng.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.suisheng.player.data.PlaybackBootstrap
import com.suisheng.player.data.PlaylistCache
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.databinding.ActivityMainBinding
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder
import com.suisheng.player.ui.AppThemeManager
import com.suisheng.player.ui.Destination
import com.suisheng.player.ui.DrawerPanelController
import com.suisheng.player.ui.ImmersiveHelper
import com.suisheng.player.ui.LocalPlaylistFragment
import com.suisheng.player.ui.MainHost
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
    private var musicSongs: List<Song> = emptyList()

    /** 当前挂在 `contentHost` 上的页面；Fragment 被销毁重建时由 FragmentManager 负责，这里只做引用。 */
    private var currentFragment: Fragment? = null
    private var currentDestination: Destination = Destination.HOME

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { scanMusic() }

    /** 返回键：抽屉开着就先收抽屉，收起状态下不拦截（交给系统退出）。 */
    private val drawerBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
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

        setupDrawer(savedInstanceState)
        applyAppTheme()

        restoreCachedLibraries(resumePlayback = false)
        requestPermissionsAndScan()
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
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleSteeringWheelKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- 抽屉

    private fun setupDrawer(savedInstanceState: Bundle?) {
        binding.drawerLayout.setScrimColor(DRAWER_SCRIM_COLOR)
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
            onTheme = { openThemeSettings() },
        )
        drawer.bind()

        // 冷启动默认首页；进程被杀后重建沿用上次的目的地。未落地的条目一律回落首页。
        currentDestination = Destination.fromName(savedInstanceState?.getString(KEY_DESTINATION))
            ?.takeIf { it.available }
            ?: Destination.HOME
        if (savedInstanceState == null) {
            showDestination(currentDestination)
        } else {
            // FragmentManager 已经恢复了上次的页面，这里只把引用抓回来。
            currentFragment = supportFragmentManager.findFragmentById(R.id.contentHost)
        }
        drawer.select(currentDestination)

        onBackPressedDispatcher.addCallback(this, drawerBackCallback)
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                drawerBackCallback.isEnabled = true
            }

            override fun onDrawerClosed(drawerView: View) {
                drawerBackCallback.isEnabled = false
            }
        })
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
        val alreadyHere = destination == currentDestination
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        if (alreadyHere) return
        currentDestination = destination
        showDestination(destination)
        drawer.select(destination)
        applyAppTheme()
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
    }

    private fun createFragment(destination: Destination): Fragment = when (destination) {
        Destination.HOME -> PlaylistFragment()
        Destination.SONGS -> LocalPlaylistFragment()
        Destination.ONLINE -> OnlineMusicFragment()
        Destination.SETTINGS -> SettingsFragment()
        Destination.PLAYER -> PlayerFragment()
        // 未落地的 3 条在 selectDestination() 已被拦下，这里只是为了让 when 穷举。
        Destination.ALBUMS, Destination.ARTISTS, Destination.FOLDERS, Destination.ABOUT ->
            PlaylistFragment()
    }

    private fun openThemeSettings() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        if (supportFragmentManager.findFragmentByTag(ThemeFragment.TAG) != null) return
        ThemeFragment().show(supportFragmentManager, ThemeFragment.TAG)
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
        musicSongs = PlaybackBootstrap.loadCachedMusic(this)
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
                    PlaybackBootstrap.scanMusicLibrary(this@MainActivity, settings)
                        .also { musicSongs = it }
                        .size
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

        /** 竖屏抽屉宽与屏宽的比例上限（屏宽 < 360dp 时生效）。 */
        const val PORTRAIT_DRAWER_RATIO = 0.84f
    }
}
