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

    /**
     * 面板内容的高度上限：可用屏高 86% 与「可用屏高 − 24dp」取小。
     *
     * 5.7.1 修车机「只露一条标题」：`displayMetrics.heightPixels` 是**整块物理屏**，
     * 在 edge-to-edge + 车机底部空调栏 / 顶部状态条的场景里，用它算出的 `maxHeight`
     * 会大于真实可用区，sheet 底部被系统条吃掉。改用 `configuration.screenHeightDp`
     * —— 那是系统给当前应用窗口的可用高度（已扣掉系统装饰），再和 heightPixels 取小。
     */
    fun maxContentHeightPx(context: Context): Int {
        val metrics = context.resources.displayMetrics
        val physicalPx = metrics.heightPixels
        val availablePx = (context.resources.configuration.screenHeightDp * metrics.density).toInt()
            .takeIf { it > 0 } ?: physicalPx
        val screenPx = minOf(physicalPx, availablePx)
        val marginPx = (MARGIN_DP * metrics.density).toInt()
        return minOf((screenPx * MAX_HEIGHT_RATIO).toInt(), screenPx - marginPx)
            .coerceAtLeast(MIN_CONTENT_PX)
    }

    /**
     * 建一个带高度上限、且**全宽**的 BottomSheet。
     *
     * @param content 面板根布局（ViewBinding 的 root）
     * @param scrollable 内容是否需要整块可滚（列表型面板传 false）
     * @param onShown show 之后要做的收尾工作（例如反算 RecyclerView 高度）。
     *   必须在 show 之后做：`BottomSheetDialog` 在首次 measure 时用 `isFitToContents`
     *   把 expandedOffset 定死，show 之前改子 View 高度不会触发重算 —— 这正是
     *   车机上「队列面板只剩 35px 标题条」的主因。
     */
    fun build(
        context: Context,
        content: View,
        scrollable: Boolean,
        onShown: ((FrameLayout) -> Unit)? = null,
    ): BottomSheetDialog {
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
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            // BottomSheetDialog 的 window 在横屏 / 车机 ROM 上会收敛到内容宽，
            // 表现为「面板只有半屏宽」。显式设成 MATCH_PARENT 才与面板 XML 的
            // match_parent 根一致（根本来就是 match_parent，问题出在 window）。
            val params = sheet.layoutParams
            if (params != null && params.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                sheet.layoutParams = params
            }
            dialog.behavior.maxHeight = maxContentHeightPx(context)
            onShown?.invoke(sheet)
            // show 之后改过子 View 高度，必须让 sheet 重新走一遍 measure/layout，
            // 否则 BottomSheetBehavior 还用着首帧算出来的 expandedOffset。
            sheet.requestLayout()
        }
        return dialog
    }

    /**
     * 给"固定高度列表"型面板反算列表高度：面板上限 − 头部实测高 − 底部内边距，下限 160dp。
     *
     * **必须在面板 show 之后调用**（由 [build] 的 `onShown` 钩子承载）。旧实现在
     * `dialog.show()` 之前注册 `doOnLayout`，view 还没 attach、回调延迟到首帧之后；
     * BottomSheet 首次 measure 时列表仍是 XML 的固定高度，`isFitToContents` 按这个
     * 首帧把 expandedOffset 定死，`skipCollapsed` 又不让折叠态重试 —— 回调里再改
     * `layoutParams.height` 也回天乏术，车机上就只剩一条标题。
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
                list.requestLayout()
                sheetRoot.requestLayout()
            }
        }
    }

    /** 面板内容最多占屏高的 86%。 */
    private const val MAX_HEIGHT_RATIO = 0.86f

    /** 面板与屏幕边缘至少留 24dp，避免贴边（车机圆角屏会切到内容）。 */
    private const val MARGIN_DP = 24f

    /** 列表最少给 160dp：大约 3 行，再少就不像"可滚动列表"了。 */
    private const val MIN_LIST_DP = 160f

    /** 面板可用高的绝对下限，避免某些 ROM 报出异常小的 screenHeightDp 把面板压成 0。 */
    private const val MIN_CONTENT_PX = 240
}
