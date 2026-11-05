package com.suisheng.player.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import com.suisheng.player.data.EqSettings
import kotlin.math.roundToInt

/**
 * 把 [EqSettings] 里的设置施加到系统音效上（不含任何 UI）。
 *
 * 生命周期跟着 `MusicPlaybackService` 的 ExoPlayer 走：服务拿到 audio session id 后构造一次，
 * 每次设置变化 `apply()` 一次，`onDestroy` 里 `release()`。
 *
 * ## 为什么要「降级到底」而不是保证生效
 *
 * `android.media.audiofx` 的可用性完全取决于设备厂商：有的车机 `Equalizer` 只有 3 段，
 * 有的干脆 `new Equalizer()` 直接抛 `UnsupportedOperationException` / `RuntimeException`，
 * 还有的 `BassBoost` 存在但 `strengthSupported == false`。任何一台设备不支持都**只是没音效**，
 * 绝不能把播放服务带崩 —— 所以这里每一次 `new` 与每一次 `setXxx` 都包在 `runCatching` 里。
 *
 * ## 5 段 UI → N 段设备的映射
 *
 * 见 [bandGainAt]：把设备的每个 band 中心频率（`getCenterFreq(band)`，单位 **milliHz**，
 * 除以 1000 得到 Hz）当成横坐标，在 [EqSettings.UI_BAND_FREQS] 这 5 个锚点之间做
 * **分段线性插值**，两端超出锚点范围时取端点值；插值结果 dB 转 **millibel**（×100）
 * 后钳到 `bandLevelRange`。
 *
 * @param audioSessionId `AudioManager.generateAudioSessionId()` 拿到、并已经
 *   `ExoPlayer.setAudioSessionId()` 设到播放器上的那个 id。传 0 表示没有可用会话，
 *   此时 [supported] 为 false。
 */
class AudioEffectController(private val audioSessionId: Int) {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null

    /** `never` 语义的创建标记：一个对象最多尝试创建一次，失败也不反复重试（每次重试都会打日志/耗电）。 */
    private var equalizerResolved = false
    private var bassBoostResolved = false

    /** 已经 `release()` 过，后续 `apply()` 全部空转（防止服务销毁后还有延迟回调打进来）。 */
    private var released = false

    /**
     * 设备是否真的能做均衡。构造后即可读（内部已经尝试创建过 [Equalizer] 并检查段数）。
     *
     * `false` 时 [apply] 是空操作 —— 面板据此把滑块置灰并提示用户。
     */
    val supported: Boolean

    init {
        val effect = ensureEqualizer()
        supported = effect != null && bandCount(effect) > 0
        // 结论回写给 UI（面板据此置灰 / 提示）
        EqSupportProbe.supported = supported
    }

    /**
     * 施加设置。开关关掉时只是 `enabled = false`（效果旁路，不改增益值），
     * 这样下次打开还是原来的曲线。
     */
    fun apply(settings: EqSettings) {
        val effect = ensureEqualizer()
        if (!supported || effect == null) return
        runCatching {
            if (!settings.enabled) {
                effect.setEnabled(false)
                bassBoost?.let { boost -> runCatching { boost.setEnabled(false) } }
                return
            }
            effect.setEnabled(true)
            applyBands(effect, settings.bandGains())
            applyBassBoost(settings.bassBoost)
        }
    }

    /** 释放系统音效句柄。可重复调用。 */
    fun release() {
        released = true
        runCatching { equalizer?.release() }
        equalizer = null
        equalizerResolved = true
        runCatching { bassBoost?.release() }
        bassBoost = null
        bassBoostResolved = true
    }

    // ------------------------------------------------------------ 均衡器

    private fun ensureEqualizer(): Equalizer? {
        if (equalizerResolved) return equalizer
        equalizerResolved = true
        if (released || audioSessionId == 0) return null
        equalizer = runCatching { Equalizer(EFFECT_PRIORITY, audioSessionId) }.getOrNull()
        return equalizer
    }

    private fun bandCount(effect: Equalizer): Int =
        runCatching { effect.getNumberOfBands().toInt() }.getOrDefault(0)

