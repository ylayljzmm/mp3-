package com.suisheng.player.data

import android.content.Context
import androidx.core.content.edit
import com.suisheng.player.model.Song
import java.security.MessageDigest

/**
 * 每首歌独立的歌词偏好（偏移、注音语种、指定渠道）。
 *
 * ## 为什么不用 `path.hashCode()`
 *
 * `SettingsRepository` 里既有的 per-song 键是 `lyric_title_${path.hashCode()}`，
 * 那是搜索标题覆盖，值很小、串了也无所谓。但**时间轴偏移**串了是很恶心的故障：
 * 用户调好了 A 歌，B 歌莫名其妙也偏了，而且没有任何报错，只能靠耳朵发现。
 * `String.hashCode()` 只有 32 位，几千到几万首的曲库出现碰撞不是理论问题。
 *
 * 所以新键一律用 `MD5(path|title|artist)` 的前 16 个十六进制字符（64 位），
 * 与 [LyricFileStore] 的缓存键同源，排查时能一眼对上是哪首歌。
 *
 * ## 键失效
 *
 * 元数据（标题 / 歌手）被改过、或文件被移动，键就会变，表现为「偏移回到 +0.0s」。
 * 这里**刻意不做跨键回退查找** —— 回退只会把别的歌的偏移错误套上来，
 * 而 UI 常显当前值，用户一眼能发现并重新校准。
 */
class PerSongLyricPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前曲目的用户偏移（毫秒，正 = 歌词提前）。 */
    fun offsetMs(song: Song): Long =
        prefs.getLong(offsetKey(song), 0L).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)

    fun setOffsetMs(song: Song, ms: Long) {
        prefs.edit { putLong(offsetKey(song), ms.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)) }
    }

    /** 重置只清**用户**值；歌词文件自带的 `[offset:]` 不受影响（那是文件的一部分）。 */
    fun resetOffset(song: Song) {
        prefs.edit { remove(offsetKey(song)) }
    }

    /** 该曲目上次用的注音语种（用户手动指定过才有值）。 */
    fun phoneticLang(song: Song): String? =
        prefs.getString(phoneticKey(song), null)?.takeIf { it.isNotBlank() }

    fun setPhoneticLang(song: Song, lang: String?) {
        prefs.edit {
            if (lang.isNullOrBlank()) remove(phoneticKey(song)) else putString(phoneticKey(song), lang)
        }
    }

    /** 「换一个渠道试试」记住的选择：下次重搜优先走它。 */
    fun providerOverride(song: Song): String? =
        prefs.getString(providerKey(song), null)?.takeIf { it.isNotBlank() }

    fun setProviderOverride(song: Song, providerId: String?) {
        prefs.edit {
            if (providerId.isNullOrBlank()) {
                remove(providerKey(song))
            } else {
                putString(providerKey(song), providerId)
            }
        }
    }

    private fun offsetKey(song: Song) = "lyric_offset_${songKey(song.path, song.title, song.artist)}"
    private fun phoneticKey(song: Song) = "lyric_phonetic_lang_${songKey(song.path, song.title, song.artist)}"
    private fun providerKey(song: Song) = "lyric_provider_override_${songKey(song.path, song.title, song.artist)}"

    companion object {
        /** 用户偏移上限 ±60 秒（超出这个范围的错位已经不是「快慢」问题）。 */
        const val MAX_OFFSET_MS = 60_000L

        /** 常规调节范围 ±5 秒，UI 滑杆用；超出走直接输入。 */
        const val SLIDER_RANGE_MS = 5_000L

        /** 常规步进 0.1 秒。 */
        const val SLIDER_STEP_MS = 100L

        /**
         * `MD5("path|title|artist")` 的前 16 个十六进制字符。
         *
         * 纯函数、不碰 Android API，所以能直接在 JVM 单测里断言稳定性与不碰撞。
         */
        fun songKey(path: String, title: String, artist: String): String {
            val digest = MessageDigest.getInstance("MD5")
            val bytes = digest.digest("$path|$title|$artist".toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
