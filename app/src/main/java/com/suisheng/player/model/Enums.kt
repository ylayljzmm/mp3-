package com.suisheng.player.model

enum class PlaybackMode {
    ORDER,
    SHUFFLE,
    /**
     * 单曲循环：播完自动重播当前曲；手动点下一曲仍强制切下一首。
     * 追加在末尾，旧存档里的 ordinal 0/1 含义不变。
     */
    REPEAT_ONE
}

// 明暗档位已迁到 com.suisheng.player.theme.NightMode（4 档，含按时间自动），
// 旧的 3 档 ThemeMode 在 2b-2 删除，避免两个枚举长期并存、谁都说不清该读哪个。

enum class LibraryKind {
    MUSIC,
    ONLINE,
    RADIO,
    PODCAST
}

enum class PlaylistViewMode {
    ALL_SONGS,
    BY_ARTIST
}

enum class PlaylistSortOrder {
    TITLE,
    DURATION_ASC,
    DURATION_DESC,
    // 方案 I 排序面板 8 项：追加在末尾，旧值含义不变。
    TITLE_DESC,
    ARTIST,
    ALBUM,
    RECENT,
    PATH
}

enum class OnlineApiType {
    GD_STUDIO,
    NETEASE_ENHANCED
}
