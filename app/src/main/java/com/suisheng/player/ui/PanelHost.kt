package com.suisheng.player.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 播放页各类面板的统一骨架。
 *
 * 抽出来的原因：四个面板（音效 / 歌曲信息 / 更多 / 队列）原来各自
 * `BottomSheetDialog(context).setContentView(binding.root)`，谁都没设上限，
 * 而 `BottomSheetDialog` **不会**帮你裁 `wrap_content` 的内容 ——
 * 车机上屏高只有 540 ~ 600dp，内容一超过屏高，标题和底部按钮同时被顶出屏幕。
 *
 * **5.7.2 起按形态分流**：车机上 `BottomSheetBehavior` 在 edge-to-edge +
 * 隐藏状态栏 + 底部系统 Dock 的组合里，首帧测量会把 `expandedOffset` 定死，
 * show 之后再改高度 / 宽度都来不及（5.7.1 那轮修复在真机上仍只露一行标题）。
 * 所以车机改用 [Dialog] 居中卡片，window 的宽高完全由这里显式设定，不依赖
 * Behavior 的测量时序；手机保持原贴底抽屉逻辑，行为不变。
 *
 * 两条接入方式：
 *  - 内容本身不高、只是「可能超」（音效 / 歌曲信息 / 更多）→ [build] 传 `scrollable = true`，
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
     * 建一个带高度上限的面板。
     *
     * 返回 [Dialog]：车机是居中卡片，手机是 [BottomSheetDialog]（后者是 Dialog 的子类）。
     * 所有调用方只用 `show()` / `dismiss()`，所以放宽返回类型不需要改任何面板代码。
     *
     * @param content 面板根布局（ViewBinding 的 root，或调用方自己搭好的滚动容器）
     * @param scrollable 内容是否需要整块可滚（列表型面板传 false）
     * @param onShown show 之后要做的收尾工作（例如反算 RecyclerView 高度）。
     *   参数是**这个面板实际可用的高度 px**：车机是主动测量出的窗口高度，
     *   手机是 BottomSheet 的高度上限。回调必须拿这个值去反算列表高度，
     *   不能自己再调 [maxContentHeightPx]，否则车机上列表会比窗口还高、底部又被裁。
     */
    fun build(
        context: Context,
        content: View,
        scrollable: Boolean,
        onShown: ((Int) -> Unit)? = null,
    ): Dialog {
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
        return if (ImmersiveHelper.isCarLikeUi(context)) {
            buildCarCard(context, host, onShown)
        } else {
            buildBottomSheet(context, host, onShown)
        }
    }

    /**
     * 车机：居中卡片。不用 [BottomSheetDialog]，窗口宽高全部显式设定，
     * 内容超高时把 window 锁到 [maxContentHeightPx]，让内部的
     * [NestedScrollView] / ScrollView / RecyclerView 自己滚。
     */
    private fun buildCarCard(context: Context, host: View, onShown: ((Int) -> Unit)?): Dialog {
        val widthPx = (context.resources.displayMetrics.widthPixels * CARD_WIDTH_RATIO).toInt()
        val cap = maxContentHeightPx(context)
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
            setContentView(host)
        }
        // **主动测量，一次成型**：这里不读 window / Behavior 的首帧结果，
        // 而是自己按「宽度确定、高度最多 cap」测一遍，直接把窗口设成实测高度。
        // 5.7.1 之所以失效，就是因为依赖了 BottomSheetBehavior 的 expandedOffset
        // 与 show 后的 measuredHeight，两者在车机 ROM 上都被首帧定死。
        host.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST),
        )
        val heightPx = minOf(host.measuredHeight, cap).coerceAtLeast(MIN_CONTENT_PX)
        dialog.window?.apply {
            setGravity(Gravity.CENTER)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(widthPx, heightPx)
        }
        dialog.setOnShowListener {
            // 队列面板在这里按「窗口实测高度」反算列表高度，保证列表 + 头部 +
            // 内边距刚好装进卡片；此时头部已 attach 且测量完，doOnLayout 立即回调。
            onShown?.invoke(heightPx)
            host.requestLayout()
        }
        return dialog
    }

    /**
     * 手机：贴底抽屉。同时补上 5.7.1 漏掉的两处兜底，作为**判定万一失效时的双保险**
     * —— 若 [ImmersiveHelper.isCarLikeUi] 在某种 ROM 上仍不命中、车机误走这条分支，
     * 也不会再退回「只露一行标题」：
     *  ① `window.setLayout(MATCH_PARENT, MATCH_PARENT)`：5.7.1 只改了 sheet 子 View
     *     的 layoutParams.width，Dialog **window** 本身仍可能收敛到内容宽；
     *  ② show 之后强制 `STATE_EXPANDED`：把可能停在 collapsed / peek 的 sheet 拉回展开态。
     *     **保留 `isFitToContents = true`** —— 若改成 false，内容很矮的面板也会占满
     *     上限高度留一片空白，手机端观感会倒退。
     * 手机端原本就正常，这两步对它是幂等的，不改变观感。
     */
    private fun buildBottomSheet(
        context: Context,
        host: View,
        onShown: ((Int) -> Unit)?,
    ): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
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
            // ① window 全宽（之前只改了 sheet，window 仍可能是 WRAP_CONTENT）
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            val params = sheet.layoutParams
            if (params != null && params.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                sheet.layoutParams = params
            }
            // ② 强制展开（保留 isFitToContents=true，内容矮时仍按内容收口）
            val cap = maxContentHeightPx(context)
            dialog.behavior.apply {
                skipCollapsed = true
                maxHeight = cap
                state = BottomSheetBehavior.STATE_EXPANDED
            }
            onShown?.invoke(cap)
            sheet.requestLayout()
        }
        return dialog
    }

    /**
     * 给「固定高度列表」型面板反算列表高度：面板上限 − 头部实测高 − 底部内边距，下限 160dp。
     *
     * **必须在面板 show 之后调用**（由 [build] 的 `onShown` 钩子承载）。旧实现在
     * `dialog.show()` 之前注册 `doOnLayout`，view 还没 attach、回调延迟到首帧之后；
     * BottomSheet 首次 measure 时列表仍是 XML 的固定高度，`isFitToContents` 按这个
     * 首帧把 expandedOffset 定死，`skipCollapsed` 又不让折叠态重试 —— 回调里再改
     * `layoutParams.height` 也回天乏术，车机上就只剩一条标题。
     */
    fun capFixedListHeight(
        context: Context,
        list: RecyclerView,
        header: View,
        sheetRoot: View,
        capPx: Int,
    ) {
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

    /** 列表最少给 160dp：大约 3 行，再少就不像「可滚动列表」了。 */
    private const val MIN_LIST_DP = 160f

    /** 面板可用高的绝对下限，避免某些 ROM 报出异常小的 screenHeightDp 把面板压成 0。 */
    private const val MIN_CONTENT_PX = 240

    /** 车机居中卡片宽度占屏宽比例：接近全宽但留出可读的左右边缘。 */
    private const val CARD_WIDTH_RATIO = 0.88f
}
