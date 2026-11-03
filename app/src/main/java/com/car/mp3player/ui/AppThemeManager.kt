package com.car.mp3player.ui

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
import androidx.core.content.ContextCompat
import com.car.mp3player.R
import com.car.mp3player.data.SettingsRepository
import com.car.mp3player.model.AppThemePreset
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial

object AppThemeManager {
    data class Palette(
        val background: Int,
        val surface: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val bottomNavBg: Int,
        val primary: Int
    )

    fun palette(context: Context, settings: SettingsRepository): Palette {
        return when (settings.appTheme()) {
            AppThemePreset.ZELDA -> Palette(
                background = ContextCompat.getColor(context, R.color.zelda_background),
                surface = ContextCompat.getColor(context, R.color.zelda_surface),
                textPrimary = ContextCompat.getColor(context, R.color.zelda_text_primary),
                textSecondary = ContextCompat.getColor(context, R.color.zelda_text_secondary),
                bottomNavBg = ContextCompat.getColor(context, R.color.zelda_bottom_nav),
                primary = ContextCompat.getColor(context, R.color.zelda_primary)
            )
            AppThemePreset.MARIO -> Palette(
                background = ContextCompat.getColor(context, R.color.mario_background),
                surface = ContextCompat.getColor(context, R.color.mario_surface),
                textPrimary = ContextCompat.getColor(context, R.color.mario_text_primary),
                textSecondary = ContextCompat.getColor(context, R.color.mario_text_secondary),
                bottomNavBg = ContextCompat.getColor(context, R.color.mario_bottom_nav),
                primary = ContextCompat.getColor(context, R.color.mario_primary)
            )
            AppThemePreset.NETEASE -> Palette(
                background = ContextCompat.getColor(context, R.color.background),
                surface = ContextCompat.getColor(context, R.color.surface),
                textPrimary = ContextCompat.getColor(context, R.color.text_primary),
                textSecondary = ContextCompat.getColor(context, R.color.text_secondary),
                bottomNavBg = ContextCompat.getColor(context, R.color.netease_bottom_nav),
                primary = ContextCompat.getColor(context, R.color.netease_red)
            )
        }
    }

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

    fun playerBottomNavColors(palette: Palette, playerBackground: Int): Int {
        return blendColors(playerBackground, Color.parseColor("#CC141414"), 0.55f)
    }

    fun applyPlayerBottomNav(
        nav: BottomNavigationView,
        palette: Palette,
        playerBackground: Int,
        dock: View? = null
    ) {
        val bg = playerBottomNavColors(palette, playerBackground)
        val onDark = isColorDark(bg)
        val selected = if (onDark) Color.WHITE else palette.primary
        val unselected = if (onDark) Color.argb(160, 255, 255, 255) else palette.textSecondary
        nav.background = dockBackground(nav, bg)
        dock?.setBackgroundColor(bg)
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        nav.itemIconTintList = ColorStateList(states, intArrayOf(selected, unselected))
        nav.itemTextColor = ColorStateList(states, intArrayOf(selected, unselected))
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
