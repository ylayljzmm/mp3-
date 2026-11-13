package com.suisheng.player.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.suisheng.player.R
import com.suisheng.player.databinding.PanelLyricSourceBinding
import com.suisheng.player.lrc.LyricAbility
import com.suisheng.player.lrc.LyricProviderCatalog

/**
 * 歌词来源选择面板。
 *
 * 与 [SpeedPanel] / [MorePanel] / [LyricAdjustPanel] 同模式：`object` + [PanelHost] + ViewBinding。
 *
 * ## 为什么「逐字优先」放在这个面板里
 *
 * [com.suisheng.player.data.SettingsRepository.lyricWordLevelEnabled] 是**全局**偏好，
 * 本应只出现在设置页。但用户真正意识到「逐字」存在，是在听一首没有逐字的歌的时候；
 * 让他从播放页退出、进设置、找到歌词设置、再切回来，歌早过去了。所以这里给一个快捷开关，
 * 权威入口仍然是「设置 → 歌词设置」。
 *
 * ## 列表为什么是动态 addView
 *
 * 行数固定（自动 + 每个联网渠道，个位数），用 RecyclerView 会引入「面板要按内容收口
 * 高度」的测量问题（见 [PanelHost] 对 RecyclerView 的说明）；LinearLayout 直接铺开更稳。
 */
object LyricSourceSheet {

    /** 「自动」行的伪渠道 id：它不是任何真实渠道，只表示「按顺序回落」。 */
    const val AUTO_ID = "__auto__"

    /**
     * 一行「来源」的运行时状态。
     *
     * 文案与能力标签都**由调用方本地化好后传进来**：面板不查设置、不查注册表，
     * 这样同一个面板既能被播放页直接弹出，也能被调节面板的「来源」页复用。
     */
    data class Item(
        /** 渠道 id；[AUTO_ID] 表示「自动」。 */
        val id: String,
        /** 已本地化好的展示名。 */
        val label: String,
        /** 已本地化好的能力标签，如 `["逐字", "译文", "时间轴"]`；「自动」行传空表。 */
        val abilities: List<String>,
        /** 设置里是否开着 —— 关掉的置灰并显示「已关闭」，但仍然可点。 */
        val enabled: Boolean,
        /** 是不是用户当前选中的那一个。 */
        val selected: Boolean,
        /** 是不是当前这份歌词的实际来源。 */
        val current: Boolean,
    )

