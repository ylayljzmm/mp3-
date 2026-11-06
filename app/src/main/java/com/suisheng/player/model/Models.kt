package com.suisheng.player.model

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val path: String,
    val lrcPath: String?,
    val durationMs: Long = 0L,
    /**
     * 专辑名。本地曲库来自 MediaStore 的 `ALBUM` 列或 `METADATA_KEY_ALBUM`；
     * 取不到就是空串——专辑墙会把空串统一归到「未知专辑」，而不是丢歌。
     * 在线曲目由 `OnlineMusicApi` 填。
     */
    val album: String = "",
    /**
     * 专辑发布年份。本地曲库来自 MediaStore 的 `YEAR` 列；目录扫描 / SAF / 在线曲目
     * 取不到就是 0 —— 专辑卡「获取不到的不显示」，不拿总时长或占位符冒充。
     * 追加在末尾并给默认值，所有既有命名参数 / 位置参数构造与 Parcelable 转换都不受影响。
     */
    val year: Int = 0,
)

data class ArtistGroup(
    val name: String,
    val songCount: Int
)

data class LrcChar(
    val char: String,
    val startTimeMs: Long
)

data class LrcLine(
    val chars: List<LrcChar>,
    val startTimeMs: Long,
    val endTimeMs: Long
) {
    val text: String get() = chars.joinToString("") { it.char }
}

data class LyricState(
    val currentLine: LrcLine?,
    val nextLine: LrcLine?,
    val positionMs: Long
)
