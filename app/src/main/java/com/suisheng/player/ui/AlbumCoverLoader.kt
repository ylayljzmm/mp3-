package com.suisheng.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.suisheng.player.R
import com.suisheng.player.data.CoverArtFetcher
import com.suisheng.player.model.LibraryGrouper
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder
import com.suisheng.player.util.CoverBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Collections

/**
 * 专辑墙的封面加载器。
 *
 * 只取**嵌入式封面**（`CoverArtFetcher.fetchEmbeddedOnly`），绝不打网络：
 * 专辑墙一屏几十张卡，联网搜图会把车机的流量和帧率一起打穿。
 * 取不到就保留首字母占位。
 *
 * 三道闸：
 * ① 内存 LRU（32 张），滚动回看不重复解码；
 * ② 磁盘缓存 `cache/album_covers/<md5>`，第二次启动不再开 MediaMetadataRetriever；
 * ③ 同一个专辑同时只允许一个加载任务，且回填前校验 `ImageView.tag` ——
 *    ViewHolder 被复用后旧结果直接丢弃，不会出现「封面串行」。
 */
class AlbumCoverLoader(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext: Context = context.applicationContext
    private val fetcher = CoverArtFetcher(appContext)
    private val cacheDir = File(appContext.cacheDir, "album_covers")
    private val memory = LruCache<String, Bitmap>(MEMORY_CACHE_COUNT)
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /**
     * 把 [key] 对应的封面放进 [cover]。
     *
     * @param letter 取不到封面时显示的首字符
     * @param source 该组的第一首歌；为 null 表示这组没有可解码的本地文件
     */
    fun into(
        cover: ImageView,
        letterView: TextView,
        key: String,
        letter: String,
        source: Song?,
    ) {
        cover.tag = key
        letterView.text = letter.take(1).uppercase()
        val cached = memory.get(key)
        if (cached != null) {
            cover.setImageBitmap(cached)
            letterView.visibility = View.GONE
            return
        }
        cover.setImageDrawable(null)
        letterView.visibility = View.VISIBLE
        if (source == null || !inFlight.add(key)) return
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { load(key, source) }
            inFlight.remove(key)
            if (bitmap != null) memory.put(key, bitmap)
            // ViewHolder 可能已经被复用成别的专辑，这时结果必须丢弃
            if (cover.tag != key) return@launch
            if (bitmap != null) {
                cover.setImageBitmap(bitmap)
                letterView.visibility = View.GONE
            }
        }
    }

    /**
     * 播放页重载了 [song] 的封面之后，让专辑墙**丢掉**这张卡上缓存的旧图。
     *
     * 这里的缓存键是 `group.key`（专辑名），既不随文件内容变化，也没有 mtime 之类
     * 的版本信息 —— 所以重载成功后如果不主动失效，回到专辑墙看到的仍然是旧图，
     * 用户会以为「重新加载封面没生效」。这正是 5.9.1 的病根之一，只是它藏在这一页。
     *
     * 只清这一个专辑的内存图和磁盘图，不动其余几十张：专辑墙一屏几十张卡，
     * 全清会让用户滚回来看见一片首字母占位，然后重新开几十次 MediaMetadataRetriever。
     * 清完后本页由宿主 `refresh()` 重新绑定时会重新加载（见 [GroupedLibraryFragment]）。
     */
    fun invalidateFor(song: Song) {
        val key = albumKeyOf(song)
        memory.remove(key)
        // inFlight 里还挂着的那个请求算的是旧文件的图，放开让它下次能重新发起
        inFlight.remove(key)
        runCatching { File(cacheDir, md5(key) + ".jpg").delete() }
    }

    /**
     * 这首歌在专辑墙上的缓存键：与 `LibraryGrouper.byAlbum` 完全同一套口径
     * （空专辑名归到 `LibraryGrouper.UNKNOWN_KEY`）。
     *
     * 公开出来是给页面用的：失效之后页面要靠同一个 key 找到那一格重新绑定，
     * 两处各算一次很容易漂移（比如一处 trim 了、一处没 trim）。
     */
    fun albumKeyOf(song: Song): String {
        val raw = song.album.trim()
        return if (raw.isEmpty()) LibraryGrouper.UNKNOWN_KEY else raw
    }

    private fun load(key: String, source: Song): Bitmap? {
        val file = File(cacheDir, md5(key) + ".jpg")
        if (!file.exists() || file.length() == 0L) {
            fetcher.fetchEmbeddedOnly(source, file)
        }
        if (!file.exists() || file.length() == 0L) return null
        return CoverBitmap.decode(file.absolutePath, ALBUM_COVER_PX)
    }

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** 一屏约 6×3 张，留 1.5 屏余量。 */
        const val MEMORY_CACHE_COUNT = 32

        /** 格子最大 180dp，×2 密度下 360px，取 384 留余量。 */
        const val ALBUM_COVER_PX = 384
    }
}
