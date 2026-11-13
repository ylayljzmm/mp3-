package com.suisheng.player.data

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import com.suisheng.player.model.Song
import com.suisheng.player.util.MediaPath
import java.io.File

sealed interface MediaDeleteResult {
    /** 已经删掉了。 */
    object Deleted : MediaDeleteResult

    /** 需要用户在系统弹窗里确认，调用方必须 activity.startIntentSenderForResult。 */
    data class NeedsConsent(val intentSender: IntentSender) : MediaDeleteResult

    /** 删不掉，reason 仅用于日志。 */
    data class Failed(val reason: String) : MediaDeleteResult
}

/**
 * 删除本地音频文件本体。
 *
 * ## 为什么 API 30+ 必须走系统删除请求
 *
 * Android 11 起「读写别人的媒体文件」不再靠存储权限，而是靠一次性的**用户授权删除**：
 * 应用即使拿到 `READ_EXTERNAL_STORAGE` 也无权 `resolver.delete()` 别的应用写进媒体库的文件，
 * 强行删会抛 `SecurityException`。唯一被系统认可的做法是 `MediaStore.createDeleteRequest()`，
 * 它返回一个 `PendingIntent`，必须由 Activity 起一个系统确认弹窗、用户点「允许」之后
 * 系统自己去删。所以这一档**不可能**同步删完，只能把 `IntentSender` 交回上层等待结果。
 * 也正因为如此，本 App 在 API 33+ 干脆不申请任何写权限（见 Manifest 里
 * `WRITE_EXTERNAL_STORAGE` 的 `maxSdkVersion="32"`），删除全靠这个弹窗。
 *
 * ## 为什么不能拿 song.id 直接拼 uri 就删
 *
 * [Song.id] 有三种来源：MediaStore 扫描给的是 `_ID`；目录扫描（`MusicScanner.scanDirectories`）
 * 给的是从 1 开始的**合成序号**；SAF 目录树给的是从 10000000 起的序号。后两者的 id 与
 * MediaStore 的 `_ID` 完全无关，却几乎必然与某些真实行撞号 ——
 * `ContentUris.withAppendedId(EXTERNAL_CONTENT_URI, 3)` 会指向一首**别的**歌。
 * 删除不可恢复，所以用 id 之前必须回查一次 `DATA`/`DISPLAY_NAME` 证明它确实是这首歌。
 */
object LocalMediaDeleter {

    private val ID_PROJECTION = arrayOf(MediaStore.Audio.Media._ID)

    /** 删音频本体。在线曲目没有本地文件，直接判失败。 */
    fun deleteAudio(context: Context, song: Song): MediaDeleteResult {
        val path = song.path
        if (MediaPath.isStream(path)) return MediaDeleteResult.Failed("stream_song")
        if (path.startsWith("content://")) return deleteDocument(context, path)

        val uri = mediaStoreUri(context, song, path) ?: return deleteBareFile(path)
        return when {
            // 30+ 只能让系统删；这里返回的 IntentSender 必须交给 Activity 去起弹窗。
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requestSystemDelete(context, uri)
            // 29 上 Scoped Storage 刚落地，仍允许直接删「自己有权限」的行。
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> deleteThroughResolver(context, uri, path, touchFile = false)
            // 28 及以下：MediaStore 行删掉后文件有时还在，得再按路径补一刀。
            else -> deleteThroughResolver(context, uri, path, touchFile = true)
        }
    }

    // ---------------------------------------------------------------- SAF（content:// 目录树）

    private fun deleteDocument(context: Context, path: String): MediaDeleteResult {
        val document = DocumentFile.fromSingleUri(context, Uri.parse(path))
            ?: return MediaDeleteResult.Failed("saf_uri_invalid")
        val deleted: Boolean = runCatching { document.delete() }
            .getOrElse { return securityOrFailure(it) }
        return if (deleted) MediaDeleteResult.Deleted else MediaDeleteResult.Failed("saf_delete_failed")
    }

    // ---------------------------------------------------------------- MediaStore

