package com.suisheng.player.ui

import android.content.Context
import android.view.LayoutInflater
import com.google.android.material.slider.Slider
import com.suisheng.player.R
import com.suisheng.player.data.PerSongLyricPrefs
import com.suisheng.player.databinding.PanelLyricAdjustBinding
import com.suisheng.player.lrc.LyricProviders
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 歌词调节面板（需求 4：快慢 / 翻译 / 注音）。
 *
 * 与 [SpeedPanel] / [MorePanel] 同模式：`object` + [PanelHost] + ViewBinding。
 * 面板只做「状态 → 控件 + 操作 → 回调」，不碰播放器、不碰存储 —— 落盘和立即生效
 * 都由宿主（播放页）负责，这样四条消费链的改动面始终只有一处。
 *
 * ## 三项都是「每首歌」
 *
 * 偏移、译文、注音都按曲目记忆（见 [PerSongLyricPrefs]）。用户是在听歌、发现对不上
 * 时才来调的，所以入口放在播放页而不是设置页。
 *
 * ## 滑杆与快捷档的分工
 *
 * 滑杆范围 ±5 秒 / 步进 0.1 秒，覆盖「差半拍」这种常见情况；快捷档给 -1.0 / -0.5 /
 * +0.5 / +1.0 四档，因为在车机上摸滑杆很难精确停在某一档。超过 ±5 秒的偏移不是
 * 「快慢」问题而是歌词对错了歌，面板不给入口。
 */
object LyricAdjustPanel {

    /** 面板要显示的全部运行时状态，由宿主一次给全（面板自己不查任何东西）。 */
    data class State(
        /** 当前用户偏移（毫秒，正 = 歌词提前）。 */
        val offsetMs: Long,
        /** 译文开关的当前值（全局偏好）。 */
        val showTranslation: Boolean,
        /** 注音开关的当前值（全局偏好）。 */
        val showPhonetic: Boolean,
        /** 本首歌词是否真的带译文 —— 决定开关是否可点、提示文案。 */
        val hasTranslation: Boolean,
        /** 本首歌词是否真的带注音。 */
        val hasPhonetic: Boolean,
        /** 这份歌词的来源渠道 id（`netease` / `local_file` / …），用于显示来源。 */
        val providerId: String?,
    )

    /** 用户操作回调。宿主负责落盘并把结果推给四条消费链。 */
    class Callbacks(
        val onOffsetChanged: (Long) -> Unit,
        val onOffsetReset: () -> Unit,
        val onShowTranslationChanged: (Boolean) -> Unit,
        val onShowPhoneticChanged: (Boolean) -> Unit,
        val onRefetch: () -> Unit,
    )

    /** 渠道 id → 展示名。未知或空一律「未知来源」，不要显示原始 id 给用户看。 */
    fun providerLabel(context: Context, providerId: String?): String = when (providerId) {
        null, "" -> context.getString(R.string.lyric_adjust_source_unknown)
        LyricProviders.NETEASE -> context.getString(R.string.lyric_provider_netease)
        LyricProviders.LRCLIB -> context.getString(R.string.lyric_provider_lrclib)
        LyricProviders.KUGOU -> context.getString(R.string.lyric_provider_kugou)
        LyricProviders.QQ -> context.getString(R.string.lyric_provider_qq)
        LyricProviders.TEXTYL -> context.getString(R.string.lyric_provider_textyl)
        LyricProviders.LYRICS_OVH -> context.getString(R.string.lyric_provider_lyrics_ovh)
        SOURCE_LOCAL -> context.getString(R.string.lyric_provider_local)
        SOURCE_ONLINE_API -> context.getString(R.string.lyric_provider_online_api)
        SOURCE_PODCAST -> context.getString(R.string.lyric_provider_podcast)
        else -> context.getString(R.string.lyric_adjust_source_unknown)
    }

