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

    fun scanMusic(onDone: ((Int) -> Unit)? = null)
    fun allSongs(): List<Song>
    fun notifyLyricStyleChanged()
    fun refreshAppTheme()
}
