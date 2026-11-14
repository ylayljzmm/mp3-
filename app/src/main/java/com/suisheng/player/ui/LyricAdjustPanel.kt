package com.suisheng.player.ui

import android.content.Context
import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import com.suisheng.player.R
import com.suisheng.player.data.PerSongLyricPrefs
import com.suisheng.player.databinding.PanelLyricAdjustBinding
import com.suisheng.player.lrc.LyricProviderCatalog
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 歌词调节面板（需求 4：快慢 / 翻译 / 注音；5.8 起多一个「来源」页签）。
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
 *
 * ## 为什么加页签
 *
 * 「同步」和「来源」是两个不同的心智动作：一个是对齐时间，一个是换一家取词。
 * 竖着堆在一起会让面板超过一屏（车机只有 540~600dp），所以分成两页。
 * 列表渲染直接复用 [LyricSourceSheet.buildSourceList]，不抄第二份。
 *
 * 5.9 起两页职责彻底分开：同步页只管时间 / 显示，来源页只管来源。原来同步页里
 * 与来源页重复的「歌词来源」分组和「换个渠道重新获取」已删除，换源只在来源页明确指定。
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
        // ---- 5.8 新增：全部带默认值，旧调用点不传也能编译 ----
        /** 用户为这首歌指定的渠道；`null` = 自动（按顺序回落）。 */
        val providerOverride: String? = null,
        /** 来源页要显示的列表；空表示宿主还没接上，面板自己用注册表兜底生成。 */
        val sources: List<LyricSourceSheet.Item> = emptyList(),
        /** 逐字优先开关的当前值（全局偏好）。 */
        val wordLevelEnabled: Boolean = false,
    )

    /** 用户操作回调。宿主负责落盘并把结果推给四条消费链。 */
    class Callbacks(
        val onOffsetChanged: (Long) -> Unit,
        val onOffsetReset: () -> Unit,
        val onShowTranslationChanged: (Boolean) -> Unit,
        val onShowPhoneticChanged: (Boolean) -> Unit,
        // ---- 5.8 新增，带默认实现，旧调用点不受影响 ----
        /** 选中某个渠道，`null` = 自动。 */
        val onPickProvider: (String?) -> Unit = {},
        /** 逐字优先开关切换。 */
        val onToggleWordLevel: (Boolean) -> Unit = {},
    )

    /**
     * 渠道 id → 展示名。未知或空一律「未知来源」，不要显示原始 id 给用户看。
     *
     * 先查 [LyricProviderCatalog] 再走 `when` 兜底：注册表才是渠道名的唯一来源，
     * 这里再硬编码一份的话，每加一个渠道就会漏一个（5.8 的酷我就是这么变成「未知来源」的）。
     */
    fun providerLabel(context: Context, providerId: String?): String {
        if (providerId.isNullOrEmpty()) {
            return context.getString(R.string.lyric_adjust_source_unknown)
        }
        LyricProviderCatalog.of(providerId)?.let { capability ->
            return context.getString(capability.labelRes)
        }
        return when (providerId) {
            SOURCE_LOCAL -> context.getString(R.string.lyric_provider_local)
            SOURCE_ONLINE_API -> context.getString(R.string.lyric_provider_online_api)
            SOURCE_PODCAST -> context.getString(R.string.lyric_provider_podcast)
            else -> context.getString(R.string.lyric_adjust_source_unknown)
        }
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
        // 有译文时开关可用就是最好的说明，不必再写一行「本首歌词带翻译」。
        binding.lyricAdjustTranslationHint.isVisible = !state.hasTranslation
        binding.lyricAdjustTranslationHint.text =
            context.getString(R.string.lyric_adjust_translation_missing)
        binding.lyricAdjustTranslationSwitch.setOnCheckedChangeListener { _, checked ->
            callbacks.onShowTranslationChanged(checked)
        }

        binding.lyricAdjustPhoneticSwitch.isChecked = state.showPhonetic
        binding.lyricAdjustPhoneticSwitch.isEnabled = state.hasPhonetic
        binding.lyricAdjustPhoneticHint.isVisible = !state.hasPhonetic
        binding.lyricAdjustPhoneticHint.text =
            context.getString(R.string.lyric_adjust_phonetic_missing)
        binding.lyricAdjustPhoneticSwitch.setOnCheckedChangeListener { _, checked ->
            callbacks.onShowPhoneticChanged(checked)
        }

        // 当前来源只读行在「来源」页顶部（5.9 从同步页平移过来），赋值逻辑不变。
        binding.lyricAdjustSourceValue.text = context.getString(
            R.string.lyric_adjust_source_value,
            providerLabel(context, state.providerId),
        )
        binding.lyricAdjustClose.setOnClickListener { dialog.dismiss() }

        setupTabs(binding)
        bindSourcePage(context, binding, state, callbacks, dialog)

        dialog.show()
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 页签：TabLayout + 两个容器手动 show/hide。
     *
     * 不用 ViewPager2：只有两页且不需要滑动手势，ViewPager2 在 Dialog 里还得处理
     * 「内部 RecyclerView 和外层 NestedScrollView」的测量冲突。容器高度由 XML 的
     * `measureAllChildren` 固定成两页的最高值，所以切页不会引起面板高度跳变。
     */
    private fun setupTabs(binding: PanelLyricAdjustBinding) {
        val tabs = binding.lyricAdjustTabs
        tabs.addTab(tabs.newTab().setText(R.string.lyric_adjust_tab_sync))
        tabs.addTab(tabs.newTab().setText(R.string.lyric_adjust_tab_source))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = renderPage(binding, tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        // 监听器是 addTab 之后才挂的，首帧不会收到回调，这里显式渲染一次
        renderPage(binding, 0)
    }

    private fun renderPage(binding: PanelLyricAdjustBinding, index: Int) {
        binding.lyricAdjustSyncPage.isVisible = index == 0
        binding.lyricAdjustSourcePage.isVisible = index == 1
    }

    /**
     * 来源页：列表复用 [LyricSourceSheet.buildSourceList]，点击直接透传给宿主。
     *
     * 旧调用点不会传 `state.sources`，所以这里必须能自己兜底生成 —— 空列表只会让
     * 用户看到一个空白页签，比没有这个页签更糟。
     */
    private fun bindSourcePage(
        context: Context,
        binding: PanelLyricAdjustBinding,
        state: State,
        callbacks: Callbacks,
        dialog: android.app.Dialog,
    ) {
        binding.lyricAdjustSourceWordLevelSwitch.isChecked = state.wordLevelEnabled
        binding.lyricAdjustSourceWordLevelSwitch.setOnCheckedChangeListener { _, checked ->
            callbacks.onToggleWordLevel(checked)
        }

        // current 一律按文档的真实来源重算：调用方传进来的 Item 可能是上一帧的快照
        val items = state.sources
            .ifEmpty {
                LyricSourceSheet.itemsFromCatalog(
                    context = context,
                    currentProviderId = state.providerId,
                    selectedId = state.providerOverride,
                )
            }
            .map { it.copy(current = it.id == state.providerId) }

        LyricSourceSheet.buildSourceList(context, binding.lyricAdjustSourceList, items) { id ->
            dialog.dismiss()
            callbacks.onPickProvider(id.takeUnless { it == LyricSourceSheet.AUTO_ID })
        }
        binding.lyricAdjustSourceClose.setOnClickListener { dialog.dismiss() }
    }

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
