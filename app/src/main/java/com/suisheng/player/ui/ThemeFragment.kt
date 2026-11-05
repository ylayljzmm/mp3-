package com.suisheng.player.ui

import android.app.TimePickerDialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.suisheng.player.R
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.databinding.FragmentThemeBinding
import com.suisheng.player.model.ThemeAccent
import com.suisheng.player.theme.NightMode
import com.suisheng.player.theme.ThemeController
import java.util.Calendar

/**
 * 主题设置页（`design/04 §3.7`）。
 *
 * 2c-2 起是**普通 Fragment**（此前是全屏 DialogFragment）：
 * 有了抽屉导航，主题页就是一个正常的目的地 [Destination.THEME]，
 * 从抽屉底部第三格或「设置 → 外观」进来都走 `MainHost.openTheme()`，
 * 返回键回它进来的那一页（由 `MainHost.navigateUp()` 决定）。
 *
 * 页面骨架严格按 §3.7 的 A–G 七段：
 * A 明暗 4 档 / B 夜间时段 / C 主题色网格 / D 预览卡 /
 * E 播放页单独配色 / F 封面取色 / G 恢复默认。
 */
class ThemeFragment : Fragment() {

    private var _binding: FragmentThemeBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: SettingsRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentThemeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsRepository(requireContext())

        binding.btnThemeBack.setOnClickListener { (activity as? MainHost)?.navigateUp() }
        setupModeToggle()
        setupNightRange()
        renderAccentGrid()
        setupPlayerSplit()
        setupCoverTint()
        binding.btnResetTheme.setOnClickListener { confirmReset() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------- A 明暗 4 档 ----------

    private fun setupModeToggle() {
        val checked = when (settings.nightMode) {
            NightMode.LIGHT -> R.id.btnModeLight
            NightMode.DARK -> R.id.btnModeDark
            NightMode.FOLLOW_SYSTEM -> R.id.btnModeSystem
            NightMode.TIME_AUTO -> R.id.btnModeAuto
        }
        binding.modeGroup.check(checked)
        updateNightRangeVisibility(settings.nightMode)

        binding.modeGroup.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (buttonId) {
                R.id.btnModeLight -> NightMode.LIGHT
                R.id.btnModeDark -> NightMode.DARK
                R.id.btnModeSystem -> NightMode.FOLLOW_SYSTEM
                else -> NightMode.TIME_AUTO
            }
            if (mode == settings.nightMode) return@addOnButtonCheckedListener
            settings.nightMode = mode
            updateNightRangeVisibility(mode)
            settings.applyTheme()
            // 明暗切换会改变资源限定符，已 inflate 的视图不会自动重取色，
            // 必须重建宿主 Activity；Dialog 随之关闭，用户从设置页看到结果。
            activity?.recreate()
        }
    }

    private fun updateNightRangeVisibility(mode: NightMode) {
        binding.nightRangeGroup.visibility =
            if (mode == NightMode.TIME_AUTO) View.VISIBLE else View.GONE
        if (mode == NightMode.TIME_AUTO) {
            refreshNightRangeLabels()
        }
    }

    // ---------- B 夜间时段 ----------

    private fun setupNightRange() {
        binding.rowNightStart.setOnClickListener { pickMinute(isStart = true) }
        binding.rowNightEnd.setOnClickListener { pickMinute(isStart = false) }
    }

    private fun refreshNightRangeLabels() {
        val (start, end) = currentNightRange()
        binding.txtNightStartValue.text = formatMinute(start)
        binding.txtNightEndValue.text = formatMinute(end)
    }

    /**
     * 用户没手动设过，就显示内置日出日落表算出来的当日区间——
     * 这样界面上看到的时间就是「现在真正生效的时间」。
     */
    private fun currentNightRange(): Pair<Int, Int> = ThemeController.nightRange(
        context = requireContext(),
        calendar = Calendar.getInstance(),
        overrideStart = settings.nightStartMinuteOverride,
        overrideEnd = settings.nightEndMinuteOverride,
    )

    private fun pickMinute(isStart: Boolean) {
        val (start, end) = currentNightRange()
        val initial = if (isStart) start else end
        TimePickerDialog(
            requireContext(),
            { _, hour, minute ->
                val value = hour * 60 + minute
                if (isStart) {
                    settings.nightStartMinuteOverride = value
                } else {
                    settings.nightEndMinuteOverride = value
                }
                refreshNightRangeLabels()
                settings.applyTheme()
                activity?.recreate()
            },
            initial / 60,
            initial % 60,
            true,
        ).show()
    }

    private fun formatMinute(minute: Int): String {
        val normalized = ThemeController.normalizeMinute(minute)
        return String.format("%02d:%02d", normalized / 60, normalized % 60)
    }

    // ---------- C 主题色网格 / E 播放页网格 ----------

    private fun renderAccentGrid() {
        renderSwatches(binding.swatchContainer, settings.themeAccent) { accent ->
            if (accent == settings.themeAccent) return@renderSwatches
            settings.themeAccent = accent
            // 换主题要重建 Activity；重建后本页仍是当前目的地，用户留在原地看新配色，
            // 不需要手动跳回（原来 Dialog 版本是 dismiss，那是「对话框必须关掉」的限制）。
            activity?.recreate()
        }
    }

