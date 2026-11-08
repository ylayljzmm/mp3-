package com.suisheng.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.model.LrcChar
import com.suisheng.player.model.LrcLine
import com.suisheng.player.model.LyricFontFamily
import kotlin.math.max
import kotlin.math.min

object LyricRenderer {

    const val PLACEHOLDER_LINE = "-----------"

    /** 悬浮歌词：待唱行用低饱和灰（带一点主题色相，避免全部变成深粉） */
    private fun overlayPendingColor(themeHighlight: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(themeHighlight, hsv)
        hsv[1] = (hsv[1] * 0.10f).coerceIn(0f, 0.14f)
        hsv[2] = 0.70f
        return Color.HSVToColor(0xD8, hsv)
    }

    /** 悬浮歌词：下一行略亮，仍保持灰调 */
    private fun overlayNextColor(themeHighlight: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(themeHighlight, hsv)
        hsv[1] = (hsv[1] * 0.14f).coerceIn(0f, 0.18f)
        hsv[2] = 0.80f
        return Color.HSVToColor(0xEE, hsv)
    }

    data class Style(
        val highlightColor: Int,
        val pendingColor: Int,
        val nextLineColor: Int,
        val overlayPlayedTopColor: Int,
        val overlayPlayedBottomColor: Int,
        val overlayPlayedStrokeColor: Int,
        val overlayPendingTopColor: Int,
        val overlayPendingBottomColor: Int,
        val overlayPendingStrokeColor: Int,
        val overlayNextTopColor: Int,
        val overlayNextBottomColor: Int,
        val overlayNextStrokeColor: Int,
        val currentSizePx: Float,
        val nextSizePx: Float,
        val otherSizePx: Float,
        val typeface: Typeface?,
        val maxVisualLines: Int,
        val currentScale: Float,
        val nextScale: Float,
        val bold: Boolean,
        val outline: Boolean,
        val strokeWidthPx: Float,
        /** 行距系数：单行占用的垂直空间为字号的多少倍 */
        val lineSpacing: Float,
        /**
         * 换行与绘制共用的水平内缩（**每侧**，px）。
         *
         * 以前这里是写死的 24f，与 `ScrollLyricView` 的换行口径（`viewWidth − padding`）
         * 各算各的，长行会出现「测量两行、画一行」的错位。现在由调用方从
         * `@dimen/lyric_render_inset` 算好后注入；宽度统一为 `maxWidth − 2 × insetPx`。
         */
        val insetPx: Float
    )

