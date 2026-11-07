package com.suisheng.player.model

import androidx.annotation.FontRes
import com.suisheng.player.R

enum class LyricThemePreset(
    val id: String,
    val displayName: String,
    val highlightColor: Int,
    val pendingColor: Int,
    val nextLineColor: Int,
    val playerCurrentSizeSp: Float,
    val playerNextSizeSp: Float,
    val overlaySizeSp: Float
) {
    CLASSIC(
        "classic", "经典白",
        0xFFFFFFFF.toInt(), 0x99FFFFFF.toInt(), 0xCCFFFFFF.toInt(),
        22f, 18f, 26f
    ),
    NETEASE(
        // id 是落盘键（老用户的 prefs 里存着 "netease"），**绝对不能改**；只有展示名可改。
        "netease", "烈焰红",
        0xFFEC4141.toInt(), 0x99FFFFFF.toInt(), 0xCCFFFFFF.toInt(),
        22f, 18f, 26f
    ),
    PINK(
        "pink", "淡粉",
        0xFFE891A8.toInt(), 0x99FFFFFF.toInt(), 0xCCFFFFFF.toInt(),
        21f, 17f, 25f
    ),
    GREEN(
        "green", "淡绿",
        0xFF7CB89A.toInt(), 0x99FFFFFF.toInt(), 0xCCFFFFFF.toInt(),
        21f, 17f, 25f
    ),
    BLUE(
        "blue", "淡蓝",
        0xFF7BA3D0.toInt(), 0x99FFFFFF.toInt(), 0xCCFFFFFF.toInt(),
        21f, 17f, 25f
    ),
    MINT(
        "mint", "薄荷",
        0xFF6BBFAD.toInt(), 0x99FFFFFF.toInt(), 0xCCFFFFFF.toInt(),
        21f, 17f, 25f
    ),
    LAVENDER(
        "lavender", "薰衣草",
        0xFFA99BD4.toInt(), 0x99FFFFFF.toInt(), 0xCCFFFFFF.toInt(),
        21f, 17f, 25f
    );

    companion object {
        fun fromId(id: String): LyricThemePreset =
            entries.firstOrNull { it.id == id }
                ?: if (id in setOf("zelda", "mario")) NETEASE else NETEASE
    }
}

enum class LyricFontFamily(
    val id: String,
    val displayName: String,
    @FontRes val fontRes: Int = 0
) {
    DEFAULT("default", "系统默认"),
    NOTO_SANS("noto_sans", "思源黑体", R.font.lyric_noto_sans_sc),
    QINGKE("qingke", "站酷快乐", R.font.lyric_zcool_qingke),
    MASHAN("mashan", "马善政楷", R.font.lyric_ma_shan_zheng),
    SERIF("serif", "思源宋体", R.font.lyric_noto_serif_sc),
    MONO("mono", "等宽"),
    CONDENSED("condensed", "窄体"),
    ROUNDED("rounded", "圆体");

    companion object {
        fun fromId(id: String): LyricFontFamily = when (id) {
            "mono" -> MONO
            "condensed" -> CONDENSED
            "rounded" -> ROUNDED
            "noto_sans" -> NOTO_SANS
            "qingke" -> QINGKE
            "mashan" -> MASHAN
            "serif" -> SERIF
            "sans" -> NOTO_SANS
            "round" -> QINGKE
            else -> entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}