    private fun renderPlayerSwatches() {
        renderSwatches(binding.playerSwatchContainer, settings.playerThemeAccent) { accent ->
            if (accent == settings.playerThemeAccent) return@renderSwatches
            settings.playerThemeAccent = accent
            renderPlayerSwatches()
        }
    }

    /**
     * 画 10 套色卡。横屏 5 列 × 2 行、竖屏 4 列（`04 §2.8` 第一条）。
     *
     * 选中态按 §2.8 第二条叠三道：外环 2dp + 空隙 4dp + 中心 ✓ 28dp，
     * 名称换 `?attr/colorPrimary` 并加粗 500——车机上远距离扫一眼就能定位。
     */
    private fun renderSwatches(
        container: LinearLayout,
        selected: ThemeAccent,
        onPick: (ThemeAccent) -> Unit,
    ) {
        container.removeAllViews()
        val columns =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 5 else 4
        ThemeAccent.entries.chunked(columns).forEach { rowAccents ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            rowAccents.forEach { accent ->
                row.addView(buildSwatchCell(accent, accent == selected, onPick))
            }
            // 末行不足列数时补空位，保持左对齐而不是被拉伸居中
            repeat(columns - rowAccents.size) {
                row.addView(
                    View(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    }
                )
            }
            container.addView(row)
        }
    }

    private fun buildSwatchCell(
        accent: ThemeAccent,
        selected: Boolean,
        onPick: (ThemeAccent) -> Unit,
    ): View {
        val density = resources.displayMetrics.density
        val dotSize = resources.getDimensionPixelSize(R.dimen.theme_swatch_size)
        val ringWidth = resources.getDimensionPixelSize(R.dimen.theme_swatch_ring_width)
        val ringGap = resources.getDimensionPixelSize(R.dimen.theme_swatch_ring_gap)
        val outerSize = dotSize + 2 * ringGap + 2 * ringWidth

        val cell = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }

        val frame = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(outerSize, outerSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                if (selected) {
                    setStroke(ringWidth, attrColor(com.google.android.material.R.attr.colorOnSurface))
                }
            }
        }

        val dot = View(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accentColor(accent, "primary"))
            }
        }
        frame.addView(dot)

        if (selected) {
            frame.addView(
                TextView(requireContext()).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    )
                    text = "✓"
                    textSize = 28f
                    setTextColor(accentColor(accent, "on_primary"))
                }
            )
        }
        cell.addView(frame)

        cell.addView(
            TextView(requireContext()).apply {
                text = accent.displayName
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(
                    if (selected) {
                        attrColor(com.google.android.material.R.attr.colorPrimary)
                    } else {
                        attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
                    }
                )
                typeface = if (selected) {
                    Typeface.create("sans-serif-medium", Typeface.NORMAL)
                } else {
                    Typeface.create("sans-serif", Typeface.NORMAL)
                }
            }
        )

        cell.setOnClickListener { onPick(accent) }
        return cell
    }

    @ColorInt
    private fun attrColor(attr: Int): Int =
        MaterialColors.getColor(requireContext(), attr, Color.TRANSPARENT)

    /**
     * 取某套 accent 的某个 token。
     *
     * 色卡必须显示「这套配色本身」的颜色，不能用当前主题的 attr——
     * 否则 10 个色卡会长得一模一样。`tu_*` 在 values/values-night 里各有定义，
     * 所以深色下取到的自然是深色那一版。
     */
    @ColorInt
    private fun accentColor(accent: ThemeAccent, token: String): Int {
        val id = resources.getIdentifier(
            "tu_${accent.id}_$token",
            "color",
            requireContext().packageName,
        )
        return if (id != 0) ContextCompat.getColor(requireContext(), id) else Color.TRANSPARENT
    }

    // ---------- E 播放页单独配色 ----------

    private fun setupPlayerSplit() {
        binding.swPlayerSplit.isChecked = settings.playerThemeSplitEnabled
        updatePlayerSwatchVisibility(settings.playerThemeSplitEnabled)
        binding.swPlayerSplit.setOnCheckedChangeListener { _, checked ->
            settings.playerThemeSplitEnabled = checked
            updatePlayerSwatchVisibility(checked)
        }
    }

    private fun updatePlayerSwatchVisibility(enabled: Boolean) {
        binding.playerSwatchContainer.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) renderPlayerSwatches()
    }

    // ---------- F 封面取色 ----------

    private fun setupCoverTint() {
        binding.swCoverTint.isChecked = settings.playerCoverTintEnabled
        binding.swCoverTint.setOnCheckedChangeListener { _, checked ->
            settings.playerCoverTintEnabled = checked
        }
    }

    // ---------- G 恢复默认 ----------

    private fun confirmReset() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.theme_reset)
            .setMessage(R.string.theme_reset_message)
            .setNegativeButton(R.string.theme_reset_cancel, null)
            .setPositiveButton(R.string.theme_reset_confirm) { _, _ -> resetToDefault() }
            .show()
    }

    private fun resetToDefault() {
        settings.themeAccent = ThemeAccent.DEFAULT
        settings.playerThemeAccent = ThemeAccent.DEFAULT
        settings.playerThemeSplitEnabled = false
        settings.playerCoverTintEnabled = true
        settings.nightMode = NightMode.DEFAULT
        settings.nightStartMinuteOverride = null
        settings.nightEndMinuteOverride = null
        settings.applyTheme()
        activity?.recreate()
    }

    companion object {
        const val TAG = "theme_fragment"
    }
}
