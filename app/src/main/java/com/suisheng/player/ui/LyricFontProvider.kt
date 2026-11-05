package com.suisheng.player.ui

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import com.suisheng.player.R
import com.suisheng.player.model.LyricFontFamily
import java.util.concurrent.ConcurrentHashMap

object LyricFontProvider {
    private val cache = ConcurrentHashMap<String, Typeface?>()

    fun getTypeface(context: Context, family: LyricFontFamily, bold: Boolean): Typeface? {
        val key = "${family.id}_$bold"
        cache[key]?.let { return it }
        val base = if (family.fontRes == 0) {
            systemFallback(family)
        } else {
            loadFont(context, family.fontRes) ?: systemFallback(family)
        }
        val typeface = if (bold) Typeface.create(base, Typeface.BOLD) else base
        cache[key] = typeface
        return typeface
    }

    fun preload(context: Context, family: LyricFontFamily, onLoaded: (() -> Unit)? = null) {
        if (family.fontRes == 0) {
            onLoaded?.invoke()
            return
        }
        val appContext = context.applicationContext
        ResourcesCompat.getFont(
            appContext,
            family.fontRes,
            object : ResourcesCompat.FontCallback() {
                override fun onFontRetrieved(typeface: Typeface) {
                    cache.keys.removeIf { it.startsWith("${family.id}_") }
                    cache["${family.id}_false"] = typeface
                    cache["${family.id}_true"] = Typeface.create(typeface, Typeface.BOLD)
                    onLoaded?.invoke()
                }

                override fun onFontRetrievalFailed(reason: Int) {
                    onLoaded?.invoke()
                }
            },
            null
        )
    }

    fun invalidate() {
        cache.clear()
    }

    private fun loadFont(context: Context, @FontRes fontRes: Int): Typeface? {
        return runCatching { ResourcesCompat.getFont(context, fontRes) }.getOrNull()
    }

    private fun systemFallback(family: LyricFontFamily): Typeface {
        val name = when (family) {
            LyricFontFamily.DEFAULT -> "sans-serif"
            LyricFontFamily.NOTO_SANS -> "sans-serif-light"
            LyricFontFamily.QINGKE -> "sans-serif-black"
            LyricFontFamily.MASHAN -> "serif"
            LyricFontFamily.SERIF -> "serif"
            LyricFontFamily.MONO -> "monospace"
            LyricFontFamily.CONDENSED -> "sans-serif-condensed"
            LyricFontFamily.ROUNDED -> "sans-serif-medium"
        }
        return Typeface.create(name, Typeface.NORMAL)
    }
}
