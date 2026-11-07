package com.suisheng.player.ui

import com.google.android.material.slider.Slider
import kotlin.math.roundToLong

/**
 * Material [Slider] 的安全赋值。
 *
 * ## 为什么需要它
 *
 * `BaseSlider.setValue()` 的校验非常严格：值一旦落在 `valueFrom..valueTo` 之外、
 * 或者不是 `stepSize` 的整数倍，直接抛 `IllegalStateException`。
 * 而设置项是持久化在 SharedPreferences 里的 —— 用户从旧版本升上来、或者某次
 * `valueTo` 被调小（比如歌词字号上限从 60 收到 56），存量值就越界了。
 * 车机上真实的崩溃栈就是这么来的：`fragment_settings` 在 `onViewCreated` 里
 * 一次性给 8 个 slider 赋值，第一个越界就把整页炸掉，用户看到的是「点歌词设置闪退」。
 *
 * 所以**所有**「把存量值写进 slider」的动作都必须走这里：先夹到区间内，
 * 再按 `stepSize` 对齐，最后才赋值，并且整段包 `runCatching` —— 万一 Material
 * 将来又加新的校验，最坏结果也只是滑块停在默认位置，而不是崩掉整个页面。
 */
internal object SliderBinding {

    /**
     * 安全赋值并挂监听。
     *
     * 先赋值、后挂监听：否则赋值这一步会立刻回调一次 `onChange`，
     * 把「读出来的旧值」当成「用户刚改的新值」再写回 prefs 一次（无害但很脏，
     * 而且会在切主题重排滑块时反复触发歌词重绘）。
     */
    fun bind(slider: Slider, value: Float, onChange: (Float) -> Unit) {
        setValueSafely(slider, value)
        slider.addOnChangeListener { _, newValue, _ -> onChange(newValue) }
    }

    /**
     * 只赋值、不挂监听。
     *
     * 给「切换歌词配色后字号被预设改写了，要把滑块同步过去」这类场景用 ——
     * 那里不能再用 [bind]，否则每切一次配色就多挂一个监听。
     */
    fun setValueSafely(slider: Slider, value: Float) {
        runCatching {
            val from = slider.valueFrom
            val to = slider.valueTo
            val step = slider.stepSize

            // NaN / Infinity 也会被 BaseSlider 拒绝，统一回落到下界
            var target = if (value.isFinite()) value else from
            target = target.coerceIn(from, to)

            if (step > 0f) {
                // 四舍五入到最近的步进点，再夹一次：浮点乘加可能落到 to 外面一点点
                val steps = ((target - from) / step).roundToLong()
                target = (from + steps * step).coerceIn(from, to)
            }
            slider.value = target
        }
    }
}
