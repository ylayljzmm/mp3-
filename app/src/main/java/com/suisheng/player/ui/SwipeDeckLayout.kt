package com.suisheng.player.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.IdRes
import com.suisheng.player.R
import kotlin.math.abs

/**
 * 横向翻页容器（播放页「歌曲信息 / 封面 / 歌词」三页，横屏两页）。
 *
 * ## 为什么不用 ViewPager2
 *
 * ViewPager2 内部是一个 RecyclerView：它会接管子 View 的复用与 `layoutParams`，
 * 而播放页的三页里有一个**一直在转的黑胶**和**一个自绘歌词**，被 RecyclerView
 * 回收重建的代价远大于收益；而且 `RecyclerView` 的嵌套滚动会跟歌词自身的
 * 手势监听打架（歌词要竖着拖，页面要横着翻）。
 *
 * 这里只要「三张整屏卡片 + 跟手位移」，所以手写一个 FrameLayout 更直接：
 * 所有子 View 都铺满 `0,0,w,h`，靠 `translationX` 定位，不涉及任何测量逻辑。
 *
 * ## 页数由子 View 数量决定
 *
 * **不能写死 3**：竖屏 `fragment_player.xml` 里 deck 有「信息 / 封面 / 歌词」三页，
 * 横屏 `fragment_player.xml` 里歌词不在 deck 中（它是右栏），deck 只有两页。
 * 两个布局共用同一个 ViewBinding，所以 `pageCount` 必须从 `childCount` 读。
 *
 * ## 手势
 *
 * - 只有「横向位移明显大于纵向」时才拦截，否则竖着拖歌词会被误判成翻页；
 * - 抬手按「速度优先、位移兜底」决定目标页，跟手过程用 200ms 减速动画收尾；
 * - 非当前页设 `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS`，
 *   否则读屏会把屏幕外的歌词整段念出来。
 */
class SwipeDeckLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 页面切换回调（只在索引真的变了时触发）。 */
    var onPageChanged: ((Int) -> Unit)? = null

    /** 页数 = 子 View 数。 */
    val pageCount: Int get() = childCount

    /** 当前停靠页。拖动过程中不变，抬手/动画结束后才更新。 */
    var currentIndex: Int = 0
        private set

    /**
     * 连续的位置：`2.37` 表示「第 2 页往第 3 页偏了 37%」。
     * 每个子 View 的位移 = `(i - progress) * width`。
     */
    private var progress: Float = 0f

    private var settleAnimator: ValueAnimator? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var progressAtDown = 0f

    // ---------------------------------------------------------------- 初始页

    override fun onFinishInflate() {
        super.onFinishInflate()
        // 初始页 = 封面那一页（播放页打开时应该看到唱片）。
        // 用 post 而不是直接调：此时 width 还是 0，applyOffsets() 会直接返回，
        // 等到第一次 layout 结束再定位才不会出现"第一页闪一下"。
        post {
            val cover = indexOfPage(R.id.pageCover)
            val initial = if (cover >= 0) cover else childCount / 2
            setCurrent(initial, animate = false)
        }
    }

    // ---------------------------------------------------------------- 布局

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        for (i in 0 until childCount) {
            // 每一页都铺满整个 deck：不做任何测量代理，也不看子 View 自己的 layoutParams。
            getChildAt(i).layout(0, 0, w, h)
        }
        applyOffsets()
    }

    /** 按 [progress] 把所有子 View 平移到各自位置，并同步无障碍可见性。 */
    private fun applyOffsets() {
        val w = width
        if (w <= 0) return
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.translationX = (i - progress) * w
            // 屏幕外的页面必须对读屏隐藏，否则「下一行歌词」会被整段念出来
            child.importantForAccessibility = if (i == currentIndex) {
                IMPORTANT_FOR_ACCESSIBILITY_AUTO
            } else {
                IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        }
    }

    // ---------------------------------------------------------------- 对外 API

    fun indexOfPage(@IdRes id: Int): Int {
        for (i in 0 until childCount) {
            if (getChildAt(i).id == id) return i
        }
        return -1
    }

    /**
     * 跳到第 [index] 页。
     *
     * @param animate false = 立即到位（初始化用），true = 200ms 减速动画
     */
    fun setCurrent(index: Int, animate: Boolean) {
        if (childCount == 0) return
        val target = index.coerceIn(0, childCount - 1)
        if (!animate) {
            stopSettleAnimation()
            progress = target.toFloat()
            applyOffsets()
            commitIndex(target)
            return
        }
        settleTo(target)
    }

    // ---------------------------------------------------------------- 触摸

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 只有一页时完全不拦截：否则歌词 / 信息页自己的点击都会被吞掉
        if (childCount <= 1 || width == 0) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                // 这里**不能**停收尾动画：ACTION_DOWN 只代表手指按下，还不确定是
                // 「点一下」还是「拖动」。如果此刻就 stopSettleAnimation()，
                // 正在 1.4 页位置收尾的动画会被冻在半路，而「点一下」不会产生任何
                // MOVE、也不会走到 ACTION_UP 的 settleTo，于是两页永久各露一半。
                // 停动画的时机推迟到「确认横向拖动」那一刻（见下面的 MOVE 分支）。
                progressAtDown = progress
                startVelocityTracker(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * HORIZONTAL_BIAS) {
                    // 确认要翻页了才接管收尾动画。顺序不可颠倒：先冻结动画，
                    // 再读 progress 作为拖动起点，否则读到的是动画下一帧的值，
                    // 手指一落下页面就会跳一下。
                    stopSettleAnimation()
                    dragging = true
                    progressAtDown = progress
                    // 一旦确认是横向翻页，就不让外层（DrawerLayout）再抢
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // 返回 true 会让子 View 收到 ACTION_CANCEL，之后的 MOVE 走 onTouchEvent
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (childCount <= 1) return false
        val tracker = velocityTracker
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                // 同 onInterceptTouchEvent：按住不算拖动，不能在这里冻住收尾动画，
                // 否则「点一下」会把页面永久停在两页之间。
                progressAtDown = progress
                startVelocityTracker(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val w = width
                if (w <= 0) return false
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging) {
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy) * HORIZONTAL_BIAS) {
                        // 确认横向拖动后才冻结动画，再取当前进度当起点（顺序不可颠倒）
                        stopSettleAnimation()
                        dragging = true
                        progressAtDown = progress
                    } else {
                        return true
                    }
                }
                val next = (progressAtDown - dx / w)
                    .coerceIn(0f, (childCount - 1).toFloat())
                // 位移超过一页也没意义：范围已经夹住，多拖的部分不累积
                if (next != progress) {
                    progress = next
                    applyOffsets()
                }
                return true
            }

            // 多指：本容器全程只跟 pointer 0（`event.x`）。第二根手指按下后，
            // 系统可能把 pointer 0 换成另一根手指，`event.x` 会瞬间跳过一个手掌的宽度，
            // 一次位移就能翻好几页 —— 竖屏歌词页恰好是双指可滚的表面，很容易误触。
            // 最小防护：直接放弃这次拖动回到当前页，并把起点重置到新的 pointer 0，
            // 这样剩下的手指要重新移动超过 touchSlop 才会再次接管。
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (dragging) {
                    dragging = false
                    settleTo(currentIndex)
                }
                downX = event.x
                downY = event.y
                return true
            }

            // 抬起一根手指同样会让 pointer 0 换人，但要保留正在进行的拖动，
            // 所以只吃掉事件、不动状态（不动声色的收尾交给 ACTION_UP）。
            MotionEvent.ACTION_POINTER_UP -> return true

            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    recycleVelocityTracker()
                    return false
                }
                tracker?.computeCurrentVelocity(1000)
                val velocityX = tracker?.xVelocity ?: 0f
                settleTo(resolveTarget(velocityX))
                recycleVelocityTracker()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                recycleVelocityTracker()
                // 取消（被父容器抢走）：回到当前页，不要停在半路
                if (dragging) settleTo(currentIndex)
                dragging = false
                return true
            }
        }
        return false
    }

    /** 速度优先、位移兜底：快速一甩只要方向对就翻页，慢拖要过半页。 */
    private fun resolveTarget(velocityX: Float): Int {
        val w = width
        val travel = progress - currentIndex
        val target = when {
            abs(velocityX) > FLING_VELOCITY_PX && w > 0 ->
                // 手指往左滑（velocityX < 0）= progress 增大 = 下一页
                if (velocityX < 0f) currentIndex + 1 else currentIndex - 1

            abs(travel) >= FLIP_RATIO -> if (travel > 0f) currentIndex + 1 else currentIndex - 1
            else -> currentIndex
        }
        return target.coerceIn(0, childCount - 1)
    }

    // ---------------------------------------------------------------- 动画

    private fun settleTo(target: Int) {
        val from = progress
        val to = target.toFloat()
        stopSettleAnimation()
        if (abs(from - to) < SETTLE_EPSILON) {
            progress = to
            applyOffsets()
            commitIndex(target)
            return
        }
        val animator = ValueAnimator.ofFloat(from, to).apply {
            duration = SETTLE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                progress = animation.animatedValue as Float
                applyOffsets()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 被新触摸打断时 stopSettleAnimation 已经把 settleAnimator 置空，
                    // 这里比对一下，避免"打断后仍然把旧目标提交成当前页"。
                    if (settleAnimator !== animation) return
                    settleAnimator = null
                    progress = to
                    applyOffsets()
                    commitIndex(target)
                }
            })
        }
        settleAnimator = animator
        animator.start()
    }

    private fun stopSettleAnimation() {
        val animator = settleAnimator ?: return
        settleAnimator = null
        animator.cancel()
    }

    private fun commitIndex(index: Int) {
        if (currentIndex == index) return
        currentIndex = index
        applyOffsets()
        onPageChanged?.invoke(index)
    }

    // ---------------------------------------------------------------- 速度

    private fun startVelocityTracker(event: MotionEvent) {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.clear()
        tracker.addMovement(event)
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onDetachedFromWindow() {
        stopSettleAnimation()
        recycleVelocityTracker()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** 横向位移要大于纵向位移的 1.5 倍才算翻页，否则把事件让给竖向滚动。 */
        const val HORIZONTAL_BIAS = 1.5f

        /** 速度超过 900px/s 就按方向翻页（车机上手指甩动普遍比手机快）。 */
        const val FLING_VELOCITY_PX = 900f

        /** 慢速拖动时翻页所需的行程比例（半页）。 */
        const val FLIP_RATIO = 0.5f

        const val SETTLE_DURATION_MS = 200L

        /** 小于这个差值就不起动画，直接落位。 */
        const val SETTLE_EPSILON = 0.001f
    }
}
