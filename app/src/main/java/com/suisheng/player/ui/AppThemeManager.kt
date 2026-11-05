package com.suisheng.player.ui

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.model.ThemeAccent

/**
 * 主题色的**代码侧**解析器。
 *
 * 界面的颜色一律由 theme 在 inflate 阶段解析（`design/04 §2.5`），本类存在的唯一理由是
 * 那几个**不在 Activity theme 作用域内**的地方：悬浮歌词窗口、仪表屏 Presentation、
 * 通知 `smallIcon.setColor()`——它们拿到的 Context 没有 `Theme.Suisheng.*`，
 * 读 `?attr/` 会直接失败。
 *
 * 因此 [palette] 刻意**不读 theme attr**，而是按 accent 直接取 `R.color.tu_<accent>_<token>`：
 * 这些颜色在 `values/` 与 `values-night/` 各有一份，深色仍然自动生效，
 * 同时不依赖调用方 Context 套了什么主题。
 *
 * 原「遍历 View 树刷色」的 10 个函数已在 2b-3 随布局 `?attr/` 化一并删除：
 * 两套机制并存会多出一个真相来源，而布局里现在一个写死颜色都没有了。
 */
object AppThemeManager {

    data class Palette(
        @ColorInt val background: Int,
        @ColorInt val surface: Int,
        @ColorInt val textPrimary: Int,
        @ColorInt val textSecondary: Int,
        @ColorInt val primary: Int,
    )

    /**
     * 按当前强调色取 5 个语义槽位。
     *
     * | 槽位 | token |
     * |---|---|
     * | background | `surface` |
     * | surface | `surface_container` |
     * | textPrimary | `on_surface` |
     * | textSecondary | `on_surface_variant` |
     * | primary | `primary` |
     *
     * 原来的「底栏底色」槽位已随底部 3 tab 一起下线（期 2c-1 改用抽屉导航）。
     */
    fun palette(context: Context, settings: SettingsRepository): Palette {
        val accent = settings.themeAccent
        return Palette(
            background = token(context, accent, "surface"),
            surface = token(context, accent, "surface_container"),
            textPrimary = token(context, accent, "on_surface"),
            textSecondary = token(context, accent, "on_surface_variant"),
            primary = token(context, accent, "primary"),
        )
    }

    /**
     * 取某套 accent 的某个 token 色值。
     *
     * 资源名按 `tu_<accentId>_<token>` 拼出来后查表；查不到返回透明色，
     * 保证「配色名写错」的后果是某处不显示，而不是启动崩溃。
     * 结果做进程内缓存：`getIdentifier` 每次都要走一遍资源表，绘制路径上不能反复调。
     */
    @ColorInt
    fun token(context: Context, accent: ThemeAccent, token: String): Int {
        val key = "${accent.id}_$token"
        cache[key]?.let { return it }
        val id = context.resources.getIdentifier(
            "tu_$key",
            "color",
            context.packageName,
        )
        val color = if (id != 0) ContextCompat.getColor(context, id) else 0
        cache[key] = color
        return color
    }

    /** 供 [token] 复用的进程内缓存；颜色是静态资源，无失效需求。 */
    private val cache = HashMap<String, Int>()
}
