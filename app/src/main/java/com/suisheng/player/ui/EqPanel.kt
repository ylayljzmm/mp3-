package com.suisheng.player.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import com.suisheng.player.R
import com.suisheng.player.data.EqSettings
import com.suisheng.player.databinding.PanelEqBinding
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 音效（均衡器）面板（`design/05 §3.4` 功能行第 4 格）。
 *
 * 与 [SpeedPanel] / [QueuePanel] 同一个模式：`object` + `BottomSheetDialog` + ViewBinding，
 * 面板**不持有播放器**，也不起 Service —— 只做「改值 → 回调」，由宿主
 * （[PlayerFragment]）负责落盘与通知 `MusicPlaybackService`。
 *
 * ## 谁写 prefs
 *
 * 这里直接改宿主传进来的 [EqSettings] 实例（它的 setter 本身就是「内存 + 落盘」一次完成），
 * 所以回调时 `onApply` 收到的就是最新值；宿主再 `applyFrom()` 一次是幂等的空写
 * （`other === this` 时直接 return）。之所以不搞「内存草稿 + 一次性提交」，
 * 是因为均衡器没有「取消/确定」按钮：用户拖完就期望立刻听到变化。
 *
 * ## 回调时机
 *
 * 滑块拖动过程中**不回调**（每次回调都会让服务重设全部 band 的电平，连续拖会听到咔哒声），
 * 只在 `onStopTrackingTouch`（抬手）时回调一次；`onValueChange` 只负责刷新右侧数字。
 * 开关与预设 chip 是离散动作，点了立刻回调。
 */
object EqPanel {

    /**
     * 弹出音效面板。
     *
     * @param current 当前设置（宿主持有的实例，面板会就地修改它）
     * @param supported 设备是否支持均衡器。调用方不知道能力时传 `true`（面板按「有滑块就可用」
     *   处理）；传 `false` 时滑块 / chip / 开关全部置灰，底部显示「当前设备不支持音效」。
     * @param onApply 每次有效改动后回调，参数是**已包含最新值的同一份** [EqSettings]
     */
    fun show(
        context: Context,
        current: EqSettings,
        supported: Boolean = true,
        onApply: (EqSettings) -> Unit,
    ) {
        val binding = PanelEqBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(binding.root)

        // 就地改宿主那份实例：setter 一次完成「内存 + prefs」，回调里不需要再搬一遍
        val state = current

        val sliders = listOf(
            binding.band0,
            binding.band1,
            binding.band2,
            binding.band3,
            binding.band4,
        )
        val valueLabels = listOf(
            binding.band0Value,
            binding.band1Value,
            binding.band2Value,
            binding.band3Value,
            binding.band4Value,
        )
        // 顺序与 EqSettings.PRESET_ORDER 一致；标签文案写在布局里，这里只认 id
        val presetChips = listOf(
            EqSettings.PRESET_FLAT to binding.presetFlat,
            EqSettings.PRESET_POP to binding.presetPop,
            EqSettings.PRESET_ROCK to binding.presetRock,
            EqSettings.PRESET_CLASSICAL to binding.presetClassical,
            EqSettings.PRESET_VOCAL to binding.presetVocal,
            EqSettings.PRESET_BASS to binding.presetBass,
        )

        fun renderValueLabels() {
            sliders.forEachIndexed { index, slider ->
                valueLabels[index].text = formatLevel(slider.value)
            }
        }

        /**
         * 把 [gains] 回填到 5 个滑块。
         *
         * `Slider` 的 `value` 必须是 `valueFrom + n * stepSize`，否则 `setValue` 直接抛
         * `IllegalStateException` —— 所以先 `roundToInt()` 对齐到 1dB 再钳位。
         */
        fun renderSliders(gains: FloatArray) {
            sliders.forEachIndexed { index, slider ->
                val aligned = gains.getOrElse(index) { 0f }
                    .roundToInt()
                    .coerceIn(EqSettings.MIN_GAIN_DB.toInt(), EqSettings.MAX_GAIN_DB.toInt())
                    .toFloat()
                slider.value = aligned
            }
            renderValueLabels()
        }

        /** chip 勾选同步；[EqSettings.PRESET_CUSTOM] 时 6 个 chip 全清空并露出「自定义」。 */
        fun renderChips(presetId: String) {
            presetChips.forEach { entry -> entry.second.isChecked = entry.first == presetId }
            binding.eqCustomLabel.visibility =
                if (presetId == EqSettings.PRESET_CUSTOM) View.VISIBLE else View.GONE
        }

        fun publish() {
            onApply(state)
        }

        // 回填开关时会触发监听器，这个标志挡住它（否则打开面板就白通知一次服务）
        var syncingSwitch = false

        /**
         * 用户动了预设 / 滑块就顺手把总开关打开。
         *
         * 开关出厂是关的，如果拖了滑块却听不到任何变化，看起来就是「坏了」。
         * 这里同步把开关 UI 拨上去（不再额外 `publish()`，由调用方统一发一次）。
         */
        fun autoEnable() {
            if (state.enabled) return
            state.enabled = true
            syncingSwitch = true
            binding.eqSwitch.isChecked = true
            syncingSwitch = false
        }

        // ---------------------------------------------------------- 开关
        // 先回填再挂监听：`isChecked =` 会触发 OnCheckedChangeListener（它不给 fromUser 标志）
        syncingSwitch = true
        binding.eqSwitch.isChecked = state.enabled
        syncingSwitch = false
        binding.eqSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            state.enabled = checked
            publish()
        }

