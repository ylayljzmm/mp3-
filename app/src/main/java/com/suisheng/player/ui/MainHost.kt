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

    /**
     * 打开歌词设置子页（设置页「歌词设置」那一行走这里）。
     *
     * 与 [openTheme] 同构：也走目的地栈，所以「设置 → 歌词设置 → 返回」
     * 能回到设置页，而不是一律回播放页。
     */
    fun openLyricSettings()

    /** 返回上一级：二级页回播放页；主题页回它进来的那一页。 */
    fun navigateUp()

    fun scanMusic(onDone: ((Int) -> Unit)? = null)
    fun allSongs(): List<Song>

    /**
     * 删除一首本地歌曲的文件。
     *
     * @param deleteLyric 是否连同歌词文件（含用户手放的 `.lrc`）一起删
     * @param onResult 结果回调，**主线程**，true 表示音频文件确实已删除
     */
    fun deleteLocalFiles(song: Song, deleteLyric: Boolean, onResult: (Boolean) -> Unit)

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
