package com.suisheng.player.util

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.min

object AlbumColorExtractor {
    fun backgroundColor(bitmap: Bitmap): Int {
        val sample = Bitmap.createScaledBitmap(bitmap, 24, 24, false)
        var r = 0L
        var g = 0L
        var b = 0L
        val pixels = IntArray(24 * 24)
        sample.getPixels(pixels, 0, 24, 0, 0, 24, 24)
        pixels.forEach { color ->
            r += Color.red(color)
            g += Color.green(color)
            b += Color.blue(color)
        }
        val count = pixels.size
        val hsv = FloatArray(3)
        Color.colorToHSV(Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt()), hsv)
        hsv[1] = min(hsv[1], 0.55f)
        hsv[2] = min(hsv[2] * 0.42f, 0.32f)
        return Color.HSVToColor(hsv)
    }
}
