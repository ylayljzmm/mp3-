package com.suisheng.player.ui

import androidx.core.view.isVisible
import com.suisheng.player.databinding.FragmentPlayerBinding

/**
 * 播放页两态显隐控制器（`design/05 §7`）。
 *
 * | 状态 | 进入方式 | 左栏 | 右栏 | 顶栏 |
 * |---|---|---|---|---|
 * | [State.DUO] 双栏 | 冷启动 / 切歌 / 返回 | 完整六段 | 歌词（竖屏下隐藏） | 显示 |
 * | [State.LYRICS_FULL] 全屏歌词 | 入口已删除，状态保留但当前不可达 | GONE | 占满 | 返回箭头 |
 *
 * 5.5.0 起**只剩这两态**：8 秒无操作自动隐藏的沉浸态、长按封面放大的仅封面态都已删除
 * （车机上"控件自己消失"会被当成失灵，且两态都需要额外的触摸计时器与淡出动画）。
 * 因此本类不再持有 Handler、不再做任何 `alpha` / `translationY` 动画：
 * **两态下进度行 / 传输行 / 功能行恒定可见**，也就没有"控件淡出但还吃触摸"的问题。
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
        LYRICS_FULL,
    }

    var state: State = State.DUO
        private set

    /** 上一次「非全屏歌词」的状态，用来从全屏歌词退回去（点返回箭头）。 */
    private var stateBeforeFullLyrics: State = State.DUO

    init {
        // 首帧就是双栏态
        render(State.DUO)
    }

    /** 唯一的对外入口。 */
    fun setState(next: State) {
        if (next == state) return
        if (next == State.LYRICS_FULL && state != State.LYRICS_FULL) {
            stateBeforeFullLyrics = state
        }
        render(next)
        onStateChanged(next)
    }

    /** 从全屏歌词退回上一个状态（返回箭头用）。 */
    fun exitFullLyrics() {
        setState(stateBeforeFullLyrics.takeIf { it != State.LYRICS_FULL } ?: State.DUO)
    }

    fun toggleFullLyrics() {
        if (state == State.LYRICS_FULL) exitFullLyrics() else setState(State.LYRICS_FULL)
    }

    // ---------------------------------------------------------------- 内部

    private fun render(next: State) {
        state = next
        val full = next == State.LYRICS_FULL

        // 顶栏：全屏歌词时左侧换成返回箭头，右侧动作位收起
        binding.btnDrawerMenu.isVisible = !full
        binding.btnLyricsBack.isVisible = full
        binding.topBarActions.isVisible = !full

        // 中轴与歌词
        binding.axisColumn.isVisible = !full
        binding.scrollLyricView.isVisible = when {
            singleColumn -> full
            else -> true
        }
    }
}
