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
 * 抽屉里的 9 个目的地（外加 4 个不在抽屉里出现的：[PLAYER]、[THEME]、[ABOUT]、
 * [LYRIC_SETTINGS]）。
 *
 * **首页（HOME）已经不存在了。** 它原来是个壳：自己没有任何内容，只用一个 ViewPager2
 * 挂「本地 / 在线 / 电台 / 播客」4 个分区标签页，标题还要靠分区栏去标识。
 * 这 4 个分区被提升成抽屉条目之后，这个壳与 [SONGS] 在用户眼里就是同一件事，
 * 留着只会让抽屉里出现两条通往同一份曲库的入口。所以：
 *
 * ① [HOME] 从枚举里删除。老版本存档里存着 "HOME" 时由 `MainActivity` 回落到 [SONGS]，
 *    保证升级不会崩；
 * ② 原分区的 4 个分区各自成为一等目的地：[SONGS]（本地）、[ONLINE]、[RADIO]、[PODCAST]；
 * ③ 枚举顺序 = 抽屉渲染顺序，`design/04 §3.5` 的清单同步变成 10 条；
 * ④ 抽屉改版后新增的 [FAVORITES]（收藏）插在 [FOLDERS] 之后，属于「媒体库」组 ——
 *    它和「歌曲」是同一份曲库的两个切面（全部 / 收藏），放在一起才符合用户的心理模型。
 *
 * 刻意用 enum 而不是 String / Int 序号：
 * ① 编译期可穷举，新增页面时 `when` 会提醒；
 * ② `onSaveInstanceState` 存的是 [name]，将来调整顺序不会串位。
 *
 * @property labelRes 条目文字
 * @property iconRes 条目图标（24dp 纯黑矢量，实际颜色由使用处 tint 决定）
 * @property group 所属分组
 * @property available 页面是否已落地；false = 条目置灰、点击提示「即将上线」
 * @property showsCount 是否在条目右侧显示计数（设计里只有歌曲/专辑/艺术家/文件夹 4 条有）
 * @property shownInDrawer 是否在抽屉里出现。[PLAYER] / [THEME] / [ABOUT] / [LYRIC_SETTINGS]
 *   为 false —— 「正在播放」不是一个「去哪」的目的地，而是播放动作的目的地；
 *   主题页由「设置 → 用户界面」直达，关于页内容已并入设置页末组「关于」，
 *   歌词设置是设置页的下一级页面。
 */