    /**
     * 找这首歌在 MediaStore 里对应的行。
     *
     * 先按 `DATA` 精确匹配（唯一可靠的依据）；查不到时才退回用 [Song.id] 拼 uri 并**回查校验**。
     * 返回 null 表示「MediaStore 里没有这一行」，调用方应当走纯文件删除。
     */
    private fun mediaStoreUri(context: Context, song: Song, path: String): Uri? {
        queryUriByData(context, path)?.let { return it }
        if (song.id <= 0L) return null
        // 合成 id 会撞号：拼出来的 uri 只能当作「候选」，必须回查 DATA/DISPLAY_NAME 才算数。
        val candidate = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
        return candidate.takeIf { matchesRow(context, it, path) }
    }

    private fun queryUriByData(context: Context, path: String): Uri? = runCatching {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            ID_PROJECTION,
            "${MediaStore.Audio.Media.DATA} = ?",
            arrayOf(path),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            // 投影只有一列，列缺失的诡异 ROM 上 getColumnIndex 会返回 -1，兜到第 0 列
            val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID).coerceAtLeast(0)
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idCol))
        }
    }.getOrNull()

    /**
     * 校验候选 uri 指向的确实是这首歌。
     *
     * 判据只有两条：`DATA` 等于 path；或者 `DATA` 根本读不到（部分 ROM 对第三方隐藏该列）
     * 但 `DISPLAY_NAME` 等于文件名。都不满足就当没找到 —— 宁可删不掉，也不能删错。
     */
    private fun matchesRow(context: Context, candidate: Uri, path: String): Boolean = runCatching {
        context.contentResolver.query(
            candidate,
            arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val storedData = dataCol.takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getString(it) }
            val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val storedName = nameCol.takeIf { it >= 0 }?.let { cursor.getString(it) }
            storedData == path ||
                (storedData == null && storedName != null && storedName == File(path).name)
        } ?: false
    }.getOrDefault(false)

    /** API 30+：把删除交给系统确认弹窗。用户没点「允许」之前文件都还在。 */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestSystemDelete(context: Context, uri: Uri): MediaDeleteResult =
        runCatching<MediaDeleteResult> {
            val sender = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
            MediaDeleteResult.NeedsConsent(sender)
        }.getOrElse { securityOrFailure(it) }

    /**
     * 直接 `resolver.delete()`。
     *
     * [touchFile] 只在 API 28 及以下为 true：那一档的 MediaStore 只是个索引，
     * 少数 ROM 删掉行之后文件仍留在存储上，必须再按路径删一次。
     */
    private fun deleteThroughResolver(
        context: Context,
        uri: Uri,
        path: String,
        touchFile: Boolean,
    ): MediaDeleteResult {
        val rows: Int = runCatching { context.contentResolver.delete(uri, null, null) }
            .getOrElse { return securityOrFailure(it) }
        if (rows <= 0) {
            // 行已经不在了 / 系统拒绝了这次删除：老系统上还能靠路径兜底，新系统上直接认失败
            return if (touchFile) deleteBareFile(path) else MediaDeleteResult.Failed("mediastore_delete_rejected")
        }
        if (touchFile) File(path).delete()
        return MediaDeleteResult.Deleted
    }

    // ---------------------------------------------------------------- 纯文件

    private fun deleteBareFile(path: String): MediaDeleteResult {
        val file = File(path)
        return when {
            !file.exists() -> MediaDeleteResult.Failed("not_found")
            file.delete() -> MediaDeleteResult.Deleted
            else -> MediaDeleteResult.Failed("file_delete_failed")
        }
    }

    /**
     * 把删除时抛出的异常翻译成结果。
     *
     * `RecoverableSecurityException` 是 API 29 才有的类，所以 `as?` 必须包在版本判断里，
     * 否则低版本设备上跑到这一行会 NoClassDefFoundError —— 这类崩溃只在小内存/旧机型出现，
     * 一旦漏掉就是「点删除必崩」。
     */
    private fun securityOrFailure(error: Throwable): MediaDeleteResult {
        if (error !is SecurityException) {
            return MediaDeleteResult.Failed(error.javaClass.simpleName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val sender = (error as? RecoverableSecurityException)
                ?.userAction?.actionIntent?.intentSender
            if (sender != null) return MediaDeleteResult.NeedsConsent(sender)
        }
        return MediaDeleteResult.Failed("security_denied")
    }
}