    fun styleFrom(
        context: Context,
        settings: SettingsRepository,
        density: Float,
        forPlayer: Boolean,
        insetPx: Float = 0f
    ): Style {
        val overlaySize = settings.fontSizeSp
        val baseCurrent = if (forPlayer) settings.playerFontSizeSp else overlaySize
        val baseNext = if (forPlayer) settings.playerNextFontSizeSp else overlaySize * 0.88f
        val baseOther = if (forPlayer) settings.playerFontSizeSp * 0.82f else overlaySize * 0.85f
        val family = settings.lyricFontFamily()
        val bold = !forPlayer && settings.overlayLyricBold
        val themeHighlight = settings.highlightColor
        val highlight = if (forPlayer) themeHighlight else overlayHighlightColor(themeHighlight)
        val pending = if (forPlayer) settings.pendingColor else overlayPendingColor(themeHighlight)
        val next = if (forPlayer) settings.nextLineColor else overlayNextColor(themeHighlight)
        val overlayPlayedTop = adjustForGradient(highlight, lift = 0.35f)
        val overlayPlayedBottom = adjustForGradient(highlight, lift = -0.05f)
        val overlayPlayedStroke = adjustForStroke(highlight, played = true)
        val overlayPendingTop = adjustForGradient(pending, lift = 0.28f)
        val overlayPendingBottom = adjustForGradient(pending, lift = -0.08f)
        val overlayPendingStroke = overlayNeutralStrokeColor(themeHighlight)
        val overlayNextTop = adjustForGradient(next, lift = 0.24f)
        val overlayNextBottom = adjustForGradient(next, lift = -0.06f)
        val overlayNextStroke = overlayNeutralStrokeColor(themeHighlight, lighter = true)
        val strokeWidthPx = if (forPlayer) 0f else overlayStrokeWidthPx(settings.overlayStrokeWidth)
        return Style(
            highlightColor = highlight,
            pendingColor = pending,
            nextLineColor = next,
            overlayPlayedTopColor = overlayPlayedTop,
            overlayPlayedBottomColor = overlayPlayedBottom,
            overlayPlayedStrokeColor = overlayPlayedStroke,
            overlayPendingTopColor = overlayPendingTop,
            overlayPendingBottomColor = overlayPendingBottom,
            overlayPendingStrokeColor = overlayPendingStroke,
            overlayNextTopColor = overlayNextTop,
            overlayNextBottomColor = overlayNextBottom,
            overlayNextStrokeColor = overlayNextStroke,
            currentSizePx = baseCurrent * density * if (forPlayer) settings.currentLineScale else 1f,
            nextSizePx = baseNext * density * if (forPlayer) settings.nextLineScale else 1f,
            otherSizePx = baseOther * density,
            typeface = LyricFontProvider.getTypeface(context, family, bold),
            maxVisualLines = settings.maxLyricVisualLines,
            currentScale = settings.currentLineScale,
            nextScale = settings.nextLineScale,
            bold = bold,
            outline = !forPlayer && settings.overlayStrokeEnabled,
            strokeWidthPx = strokeWidthPx,
            lineSpacing = if (forPlayer) settings.playerLyricLineSpacing else 2.2f,
            insetPx = insetPx
        )
    }

    fun wrapText(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        if (text.isEmpty() || maxWidth <= 0f) return emptyList()
        val lines = mutableListOf<String>()
        var start = 0
        while (start < text.length && lines.size < maxLines) {
            val count = paint.breakText(text, start, text.length, true, maxWidth, null)
            if (count <= 0) break
            var end = start + count
            if (end < text.length && lines.size == maxLines - 1) {
                val slice = text.substring(start, end).trimEnd()
                lines.add(if (end < text.length) slice.dropLastWhile { it != ' ' }.trimEnd() + "…" else slice)
                break
            }
            lines.add(text.substring(start, end).trimEnd())
            start = end
            while (start < text.length && text[start].isWhitespace()) start++
        }
        return lines.ifEmpty { listOf(text) }
    }

    data class CharRow(val chars: List<LrcChar>, val startX: Float, val baselineY: Float)

    fun layoutKaraokeRows(
        line: LrcLine,
        paint: Paint,
        maxWidth: Float,
        maxRows: Int
    ): List<CharRow> {
        if (line.chars.isEmpty()) return emptyList()
        val rows = mutableListOf<CharRow>()
        var rowChars = mutableListOf<LrcChar>()
        var rowWidth = 0f
        var currentY = 0f
        val lineHeight = paint.textSize * 1.35f

        fun flushRow() {
            if (rowChars.isEmpty()) return
            val totalW = rowChars.sumOf { paint.measureText(it.char).toDouble() }.toFloat()
            val startX = max(0f, (maxWidth - totalW) / 2f)
            rows.add(CharRow(rowChars.toList(), startX, currentY))
            rowChars = mutableListOf()
            rowWidth = 0f
            currentY += lineHeight
        }

        for (ch in line.chars) {
            val w = paint.measureText(ch.char)
            if (rowWidth + w > maxWidth && rowChars.isNotEmpty()) {
                flushRow()
                if (rows.size >= maxRows) break
            }
            rowChars.add(ch)
            rowWidth += w
        }
        if (rows.size < maxRows && rowChars.isNotEmpty()) flushRow()
        return rows
    }

