package com.suisheng.player.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Locale

/**
 * 音效（均衡器）设置的独立持久化。
 *
 * 刻意**不复用** [SettingsRepository]：音效是一组彼此耦合的强类型数据（开关 + 预设 + 5 段增益 +
 * 低音强度），单独一个 prefs 文件（[PREFS_NAME]）可以整体清空、单独迁移，也不会让设置页那份
 * 越来越长的 key 列表继续膨胀。做法与 [HiddenFolderStore] 一致。
 *
 * ## 为什么只有 5 段
 *
 * 系统 `android.media.audiofx.Equalizer` 的段数由设备决定（常见 5 / 10 段，少数车机 3 段），
 * 直接暴露设备段数会让 UI 随机器变形。所以 UI 固定 5 段（[UI_BAND_FREQS]），
 * 真正的设备段数由 [com.suisheng.player.playback.AudioEffectController] 按中心频率插值映射。
 *
 * ## 存储格式
 *
 * 增益沿用 `SettingsRepository.scanPathsText` 的口径：一个 key（[KEY_BANDS]）里用换行拼接
 * 5 个 float 字符串（`String.format(Locale.US, "%.2f", g)`，固定小数点避免某些地区把 `.`
 * 写成 `,`）。解析失败整体回落全 0，绝不抛异常 —— 设置读不出来只应「没音效」，不该崩。
 *
 * @param context 任意 Context，内部只取 `applicationContext`，不会泄漏 Activity。
 */
class EqSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var enabledValue: Boolean = prefs.getBoolean(KEY_ENABLED, false)

    private var presetIdValue: String = prefs.getString(KEY_PRESET, PRESET_FLAT) ?: PRESET_FLAT

    private var bandGainsValue: FloatArray = decodeGains(prefs.getString(KEY_BANDS, null))

    private var bassBoostValue: Int =
        prefs.getInt(KEY_BASS_BOOST, 0).coerceIn(MIN_BASS_BOOST, MAX_BASS_BOOST)

    /** 音效总开关，默认关（`false`）：不主动改变用户的听感。 */
    var enabled: Boolean
        get() = enabledValue
        set(value) {
            if (value == enabledValue) return
            enabledValue = value
            prefs.edit { putBoolean(KEY_ENABLED, value) }
        }

    /** 当前生效的预设 id（见 [PRESET_FLAT] 等；手动拖过滑块后是 [PRESET_CUSTOM]）。 */
    var presetId: String
        get() = presetIdValue
        set(value) {
            if (value == presetIdValue) return
            presetIdValue = value
            prefs.edit { putString(KEY_PRESET, value) }
        }

    /** 低音增强强度 `0..1000`（`BassBoost.setStrength` 的量程），默认 0 = 不增强。 */
    var bassBoost: Int
        get() = bassBoostValue
        set(value) {
            val clamped = value.coerceIn(MIN_BASS_BOOST, MAX_BASS_BOOST)
            if (clamped == bassBoostValue) return
            bassBoostValue = clamped
            prefs.edit { putInt(KEY_BASS_BOOST, clamped) }
        }

    /**
     * 5 段 UI 增益（dB），顺序与 [UI_BAND_FREQS] 一一对应，值域 [MIN_GAIN_DB]..[MAX_GAIN_DB]。
     *
     * 返回的是**副本**：调用方随便改，不会污染内存里这份（否则下次 `bandGains()`
     * 会读到一个没落盘的值）。
     */
    fun bandGains(): FloatArray = bandGainsValue.copyOf()

    /** 写入 5 段增益（长度不足补 0、超出丢弃、越界钳位），同时落盘。 */
    fun setBandGains(gains: FloatArray) {
        val normalized = normalizeGains(gains)
        bandGainsValue = normalized
        prefs.edit { putString(KEY_BANDS, encodeGains(normalized)) }
    }

    /**
     * 把另一份 [EqSettings] 的**全部字段**写回本实例（逐字段落盘）。
     *
     * 面板（[com.suisheng.player.ui.EqPanel]）拿的是宿主这份实例，正常情况下
     * `other === this`，这里直接 return 免得白写四次 prefs；保留这个方法是为了
     * 将来面板若改成「内存草稿 + 确认才生效」时宿主仍有一句统一的落盘出口。
     */
    fun applyFrom(other: EqSettings) {
        if (other === this) return
        val gains = other.bandGains()
        val preset = other.presetId
        val bass = other.bassBoost
        enabled = other.enabled
        presetId = preset
        setBandGains(gains)
        bassBoost = bass
    }

    /**
     * 预设 id → 5 段增益曲线（单位 dB，顺序同 [UI_BAND_FREQS]）。
     *
     * 这些是**经验值**，不是从某台设备的实测曲线拟合出来的：按「流行提中频 / 摇滚做微笑曲线 /
     * 古典两端微抬 / 人声只提中频 / 低音只抬低频」的通行听感手调，目的是让用户点一下就有
     * 可辨识的差别。未知 id 一律回落 [PRESET_FLAT]（全 0），不会抛。
     */
    fun gainsForPreset(id: String): FloatArray {
        return when (id) {
            PRESET_POP -> floatArrayOf(-1f, 2f, 4f, 3f, -1f)
            PRESET_ROCK -> floatArrayOf(5f, 3f, -1f, 3f, 4f)
            PRESET_CLASSICAL -> floatArrayOf(3f, 2f, 0f, 2f, 4f)
            PRESET_VOCAL -> floatArrayOf(-2f, 2f, 5f, 3f, 0f)
            PRESET_BASS -> floatArrayOf(8f, 6f, 2f, -1f, -2f)
            else -> FloatArray(BAND_COUNT)
        }
    }

    // ------------------------------------------------------------ 解析 / 编码

    private fun decodeGains(raw: String?): FloatArray {
        if (raw.isNullOrBlank()) return FloatArray(BAND_COUNT)
        val parts = raw.split(SEPARATOR)
        val parsed = FloatArray(BAND_COUNT)
        for (index in 0 until BAND_COUNT) {
            // 缺项或解析失败：整体回落全 0。半截曲线听起来比没音效更莫名其妙。
            val value = parts.getOrNull(index)?.trim()?.toFloatOrNull() ?: return FloatArray(BAND_COUNT)
            if (value.isNaN() || value.isInfinite()) return FloatArray(BAND_COUNT)
            parsed[index] = clampGain(value)
        }
        return parsed
    }

    private fun encodeGains(gains: FloatArray): String =
        gains.joinToString(SEPARATOR) { String.format(Locale.US, "%.2f", it) }

    private fun normalizeGains(gains: FloatArray): FloatArray =
        FloatArray(BAND_COUNT) { index -> clampGain(gains.getOrElse(index) { 0f }) }

    private fun clampGain(value: Float): Float =
        if (value.isNaN()) 0f else value.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

    companion object {
        /** prefs 文件名。 */
        private const val PREFS_NAME = "eq_settings"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_PRESET = "preset_id"
        private const val KEY_BANDS = "band_gains"
        private const val KEY_BASS_BOOST = "bass_boost"

        /** 多值分隔符，与项目里其它多值 key 保持同一口径。 */
        private const val SEPARATOR = "\n"

        /** UI 固定段数。设备段数由 `AudioEffectController` 插值映射。 */
        const val BAND_COUNT = 5

        /** 5 个 UI 频段的中心频率（Hz）：60 / 230 / 910 / 3600 / 14000。**只读，别改内容**。 */
        val UI_BAND_FREQS: FloatArray = floatArrayOf(60f, 230f, 910f, 3600f, 14000f)

        /** 增益上下限（dB）。 */
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f

        /** `BassBoost.setStrength` 的量程。 */
        const val MIN_BASS_BOOST = 0
        const val MAX_BASS_BOOST = 1000

        /** 预设 id：稳定字符串，落盘用，不要跟着文案改。 */
        const val PRESET_FLAT = "flat"
        const val PRESET_POP = "pop"
        const val PRESET_ROCK = "rock"
        const val PRESET_CLASSICAL = "classical"
        const val PRESET_VOCAL = "vocal"
        const val PRESET_BASS = "bass"

        /**
         * 手动拖过任一滑块后的「自定义」态。
         *
         * 严格说它不是一个可点的预设，只是 [presetId] 的一个取值：面板靠它把 6 个 chip
         * 全部取消勾选并露出「自定义」字样，下次打开面板还能正确回填「这不是任何预设」。
         * 它的曲线就是当前增益本身，所以 [gainsForPreset] 对它返回全 0（不会被用到）。
         */
        const val PRESET_CUSTOM = "custom"

        /** 预设展示顺序（面板 chip 的顺序也照这个来）。 */
        val PRESET_ORDER: List<String> = listOf(
            PRESET_FLAT, PRESET_POP, PRESET_ROCK, PRESET_CLASSICAL, PRESET_VOCAL, PRESET_BASS,
        )
    }
}
