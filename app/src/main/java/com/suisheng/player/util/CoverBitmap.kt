package com.suisheng.player.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 封面位图解码。
 *
 * 抽出来的理由：迷你条（48dp）与专辑墙格子（约 180dp）都需要从**磁盘文件**解码，
 * 而且都必须先按目标尺寸降采样。嵌入式封面动辄 3000×3000（ARGB_8888 下 36MB），
 * 直接 `BitmapFactory.decodeFile` 在低内存车机上就是一次 OOM 自杀。
 */
object CoverBitmap {

    /**
     * 解码 [path] 指向的图片，最长边不超过 [maxPx]。
     *
     * @return 解码失败（文件不存在 / 不是图片 / 内存不足）返回 null，调用方保留占位图。
     */
    fun decode(path: String?, maxPx: Int = 512): Bitmap? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null
            var sample = 1
            while (longest / sample > maxPx) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}
