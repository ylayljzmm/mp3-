package com.suisheng.player.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.suisheng.player.MusicPlaybackService
import com.suisheng.player.R
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.databinding.DialogLyricSearchBinding
import com.suisheng.player.databinding.FragmentPlayerBinding
import com.suisheng.player.model.LrcLine
import com.suisheng.player.model.PlaybackMode
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder
import com.suisheng.player.util.AlbumColorExtractor
import com.google.android.material.slider.Slider
import kotlin.math.max

class PlayerFragment : Fragment(), PlaybackStateHolder.Listener {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!
    private var userSeeking = false
    private lateinit var settings: SettingsRepository
    private var renderedStateKey: String? = null
    private var defaultThemeColor = Color.parseColor("#FF141B24")

    /** 竖屏（手机）下是否只显示歌词 */
    private var portraitLyricsOnly = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsRepository(requireContext())
        binding.btnPlayPause.setOnClickListener { sendAction(MusicPlaybackService.ACTION_TOGGLE) }
        binding.btnNext.setOnClickListener { sendAction(MusicPlaybackService.ACTION_NEXT) }
        binding.btnPrev.setOnClickListener { sendAction(MusicPlaybackService.ACTION_PREV) }
        binding.btnMode.setOnClickListener { toggleMode() }
        binding.btnLyrics.setOnClickListener { toggleLyrics() }
        binding.btnDrawerMenu.setOnClickListener { (activity as? MainHost)?.openDrawer() }

        binding.progressSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                userSeeking = false
                seekTo(slider.value.toLong())
            }
        })

        binding.scrollLyricView.setOnLongClickListener {
            showLyricSearchDialog()
            true
        }

        applyLayout()
        renderState()
        renderCover(PlaybackStateHolder.coverArtPath)
        updateProgressUi(PlaybackStateHolder.positionMs, PlaybackStateHolder.durationMs)
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** 歌词按钮：横屏在「封面+歌词」与「仅封面」间切换；竖屏在「封面」与「整屏歌词」间切换 */
    private fun toggleLyrics() {
        if (isLandscape()) {
            val showing = binding.scrollLyricView.visibility == View.VISIBLE
            val cs = ConstraintSet()
            cs.clone(binding.rootConstraint)
            if (showing) {
                cs.setVisibility(binding.scrollLyricView.id, View.GONE)
                cs.clear(binding.leftPanel.id, ConstraintSet.END)
                cs.connect(binding.leftPanel.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            } else {
                cs.setVisibility(binding.scrollLyricView.id, View.VISIBLE)
                cs.clear(binding.leftPanel.id, ConstraintSet.END)
                cs.connect(binding.leftPanel.id, ConstraintSet.END, R.id.stageCenterGuide, ConstraintSet.START)
                binding.scrollLyricView.update(PlaybackStateHolder.lrcLines, PlaybackStateHolder.positionMs)
            }
            cs.applyTo(binding.rootConstraint)
            return
        }
        portraitLyricsOnly = !portraitLyricsOnly
        applyLayout()
    }

    private fun applyLayout() {
        val cs = ConstraintSet()
        cs.clone(binding.rootConstraint)

        if (isLandscape()) {
            portraitLyricsOnly = false
            cs.setGuidelinePercent(binding.stageCenterGuide.id, 0.46f)
            cs.setVisibility(binding.leftPanel.id, View.VISIBLE)
            cs.setVisibility(binding.scrollLyricView.id, View.VISIBLE)
            setLeftPanelSpan(cs, guide = true)
            cs.clear(binding.scrollLyricView.id, ConstraintSet.START)
            cs.connect(
                binding.scrollLyricView.id,
                ConstraintSet.START,
                R.id.stageCenterGuide,
                ConstraintSet.END
            )
        } else {
            cs.setVisibility(binding.leftPanel.id, if (portraitLyricsOnly) View.GONE else View.VISIBLE)
            cs.setVisibility(binding.scrollLyricView.id, if (portraitLyricsOnly) View.VISIBLE else View.GONE)
            setLeftPanelSpan(cs, guide = false)
            cs.clear(binding.scrollLyricView.id, ConstraintSet.START)
            cs.connect(
                binding.scrollLyricView.id,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START
            )
        }
        cs.applyTo(binding.rootConstraint)
        if (binding.scrollLyricView.visibility == View.VISIBLE) {
            binding.scrollLyricView.update(PlaybackStateHolder.lrcLines, PlaybackStateHolder.positionMs)
        }
    }

    private fun setLeftPanelSpan(cs: ConstraintSet, guide: Boolean) {
        cs.clear(binding.leftPanel.id, ConstraintSet.END)
        if (guide) {
            cs.connect(binding.leftPanel.id, ConstraintSet.END, R.id.stageCenterGuide, ConstraintSet.START)
        } else {
            cs.connect(binding.leftPanel.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding != null) applyLayout()
    }

    override fun onStart() {
        super.onStart()
        PlaybackStateHolder.addListener(this)
    }

    override fun onStop() {
        PlaybackStateHolder.removeListener(this)
        super.onStop()
    }

    override fun onPlaybackChanged(
        song: Song?,
        playing: Boolean,
        positionMs: Long,
        lines: List<LrcLine>
    ) {
        renderState()
        binding.vinylRecord.setPlaying(playing)
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
        // Called on every progress tick (~8 Hz). Only touch the views when something
        // that actually affects them changed, otherwise the constant string/resource
        // churn shows up as jank on a slow head unit.
        val key = "${song?.path}|${PlaybackStateHolder.isPlaying}|${PlaybackStateHolder.playMode}|${song?.title}|${song?.artist}"
        if (key != renderedStateKey) {
            renderedStateKey = key
            // 无歌时不能说「随声 / 暂无歌曲\n请在设置中扫描音乐」：
            // 歌名位显示 App 名会像 bug，而 no_songs 里带 \n、songArtist 是 singleLine，
            // 换行会被折叠成省略号。这里给一套专门的静默态文案（冷启动默认就落播放页，
            // 没扫描过曲库时用户第一眼看到的就是这一屏）。
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
            binding.vinylRecord.setPlaying(PlaybackStateHolder.isPlaying)
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

    private fun applyPlayerBackground(color: Int) {
        binding.themeBackground.setBackgroundColor(color)
    }

    private fun renderCover(coverPath: String?) {
        if (coverPath.isNullOrBlank()) {
            applyPlayerBackground(defaultThemeColor)
            binding.vinylRecord.setCoverBitmap(null)
            return
        }
        // Downsample before decoding. Embedded art can be 3000x3000 (36 MB as
        // ARGB_8888); decoding that on a low-RAM head unit is a reliable way to get
        // killed while the user is simply opening the app.
        val bitmap = decodeCover(coverPath)
        if (bitmap != null) {
            applyPlayerBackground(AlbumColorExtractor.backgroundColor(bitmap))
            binding.vinylRecord.setCoverBitmap(bitmap)
        } else {
            applyPlayerBackground(defaultThemeColor)
            binding.vinylRecord.setCoverBitmap(null)
        }
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

    private fun toggleMode() {
        val next = if (PlaybackStateHolder.playMode == PlaybackMode.ORDER) PlaybackMode.SHUFFLE else PlaybackMode.ORDER
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

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        /** Longest edge, in pixels, used when decoding cover art for the player. */
        const val COVER_MAX_PX = 768
    }
}
