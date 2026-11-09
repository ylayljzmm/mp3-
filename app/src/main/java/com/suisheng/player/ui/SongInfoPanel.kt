package com.suisheng.player.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.view.LayoutInflater
import com.google.android.material.color.MaterialColors
import com.suisheng.player.R
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.data.SongInfoLoader
import com.suisheng.player.databinding.PanelSongInfoBinding
import com.suisheng.player.model.Song
import com.suisheng.player.model.SongInfoDetails
import com.suisheng.player.playback.PlaybackStateHolder
import com.suisheng.player.util.TimeFormat
import java.util.Locale

/**
 * 歌曲信息面板（方案 I 功能行第 5 格）。
 *
 * 与 [MorePanel] 的区别：这里是「直接查看信息 + 直接调倍速」的一级入口，
 * [MorePanel] 仍是顶栏「更多」的收藏 / 移除队列 / 歌曲信息 / 取消四件套。
 *
 * 格式字段按 Q-7 裁定**真实读取**：本地文件用 [MediaMetadataRetriever] 取 MIME，
 * 取不到就退回扩展名，绝不显示占位假数据；采样率只有系统真能给出时才追加。
 */
object SongInfoPanel {

    fun show(
        context: Context,
        song: Song,
        currentSpeed: Float,
        onPickSpeed: (Float) -> Unit,
    ) {
        val binding = PanelSongInfoBinding.inflate(LayoutInflater.from(context))
        // 歌曲信息行多（路径可能换行），车机上容易超屏高：交给 PanelHost 套一层滚动容器
        val dialog = PanelHost.build(context, binding.root, scrollable = true)

        val unknown = context.getString(R.string.more_info_unknown)
        binding.infoSong.text = context.getString(
            R.string.more_info_song, song.title.takeIf { it.isNotBlank() } ?: unknown
        )
        binding.infoArtist.text = context.getString(
            R.string.more_info_artist, song.artist.takeIf { it.isNotBlank() } ?: unknown
        )
        binding.infoAlbum.text = context.getString(
            R.string.more_info_album, song.album.takeIf { it.isNotBlank() } ?: unknown
        )
        binding.infoDuration.text = context.getString(
            R.string.more_info_duration, TimeFormat.mmss(song.durationMs)
        )
        binding.infoFormat.text = context.getString(
            R.string.more_info_format, describeFormat(song.path, unknown)
        )
        binding.infoPath.text = context.getString(
            R.string.more_info_path, song.path.takeIf { it.isNotBlank() } ?: unknown
        )
        binding.infoLrc.text = context.getString(
            R.string.more_info_lrc, song.lrcPath?.takeIf { it.isNotBlank() } ?: unknown
        )

        // 5.7.1：车机横屏左滑信息页被删掉，原页「音频参数 / 文件」两段的字段并到这里。
        // SongInfoLoader 会打开容器头（U 盘 FLAC 单次几十毫秒），必须在后台读，
        // 读完后 post 回主线程填；未读出来之前显示占位，绝不填假数据。
        val pending = context.getString(R.string.song_info_empty)
        binding.infoYearTrack.text = context.getString(R.string.song_info_year_track, pending)
        binding.infoMime.text = context.getString(R.string.song_info_mime_row, pending)
        binding.infoAudioParams.text = context.getString(R.string.song_info_audio_row, pending)
        binding.infoSizeLrc.text = context.getString(R.string.song_info_size_row, pending)
        Thread {
            val details = runCatching {
                SongInfoLoader.load(
                    context.applicationContext,
                    song,
                    PlaybackStateHolder.lrcLines.isNotEmpty(),
                    SettingsRepository(context.applicationContext).onlineLyricsEnabled,
                )
            }.getOrNull()
            binding.root.post { renderTech(context, binding, song, details) }
        }.start()

        val rows = listOf(
            binding.infoSpeedSlowest,
            binding.infoSpeedNormal,
            binding.infoSpeedFast,
            binding.infoSpeedFaster,
            binding.infoSpeedFastest,
        )

        fun render(selected: Float) {
            rows.forEachIndexed { index, row ->
                val option = SpeedPanel.OPTIONS[index]
                val active = kotlin.math.abs(option - selected) < 0.001f
                row.text = if (active) {
                    context.getString(R.string.speed_current, SpeedPanel.label(option))
                } else {
                    context.getString(R.string.speed_x, SpeedPanel.label(option))
                }
                row.setTextColor(
                    MaterialColors.getColor(
                        row,
                        if (active) {
                            com.google.android.material.R.attr.colorPrimary
                        } else {
                            com.google.android.material.R.attr.colorOnSurface
                        },
                    )
                )
                row.setTypeface(
                    null,
                    if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
                )
            }
        }

        rows.forEachIndexed { index, row ->
            row.setOnClickListener {
                val picked = SpeedPanel.OPTIONS[index]
                render(picked)
                onPickSpeed(picked)
                dialog.dismiss()
            }
        }

        render(currentSpeed)
        dialog.show()
    }

