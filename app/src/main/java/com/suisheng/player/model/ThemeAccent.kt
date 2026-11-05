package com.suisheng.player.model

import androidx.annotation.StyleRes
import com.suisheng.player.R

/**
 * 随声的 10 套主题色（accent）。
 *
 * 每套 accent 在 `values/` 与 `values-night/` 中各有一份完整的 12+4 个 token，
 * 同名同序，由资源限定符自动切换，因此 [styleRes] 只需一个——
 * 浅色/深色在运行期由系统按 night 限定符解析，不需要在代码里二选一。
 *
 * 色值定义见 `design/04-方案C §2.3`，资源名统一为 `tu_<id>_<token>`。
 *
 * @property id 持久化到 SharedPreferences 的稳定标识，**不可重命名**
 * @property displayName 设置页显示的中文名
 * @property styleRes 完整主题 `Theme.Suisheng.<Id>`
 * @property playerStyleRes 播放页分控用的 `ThemeOverlay.Suisheng.Player.<Id>`
 */
enum class ThemeAccent(
    val id: String,
    val displayName: String,
    @StyleRes val styleRes: Int,
    @StyleRes val playerStyleRes: Int,
) {
    AMBER(
        id = "amber",
        displayName = "暖琥珀",
        styleRes = R.style.Theme_Suisheng_Amber,
        playerStyleRes = R.style.ThemeOverlay_Suisheng_Player_Amber,
    ),
    CELADON(
        id = "celadon",
        displayName = "青瓷",
        styleRes = R.style.Theme_Suisheng_Celadon,
        playerStyleRes = R.style.ThemeOverlay_Suisheng_Player_Celadon,
    ),
    OCEAN(
        id = "ocean",
        displayName = "深海蓝",
        styleRes = R.style.Theme_Suisheng_Ocean,
        playerStyleRes = R.style.ThemeOverlay_Suisheng_Player_Ocean,
    ),
    VERMILION(
        id = "vermilion",
        displayName = "朱砂",
        styleRes = R.style.Theme_Suisheng_Vermilion,
        playerStyleRes = R.style.ThemeOverlay_Suisheng_Player_Vermilion,
    ),
    MOSS(
        id = "moss",
        displayName = "苔绿",
        styleRes = R.style.Theme_Suisheng_Moss,
        playerStyleRes = R.style.ThemeOverlay_Suisheng_Player_Moss,
    ),
    VIOLET(
        id = "violet",
        displayName = "雾紫",
        styleRes = R.style.Theme_Suisheng_Violet,
        playerStyleRes = R.style.ThemeOverlay_Suisheng_Player_Violet,
    ),
    CAMELLIA(
        id = "camellia",
        displayName = "山茶粉",
        styleRes = R.style.Theme_Suisheng_Camellia,
        playerStyleRes = R.style.ThemeOverlay_Suisheng_Player_Camellia,
    ),
    BRONZE(
        id = "bronze",
        displayName = "青铜",
        styleRes = R.style.Theme_Suisheng_Bronze,
        playerStyleRes = R.style.ThemeOverlay_Suisheng_Player_Bronze,
    ),
    AURORA(
        id = "aurora",
        displayName = "极光青",
        styleRes = R.style.Theme_Suisheng_Aurora,
        playerStyleRes = R.style.ThemeOverlay_Suisheng_Player_Aurora,
    ),
    GRAPHITE(
        id = "graphite",
        displayName = "石墨灰",
        styleRes = R.style.Theme_Suisheng_Graphite,
        playerStyleRes = R.style.ThemeOverlay_Suisheng_Player_Graphite,
    ),
    ;

    companion object {
        /** 出厂默认：暖琥珀。 */
        val DEFAULT: ThemeAccent = AMBER

        /**
         * 按持久化的 id 解析，非法值一律回落 [DEFAULT]。
         *
         * 用于「从备份文件导入」等外部来源，非法 id 不应让整页失色。
         */
        fun fromId(id: String?): ThemeAccent =
            entries.firstOrNull { it.id == id } ?: DEFAULT

        /** 是否为已知的 accent id，供导入校验使用（不回落）。 */
        fun isKnownId(id: String?): Boolean =
            id != null && entries.any { it.id == id }
    }
}
