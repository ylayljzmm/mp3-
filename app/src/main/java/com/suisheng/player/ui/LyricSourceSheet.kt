package com.suisheng.player.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.suisheng.player.R
import com.suisheng.player.lrc.LyricAbility
import com.suisheng.player.lrc.LyricProviderCatalog

/**
 * 歌词来源列表的数据模型与渲染工具。
 *
 * 5.9 删除了独立的来源面板 `show()`：它全仓没有调用点，对应布局也从未被 inflate，
 * 是 5.8 留下的第三套「来源」UI。现在播放页换源统一走 [LyricAdjustPanel] 的
 * 「来源」页签，全局顺序与开关走歌词设置页，两处职责正交。
 *
 * 本文件保留 [AUTO_ID] / [Item] / [itemsFromCatalog] / [buildSourceList] /
 * [abilityLabels] 等成员：调节面板、歌词设置页、播放页都在复用，**不要整文件删除**。
 *
 * ## 列表为什么是动态 addView
 *
 * 行数固定（自动 + 每个联网渠道，个位数），用 RecyclerView 会引入「面板要按内容收口
 * 高度」的测量问题（见 PanelHost 对 RecyclerView 的说明）；LinearLayout 直接铺开更稳。
 */
object LyricSourceSheet {

    /** 「自动」行的伪渠道 id：它不是任何真实渠道，只表示「按顺序回落」。 */
    const val AUTO_ID = "__auto__"

    /**
     * 一行「来源」的运行时状态。
     *
     * 文案与能力标签都**由调用方本地化好后传进来**：这里不查设置、不查注册表，
     * 这样同一套渲染既服务于播放页的「来源」页，也服务于兜底生成的列表。
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
     * 把来源列表铺进容器。播放页「来源」页与兜底列表读同一份渲染，逐像素一致。
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

    /** 能力 → 已本地化标签。设置页与来源列表读同一份，避免加能力时只改一处。 */
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