    fun drawKaraokeLine(
        canvas: Canvas,
        line: LrcLine,
        positionMs: Float,
        centerY: Float,
        style: Style,
        maxWidth: Float,
        sungPaint: Paint,
        pendingPaint: Paint,
        alpha: Int = 255
    ): Float {
        sungPaint.typeface = style.typeface
        pendingPaint.typeface = style.typeface
        sungPaint.isFakeBoldText = style.bold
        pendingPaint.isFakeBoldText = style.bold
        sungPaint.textSize = style.currentSizePx
        pendingPaint.textSize = style.currentSizePx

        val rows = layoutKaraokeRows(
            line, pendingPaint,
            maxWidth - 2 * style.insetPx, style.maxVisualLines
        )
        if (rows.isEmpty()) return 0f

        val totalHeight = rows.size * style.currentSizePx * 1.35f
        var topY = centerY - totalHeight / 2f + style.currentSizePx
        val padX = style.insetPx
        var charGlobalIndex = 0

        for (row in rows) {
            drawKaraokeRowWipe(
                canvas = canvas,
                line = line,
                row = row,
                rowStartIndex = charGlobalIndex,
                baselineY = topY,
                positionMs = positionMs,
                style = style,
                padX = padX,
                sungPaint = sungPaint,
                pendingPaint = pendingPaint,
                alpha = alpha
            )
            charGlobalIndex += row.chars.size
            topY += style.currentSizePx * 1.35f
        }
        return totalHeight
    }

    private fun drawKaraokeRowWipe(
        canvas: Canvas,
        line: LrcLine,
        row: CharRow,
        rowStartIndex: Int,
        baselineY: Float,
        positionMs: Float,
        style: Style,
        padX: Float,
        sungPaint: Paint,
        pendingPaint: Paint,
        alpha: Int = 255
    ) {
        if (row.chars.isEmpty()) return
        val text = row.chars.joinToString("") { it.char }
        val x = row.startX + padX
        val textSize = style.currentSizePx
        val fillEndX = sungFillEndX(line, positionMs, pendingPaint, row, rowStartIndex, padX)

        drawKaraokeRowLayer(canvas, text, x, baselineY, pendingPaint, style, played = false, alpha = alpha)

        if (fillEndX > x + 0.5f) {
            canvas.save()
            canvas.clipRect(
                x - 1f,
                baselineY - textSize * 1.15f,
                fillEndX,
                baselineY + textSize * 0.15f
            )
            drawKaraokeRowLayer(canvas, text, x, baselineY, sungPaint, style, played = true, alpha = alpha)
            canvas.restore()
        }
    }

    private fun drawKaraokeRowLayer(
        canvas: Canvas,
        text: String,
        x: Float,
        baselineY: Float,
        paint: Paint,
        style: Style,
        played: Boolean,
        alpha: Int = 255
    ) {
        if (style.outline) {
            if (played) {
                drawGradientText(
                    canvas, text, x, baselineY, paint, style,
                    style.overlayPlayedTopColor,
                    style.overlayPlayedBottomColor,
                    style.overlayPlayedStrokeColor,
                    alpha
                )
            } else {
                drawGradientText(
                    canvas, text, x, baselineY, paint, style,
                    style.overlayPendingTopColor,
                    style.overlayPendingBottomColor,
                    style.overlayPendingStrokeColor,
                    alpha
                )
            }
        } else {
            paint.color = ColorUtils.setAlphaComponent(
                if (played) style.highlightColor else style.pendingColor,
                alpha,
            )
            canvas.drawText(text, x, baselineY, paint)
        }
    }

    private fun sungFillEndX(
        line: LrcLine,
        positionMs: Float,
        paint: Paint,
        row: CharRow,
        rowStartIndex: Int,
        padX: Float
    ): Float {
        var x = row.startX + padX
        for (i in row.chars.indices) {
            val globalIdx = rowStartIndex + i
            val charText = row.chars[i].char
            val w = paint.measureText(charText)
            val start = charStartMs(line, globalIdx).toFloat()
            val end = charEndMs(line, globalIdx).toFloat().coerceAtLeast(start + 1f)
            when {
                positionMs >= end -> x += w
                positionMs <= start -> return x
                else -> {
                    val frac = ((positionMs - start) / (end - start)).coerceIn(0f, 1f)
                    return x + w * frac
                }
            }
        }
        return x
    }

