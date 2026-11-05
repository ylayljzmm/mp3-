package com.suisheng.player.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.suisheng.player.model.LibraryGrouper
import java.io.File

/**
 * 「隐藏文件夹」的独立持久化。
 *
 * 刻意不复用 [SettingsRepository]：这一份数据只有「路径集合」一个语义，
 * 单独一个 prefs 文件（[PREFS_NAME]）可以整体清空、可以单独迁移，也不会让设置页那份
 * 越来越长的 key 列表继续膨胀。
 *
 * ## 为什么用路径、而不是 id 或别的标识
 *
 * ① `Song.id` 不稳定：本地曲目有的来自 MediaStore（列 `_ID`，换机/重扫会变），
 *    有的来自自增序号，两种混在同一个 `Long` 里，用它当身份迟早会隐藏错歌；
 * ② 文件夹本身在 Android 上没有稳定 id：`DocumentFile` 的 tree uri 只覆盖 SAF 引入的目录，
 *    `File` 路径覆盖其余全部本地目录，而**本地文件夹分类天然就是路径分类**
 *    （见 `LibraryGrouper.folderChildren`：`LibraryGroup.key` 就是目录绝对路径）；
 * ③ 用户移动/改名一个目录时，路径会变 —— 这是可接受的：改名后目录重新出现，
 *    比「用不稳定的 id 隐藏了一堆不相干的歌」要好得多。
 *
 * ## 存储格式
 *
 * 沿用 `SettingsRepository.scanPathsText` 的口径：一个 key（[KEY_HIDDEN_PATHS]）
 * 里用换行拼接多个绝对路径。集合很小（个位数到几十条），不值得动用数据库。
 *
 * @param context 任意 Context，内部只取 `applicationContext`，不会泄漏 Activity。
 */
class HiddenFolderStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 已隐藏的目录（绝对路径），**已去冗余**。
     *
     * 冗余的定义：某条路径本身位于另一条隐藏路径之下（或与它相同）。例如已隐藏
     * `/sdcard/Music`，那么 `/sdcard/Music/A` 不会被单独返回 —— 隐藏父目录时
     * 子目录的过滤器已经被父目录覆盖，留着它只会让设置页出现两行「看起来都能恢复」
     * 的条目。
     */
    fun paths(): List<String> = normalize(rawPaths())

    /** 与 [paths] 一一对应的 [File] 视图，供 `LibraryGrouper.visibleSongs` 使用。 */
    fun hiddenDirs(): List<File> = paths().map(::File)

    /**
     * [path] 自身是否落在任一隐藏目录之内（含「就是该目录」）。
     *
     * 注意：`content://` 这类非文件路径仍按字符串无关的语义处理 —— 调用方若要判定
     * 「这首歌能不能看到」，应该用 `LibraryGrouper.isHidden(song, hiddenDirs)`，
     * 那里对带 `://` 的路径统一放行。
     */
    fun isHidden(path: String): Boolean {
        if (path.isBlank()) return false
        val target = File(path)
        return hiddenDirs().any { LibraryGrouper.isAncestorOrSelf(it, target) }
    }

    /**
     * 隐藏 [dirPath]。
     *
     * 写入前先剔除「位于该目录之下」的既有条目：先隐藏 `/a/b`、再隐藏 `/a` 时，
     * 落盘结果只应有 `/a`，否则恢复 `/a` 之后 `/a/b` 还留着，目录会诡异地继续消失。
     */
    fun hide(dirPath: String) {
        if (dirPath.isBlank()) return
        val dir = File(dirPath).absolutePath
        val kept = rawPaths().filterNot { LibraryGrouper.isAncestorOrSelf(File(dir), File(it)) }
        write(kept + dir)
    }

    /**
     * 恢复 [dirPath]：只移除**精确匹配**的那一条。
     *
     * 不做「移除其下所有条目」：如果 `/a` 与 `/a/b` 都被登记（历史上分开隐藏的），
     * 恢复 `/a` 不应该顺手把用户单独隐藏的 `/a/b` 也吐出来。
     */
    fun restore(dirPath: String) {
        if (dirPath.isBlank()) return
        val target = File(dirPath).absolutePath
        write(rawPaths().filterNot { it == target })
    }

    /** 清空全部隐藏项（设置页「恢复」到干净状态时的兜底入口）。 */
    fun restoreAll() {
        write(emptyList())
    }

    /** 冗余去除之后的条目数，就是设置页要显示的「N 个」。 */
    fun count(): Int = paths().size

    // ---------------------------------------------------------------- 内部

    /** 原始登记表：去空白、去重、统一成绝对路径，但**不做**父子压缩。 */
    private fun rawPaths(): List<String> = prefText()
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { File(it).absolutePath }
        .distinct()

    private fun prefText(): String = prefs.getString(KEY_HIDDEN_PATHS, "") ?: ""

    private fun write(entries: List<String>) {
        // 落盘前就压缩：读路径上也就不必反复算父子关系了
        val normalized = normalize(entries)
        prefs.edit { putString(KEY_HIDDEN_PATHS, normalized.joinToString(SEPARATOR)) }
    }

    /** 去掉被另一条隐藏路径覆盖（祖先或自身）的条目，保持原有顺序。 */
    private fun normalize(entries: List<String>): List<String> {
        if (entries.size <= 1) return entries
        val dirs = entries.map(::File)
        return entries.filterIndexed { index, _ ->
            val candidate = dirs[index]
            dirs.none { other ->
                // 「另一条」必须真的是另一条：否则每条都会把自己的祖先身份误判成被覆盖
                other.absolutePath != candidate.absolutePath &&
                    LibraryGrouper.isAncestorOrSelf(other, candidate)
            }
        }
    }

    companion object {
        /** 独立的 prefs 文件名。 */
        const val PREFS_NAME = "hidden_folders"

        /** 换行拼接的隐藏路径列表。 */
        const val KEY_HIDDEN_PATHS = "hidden_paths"

        /** 与 `SettingsRepository` 的列表口径一致：换行分隔。 */
        private const val SEPARATOR = "\n"
    }
}
