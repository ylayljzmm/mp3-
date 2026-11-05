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
