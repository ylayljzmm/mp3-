package com.suisheng.player.model

/**
 * 歌曲信息页要展示的「技术参数」快照。
 *
 * 全部字段可空：`MediaMetadataRetriever` 在不同容器格式上给出的字段差异很大
 * （MP3 没有位深、WAV 没有码率、部分车机连 MIME 都不给），
 * **取不到就给 null**，界面上统一显示 `—`。绝不拿别的值冒充（比如用文件大小反推码率）。
 *
 * 刻意**没有** `channels`（声道数）：Android 没有可靠的公开 API 能拿到它，
 * `METADATA_KEY_NUM_TRACKS` 指的是音轨条数而不是声道数，拿它冒充会显示一个错的数字。
 * 宁可这一行不存在，也不要显示假数据。
 *
 * @property format 扩展名大写（"FLAC" / "MP3" / "M4A"…）；无扩展名给 null
 * @property mimeType `METADATA_KEY_MIMETYPE`
 * @property bitrateKbps `METADATA_KEY_BITRATE / 1000`
 * @property sampleRateHz `METADATA_KEY_SAMPLERATE`
 * @property bitsPerSample `METADATA_KEY_BITS_PER_SAMPLE`（API 31+ 才有这个 key）
 * @property fileSizeBytes 文件字节数（`File.length()`，0 与不存在都算 null）
 * @property trackNumber `METADATA_KEY_CD_TRACK_NUMBER`（原始字符串，可能是 "3/12"）
 * @property lrcLabel 已本地化的「歌词来源」文案（同名 LRC / 内嵌歌词 / 联网获取）
 */
data class SongInfoDetails(
    val format: String?,
    val mimeType: String?,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val bitsPerSample: Int?,
    val fileSizeBytes: Long?,
    val trackNumber: String?,
    val lrcLabel: String?,
)
