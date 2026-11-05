package com.suisheng.player.model

enum class PlaybackMode {
    ORDER,
    SHUFFLE
}

enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}

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