    private fun applyBands(effect: Equalizer, uiGains: FloatArray) {
        val count = bandCount(effect)
        if (count <= 0) return
        val range = runCatching { effect.getBandLevelRange() }.getOrNull()
        // bandLevelRange 是 ShortArray(2)：{下限, 上限}，单位 millibel。拿不到就按常见的 ±1500mB 兜底。
        val minMb = range?.getOrNull(0)?.toInt() ?: FALLBACK_MIN_MILLIBEL
        val maxMb = range?.getOrNull(1)?.toInt() ?: FALLBACK_MAX_MILLIBEL
        for (band in 0 until count) {
            // getCenterFreq 单位是 milliHz（毫赫兹），/1000 才是 Hz
            val centerHz = runCatching { effect.getCenterFreq(band.toShort()) / 1000f }.getOrDefault(0f)
            if (centerHz <= 0f) continue
            val gainDb = bandGainAt(uiGains, centerHz)
            val levelMb = (gainDb * MILLIBEL_PER_DECIBEL).roundToInt().coerceIn(minMb, maxMb)
            runCatching { effect.setBandLevel(band.toShort(), levelMb.toShort()) }
        }
    }

    /**
     * 分段线性插值：频率 [freqHz] 处的增益（dB）。
     *
     * ```text
     * 锚点 i: (f[i], g[i])   f = 60 / 230 / 910 / 3600 / 14000 Hz
     * 落在 [f[i], f[i+1]] 内：
     *     t = (freqHz - f[i]) / (f[i+1] - f[i])          // 0..1
     *     gain = g[i] + t * (g[i+1] - g[i])              // dB
     * 低于 f[0]  → g[0]（低频段锚点值，不再外推）
     * 高于 f[4]  → g[4]（高频段锚点值）
     * ```
     *
     * 注意这里是「按中心频率线性」而不是按对数频率（倍频程）线性 —— 与产品给的映射口径一致。
     */
    private fun bandGainAt(uiGains: FloatArray, freqHz: Float): Float {
        val anchors = EqSettings.UI_BAND_FREQS
        val usable = minOf(anchors.size, uiGains.size)
        if (usable <= 0) return 0f
        if (usable == 1) return uiGains[0]
        if (freqHz <= anchors[0]) return uiGains[0]
        if (freqHz >= anchors[usable - 1]) return uiGains[usable - 1]
        for (index in 0 until usable - 1) {
            val lowHz = anchors[index]
            val highHz = anchors[index + 1]
            if (freqHz < lowHz || freqHz > highHz) continue
            val span = highHz - lowHz
            if (span <= 0f) return uiGains[index]
            val t = (freqHz - lowHz) / span
            return uiGains[index] + t * (uiGains[index + 1] - uiGains[index])
        }
        return 0f
    }

    // ------------------------------------------------------------ 低音增强

    private fun ensureBassBoost(): BassBoost? {
        if (bassBoostResolved) return bassBoost
        bassBoostResolved = true
        if (released || audioSessionId == 0) return null
        bassBoost = runCatching { BassBoost(EFFECT_PRIORITY, audioSessionId) }.getOrNull()
        return bassBoost
    }

    private fun applyBassBoost(strength: Int) {
        if (strength <= 0) {
            // 0 就是不增强：直接旁路，别让设备白算一遍
            bassBoost?.let { boost -> runCatching { boost.setEnabled(false) } }
            return
        }
        val boost = ensureBassBoost() ?: return
        val strengthSupported = runCatching { boost.strengthSupported }.getOrDefault(false)
        if (!strengthSupported) return
        runCatching {
            boost.setStrength(strength.coerceIn(EqSettings.MIN_BASS_BOOST, EqSettings.MAX_BASS_BOOST).toShort())
            boost.setEnabled(true)
        }
    }

    companion object {
        /**
         * 最近一次探测到的设备能力，`null` = 还没探测过（服务还没创建过控制器）。
         *
         * 服务和 UI 跑在默认进程里，所以一个进程内的 `@Volatile` 字段就够用，不必跨进程读 prefs：
         * 面板据此把滑块置灰并提示「当前设备不支持音效」（`PlayerFragment` 里读这个值）。
         * 首次打开面板时通常还是 `null`（按「支持」处理），第一次施加之后就有结论了。
         */
        val lastKnownSupported: Boolean?
            get() = EqSupportProbe.supported
    }
}

/** 设备能力探测结果的进程内缓存：服务和 UI 在同一个进程，读写都不需要跨进程同步。 */
private object EqSupportProbe {
    @Volatile
    var supported: Boolean? = null
}

/** 效果优先级，0 = 与其它效果平级（系统里没有更低优先级的需求）。 */
private const val EFFECT_PRIORITY = 0

/** dB → millibel。 */
private const val MILLIBEL_PER_DECIBEL = 100f

/** `getBandLevelRange()` 拿不到时的兜底（mB），约 ±15dB，覆盖绝大多数设备的量程。 */
private const val FALLBACK_MIN_MILLIBEL = -1500
private const val FALLBACK_MAX_MILLIBEL = 1500