enum class Destination(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
    val group: DrawerGroup,
    val available: Boolean,
    val showsCount: Boolean,
    val shownInDrawer: Boolean = true,
) {
    // ---------------------------------------------------------------- 媒体库
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
        available = true,
        showsCount = true,
    ),
    ARTISTS(
        labelRes = R.string.drawer_artists,
        iconRes = R.drawable.ic_drawer_artist,
        group = DrawerGroup.LIBRARY,
        available = true,
        showsCount = true,
    ),
    FOLDERS(
        labelRes = R.string.drawer_folders,
        iconRes = R.drawable.ic_drawer_folder,
        group = DrawerGroup.LIBRARY,
        available = true,
        showsCount = true,
    ),

    /**
     * 收藏。
     *
     * 播放页那颗心形按钮原来只解决了「收」，没有地方「看」——这一页就是那份清单。
     * 图标直接用 [R.drawable.ic_favorite]（播放页已收藏态的实心爱心，24dp），
     * 抽屉里由 `DrawerPanelController` 统一 tint，不需要另做一套抽屉专用图标。
     *
     * [showsCount] = false：抽屉条目的计数目前只由 `DrawerPanelController.updateLibrary`
     * 按曲库四项（歌曲 / 专辑 / 艺术家 / 文件夹）填，收藏数不在那条链路上；
     * 打开计数只会露出一个永远空着的格子。收藏数改由本页的页头副标题给出。
     */
    FAVORITES(
        labelRes = R.string.drawer_favorites,
        iconRes = R.drawable.ic_favorite,
        group = DrawerGroup.LIBRARY,
        available = true,
        showsCount = false,
    ),

    // ---------------------------------------------------------------- 在线
    ONLINE(
        labelRes = R.string.drawer_online,
        iconRes = R.drawable.ic_drawer_online,
        group = DrawerGroup.ONLINE,
        available = true,
        showsCount = false,
    ),

    /**
     * 电台。原来是「首页」里的第 3 个分区标签，提升为独立条目后有自己的抽屉入口
     * 与自己的返回栈位置：用户可以直接从「电台」退回进来之前的「专辑」。
     */
    RADIO(
        labelRes = R.string.drawer_radio,
        iconRes = R.drawable.ic_drawer_radio,
        group = DrawerGroup.ONLINE,
        available = true,
        showsCount = false,
    ),

    /** 播客。与原「首页」第 4 个分区同源，同样提升为独立条目。 */
    PODCAST(
        labelRes = R.string.drawer_podcast,
        iconRes = R.drawable.ic_drawer_podcast,
        group = DrawerGroup.ONLINE,
        available = true,
        showsCount = false,
    ),

    // ---------------------------------------------------------------- 系统
    SETTINGS(
        labelRes = R.string.drawer_settings,
        iconRes = R.drawable.ic_drawer_settings,
        group = DrawerGroup.SYSTEM,
        available = true,
        showsCount = false,
    ),
    /**
     * 关于。方案 F/G 裁定：独立抽屉入口删除，版本 / 检查更新 / 崩溃日志合并进
     * 设置页末组「关于」。这里保留枚举值以免破坏 `when` 穷举与旧存档状态恢复，
     * 只是不再出现在抽屉里，因此抽屉恰好 9 条。
     */
    ABOUT(
        labelRes = R.string.drawer_about,
        iconRes = R.drawable.ic_drawer_about,
        group = DrawerGroup.SYSTEM,
        available = true,
        showsCount = false,
        shownInDrawer = false,
    ),

    /**
     * 播放页。故意放在 9 条抽屉条目之后并且 [shownInDrawer] = false：
     * 它不是抽屉条目，而是「点了歌之后去哪儿」的目的地。
     * 放在最后也保证前面 9 条的渲染顺序与 `design/04 §3.5` 的清单完全一致。
     */
    PLAYER(
        labelRes = R.string.playing_now,
        iconRes = R.drawable.ic_drawer_song,
        group = DrawerGroup.LIBRARY,
        available = true,
        showsCount = false,
        shownInDrawer = false,
    ),

    /**
     * 主题设置页。同样不在抽屉里——它由抽屉**底部第三格**的「主题」药丸直达，
     * 也可以从「设置 → 外观」进来；两处都走 `MainHost.openTheme()`。
     * 它是普通 Fragment，不再是全屏 Dialog。
     */
    THEME(
        labelRes = R.string.theme_title,
        iconRes = R.drawable.ic_drawer_theme,
        group = DrawerGroup.SYSTEM,
        available = true,
        showsCount = false,
        shownInDrawer = false,
    ),

    /**
     * 歌词设置。**设置页的下一级页面**，不在抽屉里出现。
     *
     * 原来是设置页里一块「原地展开」的折叠区（20+ 个控件堆在同一张卡片里），
     * 展开后既超出屏幕又在 `onViewCreated` 里一次性给 8 个 slider 赋存量值 ——
     * 越界值直接抛 `IllegalStateException`，用户看到的是「点歌词设置闪退」。
     * 拆成独立页面后：控件有一整屏可排、赋值全部走 `SliderBinding` 的 clamp，
     * 而且返回键能正常退回设置页（走 [destStack]，与主题页同一条链路）。
     *
     * `shownInDrawer = false` 的另一个原因：它是 SETTINGS 的子页，
     * 让它同时出现在抽屉里会和「设置」并列，反而分不清主次。
     */
    LYRIC_SETTINGS(
        labelRes = R.string.settings_lyrics,
        iconRes = R.drawable.ic_lyrics,
        group = DrawerGroup.SYSTEM,
        available = true,
        showsCount = false,
        shownInDrawer = false,
    ),
    ;

    /** FragmentManager 用的 tag，同时是 `onSaveInstanceState` 的持久化键。 */
    val tag: String get() = "dest_$name"

    companion object {
        /**
         * 状态恢复用：拿不到（旧版本存档里的 "HOME" / 名字被改过）就返回 null。
         *
         * 调用方**必须**自己兜底：`Destination.fromName(...)` 的结果可能为 null，
         * 直接赋给非空类型的字段会在升级首启时 NPE。
         */
        fun fromName(name: String?): Destination? =
            name?.let { value -> entries.firstOrNull { it.name == value } }
    }
}
