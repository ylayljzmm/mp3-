package com.suisheng.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.suisheng.player.R
import com.suisheng.player.databinding.ViewSongInfoPageBinding
import com.suisheng.player.model.Song
import com.suisheng.player.model.SongInfoDetails
import java.util.Locale

/**
 * 播放页第 1 页：歌曲信息（`design/05` 滑动改造新增）。
 *
 * ## 为什么是「先建行、后填值」
 *
 * 播放页的 `onPlaybackChanged` 每秒来 8 次。如果每次回调都 `removeAllViews()` +
 * new 12 个 TextView，低端车机上很快就变成一串 GC。所以行 View 在构造时建好一次，
 * [bind] 只做 `setText` —— 这是这个类唯一需要小心的地方，其余都是普通排版。
 *
 * ## 封面由调用方给，本类不碰 IO
 *
 * 封面路径既不在 [Song] 里（它是播放态，来自 `PlaybackStateHolder.coverArtPath`），
 * 解码又要按 `maxPx` 降采样才不 OOM（车机上 3000×3000 的内嵌封面直接
 * `decodeFile` 就是一次自杀）。所以解码统一放在 `PlayerFragment` 的 IO 段里，
 * 这里只负责「把一张已经解好的图放上去，并把上一张回收掉」。
 * 本类不持有线程、不持有 CoroutineScope —— 谁解码谁负责生命周期，边界清楚。
 */
class SongInfoPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding = ViewSongInfoPageBinding.inflate(LayoutInflater.from(context), this, true)

    private val albumRow: InfoRow
    private val yearRow: InfoRow
    private val durationRow: InfoRow
    private val trackRow: InfoRow
    private val formatRow: InfoRow
    private val mimeRow: InfoRow
    private val bitrateRow: InfoRow
    private val sampleRateRow: InfoRow
    private val bitsRow: InfoRow
    private val sizeRow: InfoRow
    private val pathRow: InfoRow
    private val lyricsRow: InfoRow

    /** 当前显示在封面位上的位图（由本类持有，换图时负责回收）。 */
    private var coverBitmap: Bitmap? = null

    init {
        val basicContainer = binding.infoBasicRows
        albumRow = addRow(basicContainer, R.string.song_info_label_album)
        yearRow = addRow(basicContainer, R.string.song_info_label_year)
        durationRow = addRow(basicContainer, R.string.song_info_label_duration)
        trackRow = addRow(basicContainer, R.string.song_info_label_track)

        val audioContainer = binding.infoAudioRows
        formatRow = addRow(audioContainer, R.string.song_info_label_format)
        mimeRow = addRow(audioContainer, R.string.song_info_label_mime)
        bitrateRow = addRow(audioContainer, R.string.song_info_label_bitrate)
        sampleRateRow = addRow(audioContainer, R.string.song_info_label_sample_rate)
        bitsRow = addRow(audioContainer, R.string.song_info_label_bits)

        val fileContainer = binding.infoFileRows
        sizeRow = addRow(fileContainer, R.string.song_info_label_size)
        pathRow = addRow(fileContainer, R.string.song_info_label_path)
        lyricsRow = addRow(fileContainer, R.string.song_info_label_lyrics)

        // 空态先按占位画好，避免第一次滑到这一页时看到一片空白
        bind(null, null, null)
    }

    // ------------------------------------------------------------------ 对外

    /**
     * 整页刷新：只做 setText / setImageBitmap。
     *
     * @param cover 已由调用方在 IO 线程解好的封面（最长边 ≤ 256px）；null 表示没有封面
     */
    fun bind(song: Song?, details: SongInfoDetails?, cover: Bitmap?) {
        setCoverBitmap(cover)

        val ctx = context
        val empty = ctx.getString(R.string.song_info_empty)
        if (song == null || details == null) {
            binding.infoTitle.setText(R.string.player_idle_title)
            binding.infoArtist.setText(R.string.player_idle_hint)
            albumRow.value.text = empty
            yearRow.value.text = empty
            durationRow.value.text = empty
            trackRow.value.text = empty
            formatRow.value.text = empty
            mimeRow.value.text = empty
            bitrateRow.value.text = empty
            sampleRateRow.value.text = empty
            bitsRow.value.text = empty
            sizeRow.value.text = empty
            pathRow.value.text = empty
            lyricsRow.value.text = empty
            return
        }

        binding.infoTitle.text = song.title
        binding.infoArtist.text = song.artist

        // ① 基本信息
        albumRow.value.text = song.album.ifBlank { empty }
        yearRow.value.text = if (song.year > 0) song.year.toString() else empty
        durationRow.value.text = if (song.durationMs > 0L) formatDuration(song.durationMs) else empty
        trackRow.value.text = details.trackNumber ?: empty

        // ② 音频参数
        formatRow.value.text = details.format ?: empty
        mimeRow.value.text = details.mimeType ?: empty
        bitrateRow.value.text = details.bitrateKbps
            ?.let { ctx.getString(R.string.song_info_kbps, it) }
            ?: empty
        sampleRateRow.value.text = details.sampleRateHz
            ?.let { ctx.getString(R.string.song_info_hz, it.toString()) }
            ?: empty
        bitsRow.value.text = details.bitsPerSample
            ?.let { ctx.getString(R.string.song_info_bits, it) }
            ?: empty

        // ③ 文件
        sizeRow.value.text = details.fileSizeBytes?.let { formatSize(it) } ?: empty
        pathRow.value.text = song.path
        lyricsRow.value.text = details.lrcLabel ?: empty
    }

    /**
     * 只换封面。
     *
     * 单独留一个入口是因为「封面」和「文字」的来源不同步：播放服务解出内嵌封面
     * 往往晚于曲目信息（要先 `setDataSource` 再读 `embeddedPicture`），
     * 那时只需要补一张图，不该把整页 12 行文字重读一遍。
     *
     * 先挂新图、再回收旧图 —— 顺序反了会让 ImageView 从已回收的 Bitmap 上绘制，
     * 车机上表现为一处闪烁或直接抛 `RuntimeException: Canvas: trying to use a recycled bitmap`。
     */
    fun setCoverBitmap(bitmap: Bitmap?) {
        val previous = coverBitmap
        coverBitmap = bitmap
        if (bitmap == null) {
            binding.infoCover.setImageResource(R.drawable.bg_album_placeholder)
        } else {
            binding.infoCover.setImageBitmap(bitmap)
        }
        if (previous != null && previous !== bitmap && !previous.isRecycled) {
            previous.recycle()
        }
    }

    // ------------------------------------------------------------------ 行构造

    /**
     * 建一行「标签 + 值」。
     *
     * 标签定宽 88dp：12 行里最长的标签是「文件大小」（4 个汉字 ≈ 52dp），
     * 定宽让所有值左边缘对齐，看起来是一张表而不是一堆散行。
     */
    private fun addRow(container: LinearLayout, labelRes: Int): InfoRow {
        val ctx = context
        val rowLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = resources.getDimensionPixelSize(R.dimen.song_info_row_min_height)
        }
        val label = TextView(ctx).apply {
            text = ctx.getString(labelRes)
            textSize = 13f
            setTextColor(
                MaterialColors.getColor(
                    ctx,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    Color.GRAY,
                )
            )
        }
        val value = TextView(ctx).apply {
            textSize = 14f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(
                MaterialColors.getColor(
                    ctx,
                    com.google.android.material.R.attr.colorOnSurface,
                    Color.BLACK,
                )
            )
        }
        rowLayout.addView(
            label,
            LinearLayout.LayoutParams(dp(LABEL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        rowLayout.addView(
            value,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(LABEL_VALUE_GAP_DP) },
        )
        container.addView(rowLayout)
        return InfoRow(value)
    }

    private class InfoRow(val value: TextView)

    // ------------------------------------------------------------------ 格式化

    /** `m:ss` / `h:mm:ss`；0 或负数由调用方过滤，这里不做兜底。 */
    private fun formatDuration(durationMs: Long): String {
        val totalSec = durationMs / 1000L
        val sec = totalSec % 60L
        val min = (totalSec / 60L) % 60L
        val hour = totalSec / 3600L
        return if (hour > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hour, min, sec)
        } else {
            String.format(Locale.US, "%d:%02d", min, sec)
        }
    }

    /** B / KB / MB，保留 1 位小数（车机上看的是量级，不需要精确到字节）。 */
    private fun formatSize(bytes: Long): String = when {
        bytes >= BYTES_PER_MB -> String.format(Locale.US, "%.1f MB", bytes / BYTES_PER_MB)
        bytes >= BYTES_PER_KB -> String.format(Locale.US, "%.1f KB", bytes / BYTES_PER_KB)
        else -> "$bytes B"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val LABEL_WIDTH_DP = 88
        const val LABEL_VALUE_GAP_DP = 12

        const val BYTES_PER_KB = 1024.0
        const val BYTES_PER_MB = 1024.0 * 1024.0
    }
}
