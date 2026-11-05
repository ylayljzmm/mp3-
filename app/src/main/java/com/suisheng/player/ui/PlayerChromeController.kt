package com.suisheng.player.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.suisheng.player.databinding.FragmentPlayerBinding

/**
 * 播放页四态显隐控制器（`design/05 §7`）。
 *
 * | 状态 | 进入方式 | 左栏 | 右栏 | 顶栏 |
 * |---|---|---|---|---|
 * | [State.DUO] 双栏 | 冷启动 / 切歌 | 完整六段 | 歌词 | 显示，8s 后自动隐藏 |
 * | [State.IMMERSIVE] 沉浸 | 无操作 8s | 唱片 + 歌名（进度/控件/功能行淡出） | 歌词 | 隐藏 |
 * | [State.LYRICS_FULL] 全屏歌词 | 点封面 / 点功能行「歌词」 | GONE | 占满 | 返回箭头 |
 * | [State.COVER_ONLY] 仅封面 | 长按封面 800ms | 只有唱片，放大到 340dp | GONE | 隐藏 |
 *
 * **零位移**（§7 末段）：沉浸态只改 `alpha` + `translationY(10dp)`，
 * 从不改 `visibility` 到 GONE、也不参与 layout —— 所以唱片与歌名的 Y 坐标变化恒为 0。
 * 只有三个「会消失的块」（进度 / 传输控件 / 功能行）参与淡出，它们各自保留布局空间。
 *
 * 竖屏（[singleColumn] = true）没有右栏位置：双栏态只显示中轴，歌词只在全屏歌词态出现。
 */
class PlayerChromeController(
    private val binding: FragmentPlayerBinding,
    private val singleColumn: Boolean,
    private val onStateChanged: (State) -> Unit,
) {

    enum class State {
        DUO,
        IMMERSIVE,
        LYRICS_FULL,
        COVER_ONLY,
    }

    private val handler = Handler(Looper.getMainLooper())
    private val idleAction = Runnable { setState(State.IMMERSIVE) }

    /** 只有这三块会淡出（§7：唱片与歌名永远可见）。 */
    private val fadeBlocks: List<View> = listOf(
        binding.progressRow,
        binding.transportRow,
        binding.actionRow,
    )

    var state: State = State.DUO
        private set

    /** 上一次「非全屏歌词」的状态，用来从全屏歌词退回去（点返回箭头 / 再点一次歌词）。 */
    private var stateBeforeFullLyrics: State = State.DUO

    init {
        // 首帧就是双栏态
        render(State.DUO)
        scheduleIdle()
    }

    /** 唯一的对外入口。 */
    fun setState(next: State) {
        if (next == state) {
            // 重复点「歌词」时也要能重置计时，否则停在长期隐藏的顶栏上
            if (next != State.IMMERSIVE) scheduleIdle()
            return
        }
        if (next == State.LYRICS_FULL && state != State.LYRICS_FULL) {
            stateBeforeFullLyrics = state
        }
        render(next)
        if (next == State.DUO) scheduleIdle() else cancelIdle()
        onStateChanged(next)
    }

    /** 触摸 / 按键 / 方控 / 切歌都该调它：回到双栏态并重新计时。 */
    fun onUserInteraction() {
        if (state == State.IMMERSIVE) {
            render(State.DUO)
            onStateChanged(State.DUO)
        }
        if (state == State.DUO) scheduleIdle()
    }

    /** 从全屏歌词退回上一个状态（返回箭头用）。 */
    fun exitFullLyrics() {
        setState(stateBeforeFullLyrics.takeIf { it != State.LYRICS_FULL } ?: State.DUO)
    }

    fun toggleFullLyrics() {
        if (state == State.LYRICS_FULL) exitFullLyrics() else setState(State.LYRICS_FULL)
    }

    fun toggleCoverOnly() {
        if (state == State.COVER_ONLY) setState(State.DUO) else setState(State.COVER_ONLY)
    }

    /** View 销毁前必须调，否则 8s 的回调会打到已销毁的 binding 上。 */
    fun detach() {
        cancelIdle()
    }

    // ---------------------------------------------------------------- 内部

    private fun scheduleIdle() {
        cancelIdle()
        handler.postDelayed(idleAction, IDLE_DELAY_MS)
    }

    private fun cancelIdle() {
        handler.removeCallbacks(idleAction)
    }

    private fun render(next: State) {
        state = next
        val full = next == State.LYRICS_FULL
        val coverOnly = next == State.COVER_ONLY
        val immersive = next == State.IMMERSIVE

        // 顶栏：全屏歌词时换成返回箭头；沉浸与仅封面整体淡出
        binding.topBar.isInvisible = immersive || coverOnly
        binding.btnDrawerMenu.isVisible = !full
        binding.btnLyricsBack.isVisible = full
        binding.topBarActions.isVisible = !full

        // 中轴与歌词
        binding.axisColumn.isVisible = !full
        binding.scrollLyricView.isVisible = when {
            coverOnly -> false
            singleColumn -> full
            else -> true
        }

        // 三块淡出：alpha + translationY，不碰 layout
        val hiddenAlpha = if (immersive || coverOnly) 0f else 1f
        val shift = if (hiddenAlpha == 0f) dp(10) else 0f
        fadeBlocks.forEach { block ->
            block.animate()
                .alpha(hiddenAlpha)
                .translationY(shift)
                .setDuration(FADE_MS)
                .start()
            // alpha 为 0 的 View 仍然吃触摸，必须同时关掉可点
            block.isClickable = hiddenAlpha > 0f
            block.isEnabled = hiddenAlpha > 0f
        }

        // 仅封面：唱片放大到 340dp。用 scale 而不是改尺寸，避免重新布局引起位移动画
        val coverScale = if (coverOnly) {
            COVER_ONLY_TARGET_DP / axisVinylDp()
        } else {
            1f
        }
        binding.vinylRecord.animate()
            .scaleX(coverScale)
            .scaleY(coverScale)
            .setDuration(FADE_MS)
            .start()
    }

    private fun axisVinylDp(): Float =
        binding.root.resources.getDimension(com.suisheng.player.R.dimen.vinyl_size) /
            binding.root.resources.displayMetrics.density

    private fun dp(value: Int): Float =
        value * binding.root.resources.displayMetrics.density

    private companion object {
        /** 8 秒无操作进沉浸（`05 §12 Q1`）。 */
        const val IDLE_DELAY_MS = 8_000L

        /** 淡入淡出 180ms：比 250ms 更跟手，车机上不拖泥带水。 */
        const val FADE_MS = 180L

        /** 仅封面态的唱片直径（`05 §7` 第四行）。 */
        const val COVER_ONLY_TARGET_DP = 340f
    }
}
