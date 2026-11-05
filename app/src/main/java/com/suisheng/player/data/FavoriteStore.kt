package com.suisheng.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地收藏（播放页「收藏」、更多面板「收藏 / 取消收藏」）。
 *
 * 身份一律用 [com.suisheng.player.model.Song.path]：本地曲目是文件绝对路径、SAF 是
 * `content://`、在线曲目是自带前缀的伪路径，三者都不会互相撞上；而 `Song.id` 在
 * MediaStore 自增 id 与在线曲目序号之间混用，做不了稳定主键。
 *
 * 落盘格式（`filesDir/favorites.json`，与 [OnlineLibraryStore] 同风格的手写 org.json）：
 * ```json
 * [ { "path": "/storage/emulated/0/Music/晴天.mp3", "addedAt": 1712222222222 } ]
 * ```
 * 读取时按 `addedAt` 降序（最新收藏在最前），文件损坏 / 为空一律当空集合，不抛。
 *
 * 线程安全：内存里只保留一份 [entries]，所有对外方法都加 `@Synchronized`，
 * 写盘用 `runCatching` 兜住 IO 异常——收藏失败不该把播放页带崩。
 */
class FavoriteStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, STORE_FILE)

    /** 内存副本，恒按 `addedAt` 降序；只在 `this` 锁内读写。 */
    private val entries = mutableListOf<Entry>()

    init {
        // 只读一次；之后所有变更都走内存 + 立即落盘，不再回读文件。
        load()
    }

    /** 是否已收藏。空路径永远不算收藏。 */
    @Synchronized
    fun isFavorite(path: String): Boolean {
        if (path.isBlank()) return false
        return entries.any { it.path == path }
    }

    /**
     * 切换收藏状态。
     *
     * @return 切换**之后**的状态：`true` = 已收藏。
     */
    @Synchronized
    fun toggle(path: String): Boolean {
        if (path.isBlank()) return false
        val index = entries.indexOfFirst { it.path == path }
        val favorite = index < 0
        if (favorite) {
            // 新收藏插到最前，与「按 addedAt 降序」保持一致
            entries.add(0, Entry(path, System.currentTimeMillis()))
        } else {
            entries.removeAt(index)
        }
        persist()
        return favorite
    }

    /** 显式设置收藏状态；状态没有变化时不写盘。 */
    @Synchronized
    fun setFavorite(path: String, favorite: Boolean) {
        if (path.isBlank()) return
        val index = entries.indexOfFirst { it.path == path }
        when {
            favorite && index < 0 -> entries.add(0, Entry(path, System.currentTimeMillis()))
            !favorite && index >= 0 -> entries.removeAt(index)
            else -> return
        }
        persist()
    }

    /** 收藏路径快照（降序）。返回的是新集合，调用方随便改。 */
    @Synchronized
    fun paths(): Set<String> {
        val snapshot = LinkedHashSet<String>(entries.size)
        entries.forEach { snapshot.add(it.path) }
        return snapshot
    }

    /** 收藏数量。 */
    @Synchronized
    fun count(): Int = entries.size

    /** 取消收藏（等价于 `setFavorite(path, false)`，语义更直白）。 */
    @Synchronized
    fun remove(path: String) {
        if (path.isBlank()) return
        if (entries.removeAll { it.path == path }) {
            persist()
        }
    }

    // ---------------------------------------------------------------- 私有

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val array = JSONArray(file.readText())
            val parsed = mutableListOf<Entry>()
            val seen = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val path = item.optString("path")
                // 空路径与重复路径都丢掉，避免 UI 上出现「同一首收藏两次」
                if (path.isBlank() || !seen.add(path)) continue
                parsed.add(Entry(path, item.optLong("addedAt")))
            }
            // 文件里的顺序不可信（手工改过 / 老版本写过），读进来统一重排
            parsed.sortByDescending { it.addedAt }
            entries.clear()
            entries.addAll(parsed)
        }
    }

    /** 调用方必须已持有 `this` 锁。 */
    private fun persist() {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("path", entry.path)
                    put("addedAt", entry.addedAt)
                }
            )
        }
        runCatching { file.writeText(array.toString(2)) }
    }

    private data class Entry(val path: String, val addedAt: Long)

    private companion object {
        const val STORE_FILE = "favorites.json"
    }
}