    /** 把信息页原有的技术字段按同一口径渲染到面板里；取不到就逐项显示「—」。 */
    private fun renderTech(
        context: Context,
        binding: PanelSongInfoBinding,
        song: Song,
        details: SongInfoDetails?,
    ) {
        val empty = context.getString(R.string.song_info_empty)
        if (details == null) {
            binding.infoYearTrack.text = context.getString(R.string.song_info_year_track, empty)
            binding.infoMime.text = context.getString(R.string.song_info_mime_row, empty)
            binding.infoAudioParams.text = context.getString(R.string.song_info_audio_row, empty)
            binding.infoSizeLrc.text = context.getString(R.string.song_info_size_row, empty)
            return
        }
        val year = if (song.year > 0) song.year.toString() else empty
        val track = details.trackNumber ?: empty
        binding.infoYearTrack.text = context.getString(
            R.string.song_info_year_track, "$year / $track"
        )
        binding.infoMime.text = context.getString(
            R.string.song_info_mime_row, details.mimeType ?: details.format ?: empty
        )
        val audio = listOfNotNull(
            details.bitrateKbps?.let { context.getString(R.string.song_info_kbps, it) },
            details.sampleRateHz?.let { context.getString(R.string.song_info_hz, it.toString()) },
            details.bitsPerSample?.let { context.getString(R.string.song_info_bits, it) },
        ).joinToString(" · ").ifBlank { empty }
        binding.infoAudioParams.text = context.getString(R.string.song_info_audio_row, audio)
        val size = details.fileSizeBytes?.let { formatSize(it) } ?: empty
        binding.infoSizeLrc.text = context.getString(
            R.string.song_info_size_row, "$size / ${details.lrcLabel ?: empty}"
        )
    }

    /** 与信息页同一口径：B / KB / MB，保留 1 位小数。 */
    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 真实格式：优先 MIME，其次扩展名；位深 / 采样率取不到就不显示，不假造。 */
    private fun describeFormat(path: String, unknown: String): String {
        val ext = path.substringAfterLast('.', "")
            .uppercase(Locale.US)
            .takeIf { it.isNotBlank() && it.length <= 5 }
            .orEmpty()
        var mime = ""
        var sampleRate = ""
        if (path.startsWith("/")) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                mime = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_MIMETYPE
                ).orEmpty()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    sampleRate = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_SAMPLERATE
                    ).orEmpty()
                }
            } catch (ignored: Exception) {
                // 打不开或不支持就退回扩展名
            } finally {
                runCatching { retriever.release() }
            }
        }
        val container = when {
            mime.contains("flac", true) -> "FLAC"
            mime.contains("mpeg", true) || mime.contains("mp3", true) -> "MP3"
            mime.contains("mp4", true) || mime.contains("aac", true) -> "AAC"
            mime.contains("wav", true) -> "WAV"
            mime.contains("ogg", true) || mime.contains("vorbis", true) -> "OGG"
            mime.contains("opus", true) -> "OPUS"
            mime.contains("ape", true) -> "APE"
            else -> ext.ifBlank { unknown }
        }
        val khz = sampleRate.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { String.format(Locale.US, " %.1fkHz", it / 1000.0) }
            .orEmpty()
        return container + khz
    }
}