    private fun charStartMs(line: LrcLine, index: Int): Long {
        val chars = line.chars
        if (index in chars.indices && (index == 0 || chars[index].startTimeMs > chars[index - 1].startTimeMs)) {
            return chars[index].startTimeMs
        }
        val count = chars.size.coerceAtLeast(1)
        val duration = (line.endTimeMs - line.startTimeMs).coerceAtLeast(count.toLong())
        val slot = duration / count
        return line.startTimeMs + slot * index
    }

    private fun charEndMs(line: LrcLine, index: Int): Long {
        val chars = line.chars
        if (index + 1 < chars.size && chars[index + 1].startTimeMs > chars[index].startTimeMs) {
            return chars[index + 1].startTimeMs
        }
        val count = chars.size.coerceAtLeast(1)
        val duration = (line.endTimeMs - line.startTimeMs).coerceAtLeast(count.toLong())
        val slot = duration / count
        return charStartMs(line, index) + slot
    }

    fun drawWrappedStaticLine(
        canvas: Canvas,
        text: String,
        centerY: Float,
        paint: Paint,
        style: Style,
        maxWidth: Float,
        color: Int,
        sizePx: Float,
        maxLines: Int,
        alpha: Int = 255
    ) {
        paint.typeface = style.typeface
        // 判等（isPlayedLike / isNextLike）必须用**原始 color**，不能掺进 alpha，
        // 否则描边选色会随行透明度一起漂移。
        paint.color = ColorUtils.setAlphaComponent(color, alpha)
        paint.textSize = sizePx
        val lines = wrapText(text, paint, maxWidth - 2 * style.insetPx, maxLines)
        val lineHeight = sizePx * 1.3f
        val totalH = lines.size * lineHeight
        var y = centerY - totalH / 2f + sizePx
        val pad = style.insetPx
        for (line in lines) {
            val w = paint.measureText(line)
            val lineX = max(pad, (maxWidth - w) / 2f)
            if (style.outline) {
                val isPlayedLike = color == style.highlightColor
                val isNextLike = color == style.nextLineColor
                val top = when {
                    isPlayedLike -> style.overlayPlayedTopColor
                    isNextLike -> style.overlayNextTopColor
                    else -> style.overlayPendingTopColor
                }
                val bottom = when {
                    isPlayedLike -> style.overlayPlayedBottomColor
                    isNextLike -> style.overlayNextBottomColor
                    else -> style.overlayPendingBottomColor
                }
                val stroke = when {
                    isPlayedLike -> style.overlayPlayedStrokeColor
                    isNextLike -> style.overlayNextStrokeColor
                    else -> style.overlayPendingStrokeColor
                }
                drawGradientText(canvas, line, lineX, y, paint, style, top, bottom, stroke, alpha)
            } else {
                drawText(canvas, line, lineX, y, paint, style)
            }
            y += lineHeight
        }
    }

    fun drawOverlayBlock(
        canvas: Canvas,
        current: LrcLine?,
        next: LrcLine?,
        positionMs: Float,
        width: Int,
        height: Int,
        style: Style,
        sungPaint: Paint,
        nextPaint: Paint,
        pendingPaint: Paint
    ) {
        sungPaint.typeface = style.typeface
        nextPaint.typeface = style.typeface
        pendingPaint.typeface = style.typeface
        sungPaint.isFakeBoldText = style.bold
        nextPaint.isFakeBoldText = style.bold
        pendingPaint.isFakeBoldText = style.bold
        val maxW = width.toFloat()
        val gap = style.currentSizePx * 0.6f
        val blockH = height / 2f

        current?.let { line ->
            drawKaraokeLine(
                canvas, line, positionMs, blockH - gap, style, maxW, sungPaint, pendingPaint
            )
        } ?: drawWrappedStaticLine(
            canvas, PLACEHOLDER_LINE, blockH - gap, pendingPaint, style, maxW,
            style.pendingColor, style.currentSizePx, 1
        )

        next?.let { line ->
            drawWrappedStaticLine(
                canvas, line.text, blockH + blockH / 2f + gap, nextPaint, style, maxW,
                style.nextLineColor, style.nextSizePx, style.maxVisualLines
            )
        }
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint, style: Style) {
        if (!style.outline) {
            canvas.drawText(text, x, y, paint)
            return
        }
        val fillColor = paint.color
        val strokeWidth = strokeWidthFor(paint.textSize, style.strokeWidthPx)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.argb(220, 0, 0, 0)
        canvas.drawText(text, x, y, paint)
        paint.style = Paint.Style.FILL
        paint.color = fillColor
        canvas.drawText(text, x, y, paint)
    }

