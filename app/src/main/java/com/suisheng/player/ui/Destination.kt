package com.suisheng.player.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.suisheng.player.R

/**
 * 抽屉的三个分组标题（`design/04 §3.5`）。
 *
 * 分组顺序即渲染顺序：媒体库 → 在线 → 系统。
 */
enum class DrawerGroup(@StringRes val titleRes: Int) {
    LIBRARY(R.string.drawer_group_library),
    ONLINE(R.string.drawer_group_online),
    SYSTEM(R.string.drawer_group_system),
}

/**
 * 抽屉里的 8 个目的地（外加 1 个不在抽屉里出现的播放页），
 * 枚举顺序就是 `design/04 §3.5` 清单里的 1–8 顺序，不要重排。
 *
 * 刻意用 enum 而不是 String / Int 序号：
 * ① 编译期可穷举，新增页面时 `when` 会提醒；
 * ② `onSaveInstanceState` 存的是 [name]，将来调整顺序不会串位。
 *
 * @property labelRes 条目文字
 * @property iconRes 条目图标（24dp 纯黑矢量，实际颜色由使用处 tint 决定）
 * @property group 所属分组
 * @property available 页面是否已落地；false = 条目置灰、点击提示「即将上线」（期 2c-2 补齐）
 * @property showsCount 是否在条目右侧显示计数（设计里只有歌曲/专辑/艺术家/文件夹 4 条有）
 * @property shownInDrawer 是否在抽屉里出现。只有 [PLAYER] 是 false ——
 *   「正在播放」不是一个「去哪」的目的地，而是播放动作的目的地，
 *   由歌曲点击 `selectDestination(Destination.PLAYER)` 触发，抽屉里不占一条。
 */
enum class Destination(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
    val group: DrawerGroup,
    val available: Boolean,
    val showsCount: Boolean,
    val shownInDrawer: Boolean = true,
) {
    HOME(
        labelRes = R.string.drawer_home,
        iconRes = R.drawable.ic_drawer_home,
        group = DrawerGroup.LIBRARY,
        available = true,
        showsCount = false,
    ),
    SONGS(
        labelRes = R.string.drawer_songs,
        iconRes = R.drawable.ic_drawer_song,
        group = DrawerGroup.LIBRARY,
        available = true,
        showsCount = true,
    ),
    ALBUMS(
        labelRes = R.string.drawer_albums,
        iconRes = R.drawable.ic_drawer_album,
        group = DrawerGroup.LIBRARY,
        available = false,
        showsCount = true,
    ),
    ARTISTS(
        labelRes = R.string.drawer_artists,
        iconRes = R.drawable.ic_drawer_artist,
        group = DrawerGroup.LIBRARY,
        available = false,
        showsCount = true,
    ),
    FOLDERS(
        labelRes = R.string.drawer_folders,
        iconRes = R.drawable.ic_drawer_folder,
        group = DrawerGroup.LIBRARY,
        available = false,
        showsCount = true,
    ),
    ONLINE(
        labelRes = R.string.drawer_online,
        iconRes = R.drawable.ic_drawer_online,
        group = DrawerGroup.ONLINE,
        available = true,
        showsCount = false,
    ),
    SETTINGS(
        labelRes = R.string.drawer_settings,
        iconRes = R.drawable.ic_drawer_settings,
        group = DrawerGroup.SYSTEM,
        available = true,
        showsCount = false,
    ),
    ABOUT(
        labelRes = R.string.drawer_about,
        iconRes = R.drawable.ic_drawer_about,
        group = DrawerGroup.SYSTEM,
        available = false,
        showsCount = false,
    ),

    /**
     * 播放页。故意放在 8 条之后并且 [shownInDrawer] = false：
     * 它不是抽屉条目，而是「点了歌之后去哪儿」的目的地。
     * 放在最后也保证前面 8 条的渲染顺序与 `design/04 §3.5` 的清单完全一致。
     */
    PLAYER(
        labelRes = R.string.playing_now,
        iconRes = R.drawable.ic_drawer_song,
        group = DrawerGroup.LIBRARY,
        available = true,
        showsCount = false,
        shownInDrawer = false,
    ),
    ;

    /** FragmentManager 用的 tag，同时是 `onSaveInstanceState` 的持久化键。 */
    val tag: String get() = "dest_$name"

    companion object {
        /** 状态恢复用：拿不到（旧版本存档 / 名字被改过）就返回 null，由调用方回落到首页。 */
        fun fromName(name: String?): Destination? =
            name?.let { value -> entries.firstOrNull { it.name == value } }
    }
}
