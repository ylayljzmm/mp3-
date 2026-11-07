package com.suisheng.player.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 底部面板（BottomSheet）的统一骨架。
 *
 * 抽出来的原因：四个面板（音效 / 歌曲信息 / 更多 / 队列）原来各自
 * `BottomSheetDialog(context).setContentView(binding.root)`，谁都没设上限，
 * 而 `BottomSheetDialog` **不会**帮你裁 `wrap_content` 的内容 ——
 * 车机上屏高只有 540 ~ 600dp，内容一超过屏高，标题和底部按钮同时被顶出屏幕。
 *
 * 两条接入方式：
 *  - 内容本身不高、只是"可能超"（音效 / 歌曲信息 / 更多）→ [build] 传 `scrollable = true`，
 *    内部套一层 [NestedScrollView]，超了就能滚，不超就正好贴着内容高度；
 *  - 内容里有一个自己会滚的 `RecyclerView`（队列）→ `scrollable = false`，
 *    再用 [capFixedListHeight] 给列表反算高度。**绝不能**给 RecyclerView 套
 *    `NestedScrollView`：那会让它一次性 measure 全部 item，几百首队列直接卡死。
 */
internal object PanelHost {

    /** 面板内容的高度上限：屏高 86% 与「屏高 − 24dp」取小。 */
    fun maxContentHeightPx(context: Context): Int {
        val metrics = context.resources.displayMetrics
        val screenPx = metrics.heightPixels
        val marginPx = (MARGIN_DP * metrics.density).toInt()
        return minOf((screenPx * MAX_HEIGHT_RATIO).toInt(), screenPx - marginPx)
    }

    /**
     * 建一个带高度上限的 BottomSheet。
     *
     * @param content 面板根布局（ViewBinding 的 root）
     * @param scrollable 内容是否需要整块可滚（列表型面板传 false）
     */
    fun build(context: Context, content: View, scrollable: Boolean): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        val host: View = if (scrollable) {
            NestedScrollView(context).apply {
                // 内容比上限矮时按内容高度收口，不留一片空白
                isFillViewport = false
                clipToPadding = false
                addView(
                    content,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        } else {
            content
        }
        dialog.setContentView(host)
        dialog.behavior.apply {
            isFitToContents = true
            skipCollapsed = true
            maxHeight = maxContentHeightPx(context)
        }
        return dialog
    }

    /**
     * 给"固定高度列表"型面板反算列表高度：面板上限 − 头部高 − 底部内边距，下限 160dp。
     *
     * 必须等头部真正测量完（`doOnLayout`）才能算：头部是 `wrap_content` 且带工具按钮，
     * 面板还没 show 时 `header.height` 恒为 0，用裸 `post` 会在 attach 阶段抢跑拿到 0。
     */
    fun capFixedListHeight(context: Context, list: RecyclerView, header: View, sheetRoot: View) {
        val capPx = maxContentHeightPx(context)
        val minPx = (MIN_LIST_DP * context.resources.displayMetrics.density).toInt()
        header.doOnLayout { head ->
            val available = (capPx - head.height - sheetRoot.paddingBottom).coerceAtLeast(minPx)
            val params = list.layoutParams ?: return@doOnLayout
            if (params.height != available) {
                params.height = available
                list.layoutParams = params
            }
        }
    }

    /** 面板内容最多占屏高的 86%。 */
    private const val MAX_HEIGHT_RATIO = 0.86f

    /** 面板与屏幕边缘至少留 24dp，避免贴边（车机圆角屏会切到内容）。 */
    private const val MARGIN_DP = 24f

    /** 列表最少给 160dp：大约 3 行，再少就不像"可滚动列表"了。 */
    private const val MIN_LIST_DP = 160f
}