    private fun drawGradientText(
        canvas: Canvas,
        text: String,
        x: Float,
        baselineY: Float,
        paint: Paint,
        style: Style,
        topColor: Int,
        bottomColor: Int,
        strokeColor: Int,
        alpha: Int = 255
    ) {
        val fillColor = paint.color
        val prevShader = paint.shader
        val textTop = baselineY - paint.textSize
        val textBottom = baselineY + paint.textSize * 0.1f
        paint.shader = LinearGradient(
            x, textTop, x, textBottom,
            ColorUtils.setAlphaComponent(topColor, alpha),
            ColorUtils.setAlphaComponent(bottomColor, alpha),
            Shader.TileMode.CLAMP
        )
        if (style.outline) {
            val strokeWidth = strokeWidthFor(paint.textSize, style.strokeWidthPx)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = ColorUtils.setAlphaComponent(strokeColor, alpha)
            canvas.drawText(text, x, baselineY, paint)
            paint.style = Paint.Style.FILL
        }
        canvas.drawText(text, x, baselineY, paint)
        paint.shader = prevShader
        paint.color = fillColor
    }

    /** 悬浮歌词：正在播放行用高饱和主题色 */
    private fun overlayHighlightColor(color: Int): Int {
        val base = if (Color.alpha(color) == 0) Color.parseColor("#EC4141") else color
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        if (hsv[1] < 0.08f) {
            return Color.parseColor("#EC4141")
        }
        hsv[1] = min(1f, hsv[1] * 1.2f)
        hsv[2] = min(1f, hsv[2] * 1.05f)
        return Color.HSVToColor(255, hsv)
    }

    private fun adjustForGradient(color: Int, lift: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] + lift).coerceIn(0.20f, 1f)
        hsv[1] = (hsv[1] * 0.88f).coerceIn(0.08f, 1f)
        return Color.HSVToColor(230, hsv)
    }

    private fun adjustForStroke(color: Int, played: Boolean): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * if (played) 0.45f else 0.30f).coerceAtLeast(0.18f)
        hsv[1] = (hsv[1] * 0.9f).coerceIn(0.1f, 1f)
        return Color.HSVToColor(245, hsv)
    }

    /** 待唱/下一行用中性深灰描边，避免粉色描边糊字 */
    private fun overlayNeutralStrokeColor(themeHighlight: Int, lighter: Boolean = false): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(themeHighlight, hsv)
        hsv[1] = (hsv[1] * 0.06f).coerceIn(0f, 0.10f)
        hsv[2] = if (lighter) 0.32f else 0.24f
        return Color.HSVToColor(245, hsv)
    }

    /** 1=最细 … 10=最粗，默认 3 约等于字号的 3% */
    private fun overlayStrokeWidthPx(level: Int): Float {
        val ratio = 0.012f + (level - 1) * 0.007f
        return ratio
    }

    private fun strokeWidthFor(textSizePx: Float, ratio: Float): Float {
        if (ratio <= 0f) return (textSizePx * 0.03f).coerceIn(1f, 4f)
        return (textSizePx * ratio).coerceIn(1f, 5f)
    }
}
