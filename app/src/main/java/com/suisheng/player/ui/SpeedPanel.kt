package com.suisheng.player.ui

import android.content.Context
import android.view.LayoutInflater
import com.google.android.material.color.MaterialColors
import com.suisheng.player.R
import com.suisheng.player.databinding.PanelSpeedBinding
import java.util.Locale

/**
 * 倍速面板（`design/05 §3.4` 第 3 格）。
 *
 * 5 档固定值，选完**立即**写入 `SettingsRepository.KEY_SPEED` 并回调宿主生效（§12 Q3：
 * 全局记住、不随切歌重置）。面板本身不持有播放器，只做「选项 → 回调」。
 */
object SpeedPanel {

    /** 0.75 / 1.0 / 1.25 / 1.5 / 2.0（`05 §3.4`）。 */
    val OPTIONS = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    /** 出厂/复位值。 */
    const val DEFAULT_SPEED = 1.0f

    /**
     * 显示成「1.0x / 0.75x / 1.5x」：整数档保留一位小数（1.0 而不是 1），
     * 非整数档去掉末尾的 0。按钮上显示的就是这个字符串。
     */
    fun label(speed: Float): String {
        val text = if (speed % 1f == 0f) {
            String.format(Locale.US, "%.1f", speed)
        } else {
            String.format(Locale.US, "%.2f", speed).trimEnd('0')
        }
        return text
    }

    /**
     * 弹出面板。
     *
     * @param current 当前倍速，用来打勾
     * @param onPick 选中后回调（宿主负责写设置 + 通知播放服务）
     */
    fun show(context: Context, current: Float, onPick: (Float) -> Unit) {
        val binding = PanelSpeedBinding.inflate(LayoutInflater.from(context))
        // 统一走 PanelHost：车机是居中卡片、手机是贴底抽屉，两者都由宿主限高与定宽，
        // 避免车机上这个自建 BottomSheet 再次出现「只露一行标题」。
        val dialog = PanelHost.build(context, binding.root, scrollable = true)

        val rows = listOf(
            binding.speedSlowest,
            binding.speedNormal,
            binding.speedFast,
            binding.speedFaster,
            binding.speedFastest,
        )

        fun render(selected: Float) {
            rows.forEachIndexed { index, row ->
                val option = OPTIONS[index]
                val active = kotlin.math.abs(option - selected) < 0.001f
                row.text = if (active) {
                    context.getString(R.string.speed_current, label(option))
                } else {
                    context.getString(R.string.speed_x, label(option))
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
                row.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }

        rows.forEachIndexed { index, row ->
            row.setOnClickListener {
                val picked = OPTIONS[index]
                render(picked)
                onPick(picked)
                dialog.dismiss()
            }
        }

        render(current)
        dialog.show()
    }
}
