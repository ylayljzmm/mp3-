package com.suisheng.player.model

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val path: String,
    val lrcPath: String?,
    val durationMs: Long = 0L
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
