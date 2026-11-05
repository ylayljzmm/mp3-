package com.suisheng.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.suisheng.player.data.PlaybackBootstrap
import com.suisheng.player.data.PlaylistCache
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.databinding.ActivityMainBinding
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder
import com.suisheng.player.ui.AppThemeManager
import com.suisheng.player.ui.ImmersiveHelper
import com.suisheng.player.ui.MainHost
import com.suisheng.player.ui.MainPagerAdapter
import com.suisheng.player.ui.PlayerFragment
import com.suisheng.player.ui.PlaylistFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), MainHost {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsRepository
    private var musicSongs: List<Song> = emptyList()

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { scanMusic() }

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

        binding.viewPager.adapter = MainPagerAdapter(this)
        binding.viewPager.isUserInputEnabled = false
        binding.viewPager.offscreenPageLimit = 3
        applyAppTheme()

        binding.bottomNav.selectedItemId = R.id.nav_player
        binding.viewPager.setCurrentItem(1, false)

        binding.bottomNav.setOnItemSelectedListener { item ->
            val index = when (item.itemId) {
                R.id.nav_playlist -> 0
                R.id.nav_player -> 1
                else -> 2
            }
            binding.viewPager.setCurrentItem(index, false)
            if (item.itemId != R.id.nav_player) {
                applyAppTheme()
            }
            true
        }

        restoreCachedLibraries(resumePlayback = false)
        requestPermissionsAndScan()
    }

    override fun onResume() {
        super.onResume()
        ImmersiveHelper.apply(this)
        // 旧实现在这里额外回刷播放页底栏的封面取色；该链路已随主题重构移除，
        // 底栏颜色由 applyAppTheme() 统一按主题 token 处理。
        applyAppTheme()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ImmersiveHelper.apply(this)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleSteeringWheelKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

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
        (supportFragmentManager.findFragmentByTag("f1") as? PlayerFragment)?.refreshLyricStyle()
    }

    override fun switchToTab(index: Int) {
        binding.viewPager.setCurrentItem(index, false)
        binding.bottomNav.menu.getItem(index).isChecked = true
    }

    override fun scanMusic(onDone: ((Int) -> Unit)?) {
        // lifecycleScope instead of an orphan CoroutineScope: the old scope outlived the
        // activity, so a scan started before the head unit went to sleep could resume
        // against a destroyed FragmentManager. The whole body is also guarded — a scan
        // failure must degrade to "no songs", never to a crash on launch.
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
            onDone?.invoke(musicCount)
        }
    }

    override fun allSongs(): List<Song> = musicSongs

    override fun refreshAppTheme() {
        applyAppTheme()
    }

    private fun refreshPlaylistFragment() {
        (supportFragmentManager.findFragmentByTag("f0") as? PlaylistFragment)?.refreshFromHost()
    }

    private fun applyAppTheme() {
        val palette = AppThemeManager.palette(this, settings)
        binding.root.setBackgroundColor(palette.background)
        AppThemeManager.applyBottomNav(binding.bottomNav, palette, dock = binding.bottomDock)
        // API 29+ 系统会在浅色底上给导航栏加一条深色对比带，页面底部因此出现黑缝
        // （`design/04 §3.2` 第③条）。关掉它，导航栏颜色完全由我们决定。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        window.navigationBarColor = palette.bottomNavBg
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
}
