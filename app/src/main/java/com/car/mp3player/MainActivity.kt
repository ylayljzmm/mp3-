package com.car.mp3player

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
import com.car.mp3player.data.PlaybackBootstrap
import com.car.mp3player.data.PlaylistCache
import com.car.mp3player.data.SettingsRepository
import com.car.mp3player.databinding.ActivityMainBinding
import com.car.mp3player.model.LibraryKind
import com.car.mp3player.model.Song
import com.car.mp3player.playback.PlaybackStateHolder
import com.car.mp3player.ui.AppThemeManager
import com.car.mp3player.ui.ImmersiveHelper
import com.car.mp3player.ui.MainHost
import com.car.mp3player.ui.MainPagerAdapter
import com.car.mp3player.ui.PlayerFragment
import com.car.mp3player.ui.PlaylistFragment
import kotlinx.coroutines.CoroutineScope
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
        setTheme(settings.appTheme().styleRes)
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
        applyAppTheme()
        if (binding.viewPager.currentItem == 1) {
            (supportFragmentManager.findFragmentByTag("f1") as? PlayerFragment)?.syncBottomNavTheme()
        }
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
        CoroutineScope(Dispatchers.Main).launch {
            val musicCount = withContext(Dispatchers.IO) {
                PlaybackBootstrap.scanMusicLibrary(this@MainActivity, settings).also { musicSongs = it }.size
            }
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
            onDone?.invoke(musicCount)
        }
    }

    override fun allSongs(): List<Song> = musicSongs

    override fun refreshAppTheme() {
        applyAppTheme()
    }

    override fun syncPlayerBottomNav(backgroundColor: Int) {
        if (binding.viewPager.currentItem != 1) return
        val palette = AppThemeManager.palette(this, settings)
        AppThemeManager.applyPlayerBottomNav(binding.bottomNav, palette, backgroundColor, binding.bottomDock)
        window.navigationBarColor = AppThemeManager.playerBottomNavColors(palette, backgroundColor)
    }

    private fun refreshPlaylistFragment() {
        (supportFragmentManager.findFragmentByTag("f0") as? PlaylistFragment)?.refreshFromHost()
    }

    private fun applyAppTheme() {
        val palette = AppThemeManager.palette(this, settings)
        binding.root.setBackgroundColor(palette.background)
        AppThemeManager.applyBottomNav(binding.bottomNav, palette, dock = binding.bottomDock)
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
