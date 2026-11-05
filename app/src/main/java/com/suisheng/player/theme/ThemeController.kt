package com.suisheng.player.theme

import android.content.Context
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import com.suisheng.player.R
import com.suisheng.player.model.ThemeAccent
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * 明暗 4 档（`design/04 §2.2`）。
 *
 * 出厂默认取 [LIGHT]（浅色）——这是 `design/05 §5.2` 对 04「默认深色」的裁定覆盖。
 *
 * @property id 持久化标识，**不可重命名**
 * @property displayName 设置页显示文案
 */
enum class NightMode(
    val id: String,
    val displayName: String,
) {
    /** 恒浅色。 */
    LIGHT("light", "浅色"),

    /** 恒深色。 */
    DARK("dark", "深色"),

    /** 跟随系统的日夜切换。 */
    FOLLOW_SYSTEM("system", "跟随系统"),

    /** 按内置日出日落表自动切换（车机推荐档）。 */
    TIME_AUTO("auto", "按时间自动"),
    ;

    companion object {
        /** 出厂默认：浅色（05 §5.2 裁定）。 */
        val DEFAULT: NightMode = LIGHT

        /** 按持久化 id 解析，非法值回落 [DEFAULT]。 */
        fun fromId(id: String?): NightMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * 主题解析器：把「用户配置」翻译成「AppCompat 明暗模式 + 主题资源」。
 *
 * 设计约束（`design/04 §2.5`）：**颜色在 inflate 阶段由 theme 解析**，布局写
 * `?attr/colorPrimary`，所以切主题只需换 theme + 必要时 `recreate()`，
 * 不需要遍历视图刷色。这里因此只做「查表」，不做任何视图操作。
 *
 * 本类在 2b-1 阶段只提供纯函数，不读写 SharedPreferences——
 * 这样它可以独立单测，且 2b-2 接线时改动面最小。
 */
object ThemeController {

    /** 夜间开始默认值：18:30 = 1110 分钟（`04 §2.2`）。 */
    const val DEFAULT_NIGHT_START_MINUTE: Int = 1110

    /** 夜间结束默认值：06:30 = 390 分钟（`04 §2.2`）。 */
    const val DEFAULT_NIGHT_END_MINUTE: Int = 390

    private const val MINUTES_PER_DAY = 24 * 60

    /**
     * 把 [NightMode] 档位解析成 `AppCompatDelegate` 的明暗常量。
     *
     * @param isNightNow 当前是否处于夜间时段。仅当档位为 [NightMode.TIME_AUTO] 时被使用；
     *   其余档位忽略此参数，由调用方按需传入（可传 `false`）。
     * @return 取值恒为 `AppCompatDelegate.MODE_NIGHT_NO` / `MODE_NIGHT_YES` /
     *   `MODE_NIGHT_FOLLOW_SYSTEM` 三者之一，可直接交给 `AppCompatDelegate.setDefaultNightMode`。
     */
    fun nightModeConstant(mode: NightMode, isNightNow: Boolean): Int = when (mode) {
        NightMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        NightMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        NightMode.FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        NightMode.TIME_AUTO ->
            if (isNightNow) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
    }

    /**
     * 判断 [minuteOfDay]（当日第几分钟，0..1439）是否落在夜间区间内。
     *
     * 支持跨午夜（如 18:30 → 06:30）与不跨午夜（如 01:00 → 05:00）两种区间。
     * `start == end` 视为「无夜间区间」，恒返回 `false`，避免把整天都判成夜间。
     */
    fun isNight(minuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean {
        val now = normalizeMinute(minuteOfDay)
        val start = normalizeMinute(startMinute)
        val end = normalizeMinute(endMinute)
        if (start == end) return false
        return if (start < end) {
            now >= start && now < end
        } else {
            now >= start || now < end
        }
    }

    /** 把任意分钟数折算到 0..1439，便于容忍「24:30」这类越界输入。 */
    fun normalizeMinute(minute: Int): Int {
        val m = minute % MINUTES_PER_DAY
        return if (m < 0) m + MINUTES_PER_DAY else m
    }

    /**
     * 由内置日出日落表算出「当日夜间区间」的分钟值（`04 §2.2`）。
     *
     * 表中每月 1 日一个采样点，这里按「当月已过比例」在相邻两月之间线性插值，
     * 年末从 12 月回绕到 1 月。插值误差 < 15 分钟，对「天黑了切深色」足够。
     *
     * @param calendar 用其月/日/当月天数；时区无关
     * @return `Pair(夜间开始, 夜间结束)`，夜间开始 = 日落，夜间结束 = 日出
     */
    fun solarNightRange(context: Context, calendar: Calendar): Pair<Int, Int> {
        val sunrise = context.resources.getIntArray(R.array.night_schedule_sunrise)
        val sunset = context.resources.getIntArray(R.array.night_schedule_sunset)
        require(sunrise.size == 12 && sunset.size == 12) {
            "night_schedule 数组长度必须为 12，实际 sunrise=${sunrise.size} sunset=${sunset.size}"
        }

        val month = calendar.get(Calendar.MONTH).coerceIn(0, 11)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
        val nextMonth = (month + 1) % 12

        // 当月已过比例：1 日 -> 0，月末 -> 接近 1
        val fraction = ((dayOfMonth - 1).toFloat() / daysInMonth.toFloat()).coerceIn(0f, 1f)

        fun lerp(values: IntArray): Int {
            val from = values[month]
            val to = values[nextMonth]
            return (from + (to - from) * fraction).roundToInt()
        }

        return lerp(sunset) to lerp(sunrise)
    }

    /**
     * 得出最终采用的夜间区间：用户手动设置过则优先，否则用日出日落表。
     *
     * @param overrideStart 用户设置的夜间开始（分钟），`null` 表示未设置
     * @param overrideEnd 用户设置的夜间结束（分钟），`null` 表示未设置
     */
    fun nightRange(
        context: Context,
        calendar: Calendar,
        overrideStart: Int? = null,
        overrideEnd: Int? = null,
    ): Pair<Int, Int> {
        val (solarStart, solarEnd) = solarNightRange(context, calendar)
        val start = overrideStart ?: solarStart
        val end = overrideEnd ?: solarEnd
        return normalizeMinute(start) to normalizeMinute(end)
    }

    /**
     * 当前是否处于夜间（供 [NightMode.TIME_AUTO] 使用）。
     *
     * 调用方传入 [Calendar] 以便单测注入固定时间。
     */
    fun isNightNow(
        context: Context,
        calendar: Calendar,
        overrideStart: Int? = null,
        overrideEnd: Int? = null,
    ): Boolean {
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val (start, end) = nightRange(context, calendar, overrideStart, overrideEnd)
        return isNight(minuteOfDay, start, end)
    }

    /**
     * 取全局主题样式（浅/深由资源限定符自动切换）。
     */
    @StyleRes
    fun accentStyleRes(accent: ThemeAccent): Int = accent.styleRes

    /**
     * 取样式：`forPlayer = true` 时返回播放页分控用的 ThemeOverlay。
     *
     * @param forPlayer 是否用于播放页（`04 §2.6` 的分控主题）
     */
    @StyleRes
    fun accentStyleRes(accent: ThemeAccent, forPlayer: Boolean): Int =
        if (forPlayer) accent.playerStyleRes else accent.styleRes

    /**
     * 解析播放页「实际生效」的 accent（`04 §2.6` 三条降级规则）。
     *
     * ① 分控开关关闭 → 用全局 accent；
     * ② 开关开启但 `playerAccentId` 为空或非法 → 回落全局 accent（不因一个坏值整页失色）；
     * ③ 调用方若要「静默关闭开关」，可通过返回值是否等于 [global] 判断。
     *
     * @return 实际应使用的 accent，以及该开关是否仍然有效
     */
    fun resolvePlayerAccent(
        global: ThemeAccent,
        playerAccentId: String?,
        splitEnabled: Boolean,
    ): PlayerAccentResolution {
        if (!splitEnabled) {
            return PlayerAccentResolution(accent = global, splitEffective = false)
        }
        val player = ThemeAccent.entries.firstOrNull { it.id == playerAccentId }
            ?: return PlayerAccentResolution(accent = global, splitEffective = false)
        return PlayerAccentResolution(accent = player, splitEffective = true)
    }

    /**
     * 播放页分控解析结果。
     *
     * @property accent 实际使用的 accent
     * @property splitEffective 分控开关是否真正生效；`false` 时调用方应把持久化的开关静默置 false
     */
    data class PlayerAccentResolution(
        val accent: ThemeAccent,
        val splitEffective: Boolean,
    )
}
