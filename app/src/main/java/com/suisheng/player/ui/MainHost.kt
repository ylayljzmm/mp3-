package com.suisheng.player.ui

import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.Song

interface MainHost {
    fun playSongAt(index: Int)
    fun playSongSubset(subset: List<Song>, index: Int, library: LibraryKind = LibraryKind.MUSIC)

    /** 打开左侧抽屉（播放页左上角的菜单键走这里）。 */
    fun openDrawer()

    /** 切到抽屉里的某个目的地；未落地的条目由实现方提示「即将上线」。 */
    fun selectDestination(dest: Destination)

    /** 打开主题设置页（抽屉底部第三格 / 设置页「外观」两条入口共用）。 */
    fun openTheme()

    /** 返回上一级：二级页回播放页；主题页回它进来的那一页。 */
    fun navigateUp()

    fun scanMusic(onDone: ((Int) -> Unit)? = null)
    fun allSongs(): List<Song>

    /**
     * 隐藏/恢复文件夹之后重算「可见曲库」并刷新受影响 UI（抽屉计数、首页列表、当前页）。
     *
     * 走宿主而不是让每个页面自己过滤：曲库是宿主持有的单一数据源，
     * 页面各自过滤会立刻退化成「这页过滤了、那页没过滤」的不一致状态。
     */
    fun refreshHiddenFilter()
    fun notifyLyricStyleChanged()
    fun refreshAppTheme()
}