    /**
     * 弹出来源面板。
     *
     * @param items 第一行必须是「自动」（[AUTO_ID]）
     * @param currentProviderId 当前这份歌词的实际来源 id；用它覆盖 [Item.current]，
     *   避免调用方传进来的列表和文档状态不一致时打勾打错行
     * @param wordLevelEnabled 逐字优先开关的当前值
     * @param onPick 选中回调，`null` 表示「自动（按顺序尝试）」
     * @param onToggleWordLevel 逐字优先开关的切换回调
     */
    fun show(
        context: Context,
        items: List<Item>,
        currentProviderId: String?,
        wordLevelEnabled: Boolean,
        onPick: (String?) -> Unit,
        onToggleWordLevel: (Boolean) -> Unit,
    ) {
        val binding = PanelLyricSourceBinding.inflate(LayoutInflater.from(context))
        val dialog = PanelHost.build(context, binding.root, scrollable = true)

        binding.lyricSourceWordLevelSwitch.isChecked = wordLevelEnabled
        binding.lyricSourceWordLevelSwitch.setOnCheckedChangeListener { _, checked ->
            onToggleWordLevel(checked)
        }

        // current 一律以 currentProviderId 为准：调用方给的列表可能是上一帧的快照，
        // 打勾和「当前」角标必须和文档真实的来源一致。
        val rows = items.map { it.copy(current = it.id == currentProviderId) }
        buildSourceList(context, binding.lyricSourceList, rows) { id ->
            dialog.dismiss()
            onPick(id.takeUnless { it == AUTO_ID })
        }
        binding.lyricSourceClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    /**
     * 按注册表的出厂顺序生成列表，全部视为启用。
     *
     * 这是给「宿主没有传列表」的兜底用的（[LyricAdjustPanel] 的来源页在旧调用点下
     * 拿不到渠道配置）：宁可显示成全部可用，也不要让来源页空白。
     */
    fun itemsFromCatalog(
        context: Context,
        currentProviderId: String?,
        selectedId: String?,
    ): List<Item> = buildList {
        add(autoItem(context, selectedId))
        LyricProviderCatalog.all().forEach { capability ->
            add(
                channelItem(
                    context = context,
                    id = capability.id,
                    labelRes = capability.labelRes,
                    abilities = capability.abilities,
                    enabled = true,
                    selected = capability.id == selectedId,
                    current = capability.id == currentProviderId,
                )
            )
        }
    }

    /**
     * 把来源列表铺进容器。调节面板的「来源」页也调这个，保证两处渲染逐像素一致。
     *
     * @param onPick 传的是被点行的原始 id（含 [AUTO_ID]），由调用方决定怎么映射
     */
    internal fun buildSourceList(
        context: Context,
        container: LinearLayout,
        items: List<Item>,
        onPick: (String) -> Unit,
    ) {
        container.removeAllViews()
        val ripple = rippleDrawable(context)
        items.forEach { item ->
            container.addView(
                createRow(context, container, item, ripple) { onPick(item.id) }
            )
        }
    }

    /** 能力 → 已本地化标签。设置页与来源面板读同一份，避免加能力时只改一处。 */
    fun abilityLabel(context: Context, ability: LyricAbility): String = when (ability) {
        LyricAbility.CHAR_LEVEL -> context.getString(R.string.lyric_ability_char_level)
        LyricAbility.TRANSLATION -> context.getString(R.string.lyric_ability_translation)
        LyricAbility.TIMELINE -> context.getString(R.string.lyric_ability_timeline)
    }

    /**
     * 能力集合 → 标签列表。
     *
     * 空集合在注册表里的语义是「拿不到时间轴」，用户看到的应该是「纯文本」而不是一片空白
     * —— `LyricProviders.LYRICS_OVH` 就是这种渠道。
     */
    fun abilityLabels(context: Context, abilities: Set<LyricAbility>): List<String> {
        if (abilities.isEmpty()) return listOf(context.getString(R.string.lyric_ability_plain))
        return LyricAbility.entries
            .filter { it in abilities }
            .map { abilityLabel(context, it) }
    }

    // ---------------------------------------------------------------- 内部

    private fun autoItem(context: Context, selectedId: String?) = Item(
        id = AUTO_ID,
        label = context.getString(R.string.lyric_source_auto),
        abilities = emptyList(),
        enabled = true,
        selected = selectedId == null,
        current = false,
    )

    private fun channelItem(
        context: Context,
        id: String,
        labelRes: Int,
        abilities: Set<LyricAbility>,
        enabled: Boolean,
        selected: Boolean,
        current: Boolean,
    ) = Item(
        id = id,
        label = context.getString(labelRes),
        abilities = abilityLabels(context, abilities),
        enabled = enabled,
        selected = selected,
        current = current,
    )

    private fun createRow(
        context: Context,
        parent: View,
        item: Item,
        rowBackground: Drawable?,
        onClick: () -> Unit,
    ): View {
        val primary = MaterialColors.getColor(
            parent,
            com.google.android.material.R.attr.colorPrimary,
        )
        val onSurface = MaterialColors.getColor(
            parent,
            com.google.android.material.R.attr.colorOnSurface,
        )
        val onSurfaceVariant = MaterialColors.getColor(
            parent,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
        )

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }

        val nameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(
            TextView(context).apply {
                text = item.label
                textSize = 16f
                setTextColor(if (item.current) primary else onSurface)
                if (item.current) setTypeface(null, Typeface.BOLD)
            }
        )
        if (item.current) {
            // 「当前」角标：主色加粗已经能区分，但一份歌词可能来源同名渠道的不同版本，
            // 补一个角标让用户确认「我现在听到的确实来自这一行」。
            nameRow.addView(
                TextView(context).apply {
                    text = context.getString(R.string.lyric_source_current)
                    textSize = 11f
                    setTextColor(primary)
                    setPadding(dp(context, 8f), 0, 0, 0)
                }
            )
        }
        textColumn.addView(nameRow)

        val abilityText = buildList {
            addAll(item.abilities)
            if (!item.enabled) add(context.getString(R.string.lyric_source_disabled))
        }.joinToString(" · ")
        if (abilityText.isNotEmpty()) {
            textColumn.addView(
                TextView(context).apply {
                    text = abilityText
                    textSize = 12f
                    setTextColor(onSurfaceVariant)
                    setPadding(0, dp(context, 2f), 0, 0)
                }
            )
        }

        val check = TextView(context).apply {
            text = "✓"
            textSize = 18f
            setTextColor(primary)
            // INVISIBLE 而不是 GONE：右侧留出固定宽度，未选中的行名称不会左右错位
            visibility = if (item.selected) View.VISIBLE else View.INVISIBLE
            setPadding(dp(context, 12f), 0, 0, 0)
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            minimumHeight = dp(context, 56f)
            setPadding(dp(context, 24f), dp(context, 8f), dp(context, 24f), dp(context, 8f))
            this.background = rowBackground
            isClickable = true
            isFocusable = true
            // 关掉的渠道置灰但**仍然可点**：点它就是「强制用这一家取词」，
            // 这是用户关掉某渠道后唯一还能单独试一次它有没有这首歌的入口。
            if (!item.enabled) alpha = 0.45f
            addView(textColumn)
            addView(check)
            setOnClickListener { onClick() }
        }
    }

    /** 从主题里取 `?attr/selectableItemBackground` 的水波纹，和 XML 面板的点击反馈一致。 */
    private fun rippleDrawable(context: Context): Drawable? {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                value,
                true,
            )
        ) {
            return null
        }
        return ContextCompat.getDrawable(context, value.resourceId)
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
