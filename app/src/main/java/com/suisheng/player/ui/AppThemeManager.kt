package com.suisheng.player.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.switchmaterial.SwitchMaterial
import com.suisheng.player.R
import com.suisheng.player.data.SettingsRepository

/**
 * 旧「递归刷色器」的过渡期形态。
 *
 * `design/04 §2.5` 的终局是：颜色全部在 inflate 阶段由 theme 解析，布局写 `?attr/`，
 * 代码侧不再有遍历视图树刷色的逻辑。但布局里还留着 124 处裸 `@color/`（`04 §2.5`
 * 的清理属 2b-3），此刻若直接删掉 [applyFragmentRoot]，那 6 个 Fragment 的根布局会
 * 掉回旧的深海军蓝底色，界面变成「新主题 + 旧黑面板」，比不动更难看。
 *
 * 所以 2b-2 只做两件事，且顺序有依赖：
 * ① 把 [palette] 从「读三套硬编码 AppThemePreset」改成「读当前已应用主题的 M3 token」——
 *    这一步做完，所有刷色调用点不改一行就整体变成新配色；
 * ② 删掉唯一与「封面取色」绑定、且已被 `04 §2.6` 播放页分控主题取代的两个函数
 *    `playerBottomNavColors` 与 `applyPlayerBottomNav`（已删除）。
 *
 * 2b-3 布局 `@color/` 清零后，本文件的 [applyBottomNav] / [applyFragmentRoot] /
 * [applyTextColorIfThemed] / [visitViews] / [dockBackground] 连同 7 处调用点一起删除，
 * 只留 [palette]。**这个顺序不要提前**：提前删会留下一段「新底色配旧面板」的界面。
 */
object AppThemeManager {
    data class Palette(
        val background: Int,
        val surface: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val bottomNavBg: Int,
        val primary: Int
    )

    /**
     * 读取当前 theme 的颜色。
     *
     * 每个字段都是「语义槽位」，不再是某一套固定配色的具体色值：
     * | 槽位 | 取自 |
     * |---|---|
     * | background | `colorSurface`（页面底） |
     * | surface | `colorSurfaceContainer`（卡片比页面高一层） |
     * | textPrimary | `colorOnSurface` |
     * | textSecondary | `colorOnSurfaceVariant` |
     * | bottomNavBg | `colorSurfaceContainer`（底栏与卡片同层） |
     * | primary | `colorPrimary` |
     *
     * @param settings 当前**未使用**。保留该形参纯粹是为了让 7 处调用点一行都不用改
     *   （`04 §2.5` 要求本期的改动面尽量小）；2b-3 删除这些调用点时会一并去掉。
     */
    @Suppress("UNUSED_PARAMETER")
    fun palette(context: Context, settings: SettingsRepository): Palette = Palette(
        background = themeColor(context, com.google.android.material.R.attr.colorSurface),
        surface = themeColor(context, com.google.android.material.R.attr.colorSurfaceContainer),
        textPrimary = themeColor(context, com.google.android.material.R.attr.colorOnSurface),
        textSecondary = themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant),
        bottomNavBg = themeColor(context, com.google.android.material.R.attr.colorSurfaceContainer),
        primary = themeColor(context, com.google.android.material.R.attr.colorPrimary)
    )

    /**
     * 解析一个 M3 颜色 attr。
     *
     * 主题缺该 attr 时返回透明色而不是抛异常：配色错最多是「某处不显示」，
     * 不能让车机在开机路径上因为取色失败闪退。
     */
    @ColorInt
    private fun themeColor(context: Context, @AttrRes attr: Int): Int =
        MaterialColors.getColor(context, attr, Color.TRANSPARENT)

    fun applyBottomNav(
        nav: BottomNavigationView,
        palette: Palette,
        backgroundOverride: Int? = null,
        dock: View? = null
    ) {
        val bg = backgroundOverride ?: palette.bottomNavBg
        val onDark = isColorDark(bg)
        nav.background = dockBackground(nav, bg)
        dock?.setBackgroundColor(bg)
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val selected = palette.primary
        val unselected = if (onDark) {
            Color.argb(170, Color.red(palette.textPrimary), Color.green(palette.textPrimary), Color.blue(palette.textPrimary))
        } else {
            palette.textSecondary
        }
        val colors = intArrayOf(selected, unselected)
        nav.itemIconTintList = ColorStateList(states, colors)
        nav.itemTextColor = ColorStateList(states, colors)
    }

    fun applyCard(card: MaterialCardView, palette: Palette) {
        card.setCardBackgroundColor(palette.surface)
    }

    fun applyFragmentRoot(root: View, palette: Palette) {
        root.setBackgroundColor(palette.background)
        visitViews(root) { view ->
            when (view) {
                is MaterialCardView -> view.setCardBackgroundColor(palette.surface)
                is TextView -> applyTextColorIfThemed(view, palette)
                is RadioButton -> applyTextColorIfThemed(view, palette)
                is SwitchMaterial -> applyTextColorIfThemed(view, palette)
            }
        }
    }

    /**
     * 只改写「原本写作语义色」的文字颜色。
     *
     * 判断依据是 `currentTextColor` 与旧 `@color/text_primary` 等资源值相等——
     * 2b-3 把布局换成 `?attr/` 之后这些比较自然全部失配，本函数随之变成空转，
     * 正好可以和调用点一起删除。
     */
    private fun applyTextColorIfThemed(view: TextView, palette: Palette) {
        val context = view.context
        val color = view.currentTextColor
        when (color) {
            ContextCompat.getColor(context, R.color.text_primary),
            ContextCompat.getColor(context, R.color.zelda_text_primary),
            ContextCompat.getColor(context, R.color.mario_text_primary) ->
                view.setTextColor(palette.textPrimary)
            ContextCompat.getColor(context, R.color.text_secondary),
            ContextCompat.getColor(context, R.color.text_hint),
            ContextCompat.getColor(context, R.color.zelda_text_secondary),
            ContextCompat.getColor(context, R.color.mario_text_secondary) ->
                view.setTextColor(palette.textSecondary)
        }
    }

    /** 导航栏与页面同色，仅在顶部留 1dp 发丝线，避免内容区与底栏之间出现黑缝 */
    private fun dockBackground(nav: BottomNavigationView, bg: Int): Drawable {
        val solid = GradientDrawable().apply { setColor(bg) }
        val hairline = GradientDrawable().apply { setColor(blendColors(bg, Color.WHITE, 0.10f)) }
        val lineHeight = nav.resources.displayMetrics.density.toInt().coerceAtLeast(1)
        return LayerDrawable(arrayOf<Drawable>(solid, hairline)).apply {
            setLayerHeight(1, lineHeight)
            setLayerGravity(1, Gravity.TOP)
        }
    }

    private fun isColorDark(color: Int): Boolean {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return luminance < 0.5
    }

    private fun blendColors(from: Int, to: Int, ratio: Float): Int {
        val inverse = 1f - ratio
        return Color.rgb(
            (Color.red(from) * inverse + Color.red(to) * ratio).toInt().coerceIn(0, 255),
            (Color.green(from) * inverse + Color.green(to) * ratio).toInt().coerceIn(0, 255),
            (Color.blue(from) * inverse + Color.blue(to) * ratio).toInt().coerceIn(0, 255)
        )
    }

    private fun visitViews(view: View, block: (View) -> Unit) {
        block(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                visitViews(view.getChildAt(i), block)
            }
        }
    }
}
