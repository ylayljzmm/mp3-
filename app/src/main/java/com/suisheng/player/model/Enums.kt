package com.suisheng.player.model

enum class PlaybackMode {
    ORDER,
    SHUFFLE
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
    DURATION_DESC
}

enum class OnlineApiType {
    GD_STUDIO,
    NETEASE_ENHANCED
}