    fun show(context: Context, state: State, callbacks: Callbacks) {
        val binding = PanelLyricAdjustBinding.inflate(LayoutInflater.from(context))
        val dialog = PanelHost.build(context, binding.root, scrollable = true)

        // 面板开着的时候滑杆与快捷档会互相影响，当前值必须由这里持有；
        // 只读 state.offsetMs 的话连点两次「+0.5」就只会加一次。
        var current = state.offsetMs

        fun apply(offsetMs: Long) {
            current = offsetMs
            // 同步滑杆把手：快捷档也要让用户看到当前落在哪一档。
            // 这里是程序赋值，监听器收到的 fromUser = false，不会回调成环。
            binding.lyricAdjustOffsetSlider.value = snapToStep(offsetMs)
            renderOffset(context, binding, offsetMs)
            callbacks.onOffsetChanged(offsetMs)
        }

        renderOffset(context, binding, current)

        // value 是 Float 且必须落在步进网格上，否则 Material Slider 会直接抛异常。
        binding.lyricAdjustOffsetSlider.value = snapToStep(current)
        binding.lyricAdjustOffsetSlider.addOnChangeListener(
            Slider.OnChangeListener { _, value, fromUser ->
                if (fromUser) apply(value.roundToLong())
            }
        )

        bindChip(binding.lyricAdjustBack1000) { apply(nudged(current, -1_000L)) }
        bindChip(binding.lyricAdjustBack500) { apply(nudged(current, -500L)) }
        bindChip(binding.lyricAdjustForward500) { apply(nudged(current, 500L)) }
        bindChip(binding.lyricAdjustForward1000) { apply(nudged(current, 1_000L)) }
        bindChip(binding.lyricAdjustReset) {
            apply(0L)
            callbacks.onOffsetReset()
        }

        binding.lyricAdjustTranslationSwitch.isChecked = state.showTranslation
        binding.lyricAdjustTranslationSwitch.isEnabled = state.hasTranslation
        binding.lyricAdjustTranslationHint.text = context.getString(
            if (state.hasTranslation) {
                R.string.lyric_adjust_translation_ready
            } else {
                R.string.lyric_adjust_translation_missing
            }
        )
        binding.lyricAdjustTranslationSwitch.setOnCheckedChangeListener { _, checked ->
            callbacks.onShowTranslationChanged(checked)
        }

        binding.lyricAdjustPhoneticSwitch.isChecked = state.showPhonetic
        binding.lyricAdjustPhoneticSwitch.isEnabled = state.hasPhonetic
        binding.lyricAdjustPhoneticHint.text = context.getString(
            if (state.hasPhonetic) {
                R.string.lyric_adjust_phonetic_ready
            } else {
                R.string.lyric_adjust_phonetic_missing
            }
        )
        binding.lyricAdjustPhoneticSwitch.setOnCheckedChangeListener { _, checked ->
            callbacks.onShowPhoneticChanged(checked)
        }

        binding.lyricAdjustSourceValue.text = context.getString(
            R.string.lyric_adjust_source_value,
            providerLabel(context, state.providerId),
        )
        binding.lyricAdjustRefetch.setOnClickListener {
            dialog.dismiss()
            callbacks.onRefetch()
        }
        binding.lyricAdjustClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    // ---------------------------------------------------------------- 内部

    /** 快捷档：在当前值上加减，并钳到滑杆范围内（超出滑杆范围的值滑杆表达不了）。 */
    private fun nudged(currentMs: Long, deltaMs: Long): Long =
        (currentMs + deltaMs)
            .coerceIn(-PerSongLyricPrefs.SLIDER_RANGE_MS, PerSongLyricPrefs.SLIDER_RANGE_MS)

    private fun bindChip(view: android.widget.TextView, action: () -> Unit) {
        view.setOnClickListener { action() }
    }

    private fun renderOffset(
        context: Context,
        binding: PanelLyricAdjustBinding,
        offsetMs: Long,
    ) {
        val text = context.getString(
            R.string.lyric_adjust_offset_second,
            String.format(Locale.US, "%+.1f", offsetMs / 1000.0),
        )
        binding.lyricAdjustOffsetValue.text =
            context.getString(R.string.lyric_adjust_offset_value, text)
    }

    /**
     * 把毫秒吸附到滑杆的步进网格上。
     *
     * `value` 是 Float，两个相邻步进之间任意一个值都会让 Slider 抛
     * `IllegalArgumentException`，所以不能直接把历史值塞进去。
     */
    private fun snapToStep(offsetMs: Long): Float {
        val clamped = offsetMs.coerceIn(
            -PerSongLyricPrefs.SLIDER_RANGE_MS,
            PerSongLyricPrefs.SLIDER_RANGE_MS,
        )
        val steps = (clamped.toDouble() / PerSongLyricPrefs.SLIDER_STEP_MS).roundToLong()
        val snapped = steps * PerSongLyricPrefs.SLIDER_STEP_MS
        // 负零在格式化时会显示成 "-0.0"，这里统一成正零。
        return if (abs(snapped) < PerSongLyricPrefs.SLIDER_STEP_MS) 0f else snapped.toFloat()
    }

    private const val SOURCE_LOCAL = "local_file"
    private const val SOURCE_ONLINE_API = "online_api"
    private const val SOURCE_PODCAST = "podcast_desc"
}
