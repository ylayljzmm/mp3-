package com.suisheng.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.view.GestureDetectorCompat
import com.suisheng.player.R
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.model.LrcLine
import kotlin.math.abs

class ScrollLyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val settings = SettingsRepository(context)
    private val sungPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pendingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var lines: List<LrcLine> = emptyList()

    /** 每行歌词在内容中的起始偏移与高度：按实际换行数累计，行距由设置系数控制 */
    private var lineTops = FloatArray(0)
    private var lineHeights = FloatArray(0)

    /**
     * 每行**正文**部分的高度（不含译注行）。
     *
     * 与 [lineHeights] 分开存是因为绘制时要算正文的中心点：`drawKaraokeLine` /
     * `drawWrappedStaticLine` 都是「按中心 Y 居中画」，一个块里既有正文又有译注时，
     * 用整块的中心去画正文会让正文整体下移半行。
     */
    private var mainHeights = FloatArray(0)

    /** [ensureLayout] 算出的换行宽度，绘制译注行时复用（两处必须同口径）。 */
    private var layoutMaxWidth = 0f
    private var totalHeightPx = 0f
    private var layoutWidth = -1
    private var layoutIndex = -2
    private var layoutKey = -1f

    /** 每侧水平内缩（px）：换行测量与绘制共用，见 [renderInsetPx] */
    private var cachedInsetPx = 0f
    private var renderInsetResolved = false

    /**
     * 是否启用「按行渐隐」。5.5.0 起不再用色块遮罩，只把颜色当作开关：
     * 传 `Color.TRANSPARENT` 关闭，其余任何颜色都表示启用。
     */
    private var fadeEnabled: Boolean = false

    private var targetPositionMs = 0L
    private var displayPositionMs = 0f
    private var smoothScrollY = 0f
    private var targetScrollY = 0f
    private var userScrollOffset = 0f
    private var isUserScrolling = false
    private var animating = false

    private val gestureDetector = GestureDetectorCompat(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                // 刻意**不**调用 requestDisallowInterceptTouchEvent(true)：
                // 播放页现在把歌词放在 SwipeDeckLayout 里，横向滑动是翻页手势。
                // 一旦在 DOWN 就禁止父容器拦截，deck 永远收不到横向事件，歌词页就锁死了。
                // 竖向滚动不受影响：deck 只在「横向位移 > 纵向位移 × 1.5」时才拦截。
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                isUserScrolling = true
                userScrollOffset -= distanceY
                clampUserScrollOffset()
                invalidate()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                performClick()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                performLongClick()
            }
        }
    )

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating) return
            val smooth = settings.smoothLyrics
            val lerp = if (smooth) 0.18f else 1f
            displayPositionMs += (targetPositionMs - displayPositionMs) * lerp
            if (!isUserScrolling) {
                userScrollOffset *= 0.9f
                if (abs(userScrollOffset) < 1.5f) userScrollOffset = 0f
            }
            computeTargetScroll()
            smoothScrollY += (targetScrollY - smoothScrollY) * lerp
            invalidate()
            if (smooth && (abs(targetPositionMs - displayPositionMs) > 8f ||
                    abs(targetScrollY - smoothScrollY) > 0.5f ||
                    (!isUserScrolling && abs(userScrollOffset) > 1.5f))
            ) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                displayPositionMs = targetPositionMs.toFloat()
                smoothScrollY = targetScrollY
                if (!isUserScrolling) userScrollOffset = 0f
                animating = false
            }
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
    }

    fun update(lines: List<LrcLine>, positionMs: Long) {
        if (this.lines.size != lines.size) invalidateLayout()
        this.lines = lines
        targetPositionMs = positionMs
        if (!settings.smoothLyrics) {
            displayPositionMs = positionMs.toFloat()
            computeTargetScroll()
            smoothScrollY = targetScrollY
            if (!isUserScrolling) userScrollOffset = 0f
            invalidate()
            return
        }
        computeTargetScroll()
        if (!animating) {
            animating = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    fun refreshStyle() {
        invalidateLayout()
        invalidate()
    }

    private fun invalidateLayout() {
        layoutWidth = -1
        layoutIndex = -2
        layoutKey = -1f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isUserScrolling = false
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateLayout()
        if (lines.isNotEmpty()) {
            computeTargetScroll()
            smoothScrollY = targetScrollY
        }
    }

    override fun onDetachedFromWindow() {
        animating = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    private fun currentStyle(): LyricRenderer.Style = LyricRenderer.styleFrom(
        context, settings, resources.displayMetrics.scaledDensity, forPlayer = true,
        insetPx = renderInsetPx()
    )

    /**
     * 换行与绘制共用的每侧水平内缩（px）。
     *
     * 以前渲染层写死 `24f`、换行层只减 `paddingLeft/Right`，两个出口口径不一致，
     * 超长行会出现「测量两行、只画一行」。现在两边都走 `maxTextWidth − 2 × insetPx`，
     * 内缩值由布局令牌 `@dimen/lyric_render_inset` 给（本期为 0dp，只打通注入链路）。
     */
    private fun renderInsetPx(): Float {
        if (!renderInsetResolved) {
            cachedInsetPx = resources.getDimension(R.dimen.lyric_render_inset)
            renderInsetResolved = true
        }
        return cachedInsetPx
    }

    /**
     * 传 `Color.TRANSPARENT` 关闭按行渐隐，其余颜色开启（`05 §6`）。
     *
     * 5.5.0 起**语义变了但签名没变**：原来是「用这个颜色画上下 96dp 的不透明遮罩」，
     * 那个做法在播放页上必然对不上色 —— 真实背景是 `BackdropView` 的双径向色块
     * 加两层 scrim，`colorSurface` 永远不是屏幕上那个像素色，于是画出两条硬边，
     * 还把 428dp 高的歌词列上下各吃掉 96dp。现在这个颜色只当开关用
     * （3 个调用方不用改），渐隐改成按行透明度实现。
     */
    fun setFadeColor(@ColorInt color: Int) {
        val enabled = color != Color.TRANSPARENT
        if (fadeEnabled == enabled) return
        fadeEnabled = enabled
        invalidate()
    }

    private fun textPaddingPx(): Float = paddingLeft.toFloat() + paddingRight.toFloat()

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    /**
     * 行高 = 换行行数 × ROW_RATIO 倍字号 + 行距；
     * 行距 = (设置系数 - ROW_RATIO) × 字号，系数 2.4 时单行占 2.4 倍字号。
     */
    private fun ensureLayout(style: LyricRenderer.Style, idx: Int, viewWidth: Int) {
        if (viewWidth <= 0) return
        val key = style.currentSizePx * 31.7f +
            style.nextSizePx * 17.3f +
            style.otherSizePx * 11.9f +
            style.lineSpacing * 7.7f +
            style.maxVisualLines * 3.1f +
            style.insetPx * 5.3f +
            // 译文 / 注音开关一变，每行占的高度就变了，必须重排
            subLineFlags() * 1.7f
        if (viewWidth == layoutWidth && idx == layoutIndex && key == layoutKey &&
            lineTops.size == lines.size
        ) {
            return
        }
        layoutWidth = viewWidth
        layoutIndex = idx
        layoutKey = key
        lineTops = FloatArray(lines.size)
        lineHeights = FloatArray(lines.size)
        mainHeights = FloatArray(lines.size)
        if (lines.isEmpty()) {
            totalHeightPx = 0f
            return
        }

        measurePaint.typeface = style.typeface
        // 换行宽度与 LyricRenderer 的绘制宽度必须是同一口径：两边都减 `2 × insetPx`。
        val maxTextWidth = (viewWidth - textPaddingPx() - 2 * style.insetPx)
            .coerceAtLeast(dp(48f))
        layoutMaxWidth = maxTextWidth
        val gapRatio = (style.lineSpacing - ROW_RATIO).coerceAtLeast(0f)
        var y = 0f
        for (i in lines.indices) {
            val size = when {
                i == idx -> style.currentSizePx
                abs(i - idx) == 1 -> style.nextSizePx
                else -> style.otherSizePx
            }
            measurePaint.textSize = size
            val mainRows = countRows(lines[i].text, maxTextWidth, style.maxVisualLines)
            val mainHeight = mainRows * size * ROW_RATIO
            val subHeight = subLinesHeight(lines[i], size, maxTextWidth)
            val height = mainHeight + subHeight + size * gapRatio
            lineTops[i] = y
            lineHeights[i] = height
            mainHeights[i] = mainHeight
            y += height
        }
        totalHeightPx = y
    }

    /** 译文 / 注音两个开关的位掩码；参与 [layoutKey]，任一翻转都要重排。 */
    private fun subLineFlags(): Int {
        var flags = 0
        if (settings.lyricShowPhonetic) flags = flags or 1
        if (settings.lyricShowTranslation) flags = flags or 2
        return flags
    }

    /**
     * 一行要额外画出来的译注行，顺序固定 **注音 → 译文**。
     *
     * 与 [LrcLine.displayLines] 的顺序一致（那边是 原文 → 注音 → 译文，原文由调用方
     * 自己先画）。没有数据就不产生空行，行高也就不会多算。
     */
    private fun subTexts(line: LrcLine): List<String> {
        val out = ArrayList<String>(2)
        if (settings.lyricShowPhonetic) line.phonetic?.takeIf { it.isNotBlank() }?.let(out::add)
        if (settings.lyricShowTranslation) line.translation?.takeIf { it.isNotBlank() }?.let(out::add)
        return out
    }

    /** 译注行占的总高度（可能被折成多行）。测量用的 textSize 会在调用方之后再设回去。 */
    private fun subLinesHeight(line: LrcLine, mainSize: Float, maxWidth: Float): Float {
        val subs = subTexts(line)
        if (subs.isEmpty()) return 0f
        val subSize = mainSize * SUB_TEXT_RATIO
        measurePaint.textSize = subSize
        var sum = 0f
        for (text in subs) {
            val rows = countRows(text, maxWidth, SUB_MAX_ROWS)
            sum += rows * subSize * SUB_ROW_RATIO
        }
        return sum
    }

    private fun countRows(text: String, maxWidth: Float, maxRows: Int): Int {
        if (text.isEmpty() || maxWidth <= 0f) return 1
        var start = 0
        var rows = 0
        while (start < text.length && rows < maxRows) {
            val count = measurePaint.breakText(text, start, text.length, true, maxWidth, null)
            if (count <= 0) break
            start += count
            rows++
        }
        return rows.coerceAtLeast(1)
    }

    private fun computeTargetScroll() {
        if (lines.isEmpty()) {
            targetScrollY = 0f
            return
        }
        val pos = if (settings.smoothLyrics) targetPositionMs else displayPositionMs.toLong()
        val idx = findIndex(pos)
        ensureLayout(currentStyle(), idx, width)
        targetScrollY = offsetForLine(idx)
    }

    private fun offsetForLine(idx: Int): Float {
        if (idx !in lineTops.indices) return 0f
        return lineTops[idx] + lineHeights[idx] / 2f
    }

    private fun effectiveScrollY(): Float = smoothScrollY + userScrollOffset

    private fun clampUserScrollOffset() {
        if (lines.isEmpty() || totalHeightPx <= 0f) {
            userScrollOffset = 0f
            return
        }
        val maxOffset = (totalHeightPx / 2f + height / 2f).coerceAtLeast(1f)
        userScrollOffset = userScrollOffset.coerceIn(-maxOffset, maxOffset)
    }

    override fun onDraw(canvas: Canvas) {
        drawLyrics(canvas)
    }

    private fun drawLyrics(canvas: Canvas) {
        super.onDraw(canvas)
        val style = currentStyle()

        if (lines.isEmpty()) {
            LyricRenderer.drawWrappedStaticLine(
                canvas, LyricRenderer.PLACEHOLDER_LINE, height / 2f, pendingPaint, style, width.toFloat(),
                style.pendingColor, style.otherSizePx, 2
            )
            return
        }

        val idx = findIndex(displayPositionMs.toLong())
        ensureLayout(style, idx, width)
        val scrollY = effectiveScrollY()
        val maxTextWidth = layoutMaxWidth

        for (i in lines.indices) {
            val blockTop = height / 2f - scrollY + lineTops[i]
            val blockCenterY = blockTop + lineHeights[i] / 2f
            if (blockCenterY < -lineHeights[i] || blockCenterY > height + lineHeights[i]) continue

            val alpha = lineAlpha(blockCenterY)
            // 正文按**正文自己**的中心画，不是整块的中心：有译注行时整块更高，
            // 用整块中心会让正文整体下移半行。
            val mainCenterY = blockTop + mainHeights[i] / 2f
            val size = when {
                i == idx -> style.currentSizePx
                abs(i - idx) == 1 -> style.nextSizePx
                else -> style.otherSizePx
            }
            val baseColor = if (i < idx) style.nextLineColor else style.pendingColor
            if (i == idx) {
                LyricRenderer.drawKaraokeLine(
                    canvas, lines[i], displayPositionMs, mainCenterY,
                    style, width.toFloat(), sungPaint, pendingPaint, alpha
                )
            } else {
                LyricRenderer.drawWrappedStaticLine(
                    canvas, lines[i].text, mainCenterY, nextPaint, style,
                    width.toFloat(), baseColor, size, style.maxVisualLines, alpha
                )
            }

            // 译注行：正文下方依次排，不参与卡拉 OK 擦除（译文跟着逐字擦会很怪）。
            val subs = subTexts(lines[i])
            if (subs.isEmpty()) continue
            val subSize = size * SUB_TEXT_RATIO
            val subColor = baseColor
            val subAlpha = (alpha * SUB_ALPHA_RATIO).toInt().coerceIn(0, 255)
            var y = blockTop + mainHeights[i]
            for (text in subs) {
                measurePaint.textSize = subSize
                val rows = countRows(text, maxTextWidth, SUB_MAX_ROWS)
                val subHeight = rows * subSize * SUB_ROW_RATIO
                LyricRenderer.drawWrappedStaticLine(
                    canvas, text, y + subHeight / 2f, pendingPaint, style,
                    width.toFloat(), subColor, subSize, SUB_MAX_ROWS, subAlpha
                )
                y += subHeight
            }
        }
    }

    /**
     * 按行渐隐：行中心与视图中心的距离决定透明度（5.5.0 取代上下遮罩）。
     *
     * - 距中心 ≤ `height/2 × 0.42` 的行完全不透明（视口中间的 84% 是阅读区）；
     * - 之外线性衰减，最边缘降到 6%（16/255），刚好够看见"还有下一行"但不抢焦点；
     * - 关闭渐隐（[fadeEnabled] = false）时恒为 255，与改造前副屏的表现逐像素一致。
     */
    private fun lineAlpha(lineCenterY: Float): Int {
        if (!fadeEnabled || height <= 0) return 255
        val half = height / 2f
        val solidBand = half * FADE_SOLID_RATIO
        val distance = abs(lineCenterY - half)
        if (distance <= solidBand) return 255
        val span = (half - solidBand).coerceAtLeast(1f)
        val t = ((distance - solidBand) / span).coerceIn(0f, 1f)
        val alpha = (255f + (FADE_MIN_ALPHA - 255f) * t).toInt()
        return alpha.coerceIn(FADE_MIN_ALPHA, 255)
    }

    private fun findIndex(positionMs: Long): Int {
        var index = 0
        for (i in lines.indices) {
            if (positionMs >= lines[i].startTimeMs) index = i else break
        }
        return index
    }

    private companion object {
        /**
         * 单行字号的「字形占位」倍率（字体度量用，不是排版行距）。
         *
         * 本类的全部排版规格都在这里与 [LyricRenderer.Style] 里，**不放布局文件**：
         * 歌词是 Canvas 自绘的，没有逐行 View（8Hz 进度回调下逐行 View 会带来
         * GC 抖动与每帧 requestLayout）。`design/05 §9.2` 给的四个数落在：
         *  - 行距 `1.72`      → `SettingsRepository.playerLyricLineSpacing`
         *  - 当前行 `1.55`    → `SettingsRepository.currentLineScale`
         *  - 基准字号         → `SettingsRepository.playerFontSizeSp`
         *  - 渐隐区 `上下各 58%` → [FADE_SOLID_RATIO] / [FADE_MIN_ALPHA]（5.5.0 改为按行透明度）
         *
         * 6.0 起**没有全屏歌词态**（歌词永远是横屏右栏 / 竖屏 deck 第 3 页），
         * 所以「全屏字号放大 1.35 倍」那条常量与本类的 `sizeScale` 一起删除：
         * 字号只由用户的「歌词设置」决定，不再有第二种来源。
         */
        const val ROW_RATIO = 1.35f

        /**
         * 译注行（译文 / 注音）相对正文的**字号**比例。
         *
         * 0.62 是「一眼看出是附属信息、但又不用凑近看」的折中：再小（0.5 以下）在车机
         * 1080p 屏上会糊，再大（0.75 以上）就和正文抢焦点，与「原文是主角」的排版意图冲突。
         */
        const val SUB_TEXT_RATIO = 0.62f

        /** 译注行的行距倍率：附属信息不需要正文那么松，0.62 × 1.25 ≈ 0.78 倍正文字号。 */
        const val SUB_ROW_RATIO = 1.25f

        /** 译注行最多折几行：再多就不像「一行译注」了，宁可截断。 */
        const val SUB_MAX_ROWS = 2

        /** 译注行的透明度相对正文的折扣，让它在视觉上退后一层。 */
        const val SUB_ALPHA_RATIO = 0.78f

        /** 视口中心 ±(height/2 × 0.42) 的行完全不透明，也就是中间 84% 是阅读区。 */
        const val FADE_SOLID_RATIO = 0.42f

        /** 最边缘的行保留 6% 透明度：看得见"还有下一行"，但不抢当前行的注意力。 */
        const val FADE_MIN_ALPHA = 16
    }
}
