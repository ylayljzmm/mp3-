package com.suisheng.player.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File

object ScanPathHelper {
    fun displayName(context: Context, entry: String): String {
        return if (entry.startsWith("content://")) {
            DocumentFile.fromTreeUri(context, Uri.parse(entry))?.name ?: "已选文件夹"
        } else {
            val file = File(entry)
            when {
                !file.name.isNullOrBlank() -> file.name
                entry.contains("Music", ignoreCase = true) -> "Music"
                else -> entry
            }
        }
    }

    fun presetPaths(): List<Pair<String, String>> {
        val presets = mutableListOf<Pair<String, String>>()
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)?.absolutePath?.let {
            presets.add("内置 Music" to it)
        }
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath?.let {
            presets.add("下载目录" to it)
        }
        presets.add("SD 卡 Music" to "/sdcard/Music")
        presets.add("存储 Music" to "/storage/emulated/0/Music")
        return presets.distinctBy { it.second }
    }
}