        // ---------------------------------------------------------- 预设
        presetChips.forEach { entry ->
            val presetId = entry.first
            val chip = entry.second
            chip.tag = presetId
            chip.setOnClickListener {
                val gains = state.gainsForPreset(presetId)
                state.presetId = presetId
                state.setBandGains(gains)
                renderSliders(gains)
                renderChips(presetId)
                autoEnable()
                publish()
            }
        }

        // ---------------------------------------------------------- 5 段滑块
        sliders.forEachIndexed { index, slider ->
            slider.addOnChangeListener { _, value, fromUser ->
                // 拖动过程中只跟手改数字，不回调（见类注释）
                if (fromUser) valueLabels[index].text = formatLevel(value)
            }
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = Unit

                override fun onStopTrackingTouch(slider: Slider) {
                    val gains = FloatArray(EqSettings.BAND_COUNT) { band -> sliders[band].value }
                    state.setBandGains(gains)
                    // 手动拖过就不再属于任何预设：清空 chip + 露出「自定义」
                    state.presetId = EqSettings.PRESET_CUSTOM
                    renderChips(EqSettings.PRESET_CUSTOM)
                    renderValueLabels()
                    autoEnable()
                    publish()
                }
            })
        }

        // ---------------------------------------------------------- 回填 + 设备能力
        renderSliders(state.bandGains())
        renderChips(state.presetId)

        if (!supported) {
            // 设备没有可用均衡器：所有可交互控件置灰，底部给一句原因
            binding.eqHint.setText(R.string.eq_unsupported)
            binding.eqHint.visibility = View.VISIBLE
            binding.eqSwitch.isEnabled = false
            presetChips.forEach { entry -> entry.second.isEnabled = false }
            sliders.forEach { slider -> slider.isEnabled = false }
        } else {
            binding.eqHint.visibility = View.GONE
        }

        dialog.show()
    }

    /**
     * 右侧数值文案：`+3dB` / `-12dB` / `+0dB`。
     *
     * 固定 `Locale.US`，避免某些地区把符号位置或小数点改掉；`%+d` 保证 0 也带正号，
     * 这样 5 行数字永远等宽对齐（配合布局里的 `tnum`）。
     */
    private fun formatLevel(db: Float): String =
        String.format(Locale.US, "%+ddB", db.roundToInt())
}
