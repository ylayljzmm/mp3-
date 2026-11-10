package com.suisheng.player.ui

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 沉浸式与系统栏安全区。
 *
 * ## 6.0 修掉的两个真实问题
 *
 * ① **手机被整体抬高 60dp**。旧实现在 `activity_main.xml` 里塞了一个
 *    `@dimen/car_dock_safe_area` = 60dp 的空 View 当安全区 —— 那是照着车机 Dock
 *    量出来的数字，手机上就是一条谁也解释不了的空白。现在改用真实的 window insets。
 *
 * ② **内容跑到系统栏底下**。旧实现加了 `FLAG_LAYOUT_NO_LIMITS`，把所有系统栏的
 *    限制彻底放开，同时又没有任何人补 padding —— 手机竖屏时状态栏会压在页头上、
 *    导航栏会压住迷你条。这个 flag 已经删除；现在用
 *    [WindowCompat.setDecorFitsSystemWindows] `false` + [applyInsets] 自己补。
 *
 * ## 车机与手机的区别
 *
 * 车机（`UI_MODE_TYPE_CAR` 或 `FEATURE_AUTOMOTIVE`）**隐藏状态栏**：车机的顶栏
 * 是一条很矮的系统条（有的机型只有时钟），留着只会挤占本来就紧张的竖向空间。
 * 手机则**显示**状态栏 —— 用户要看时间、电量、通知图标，藏起来只会被当成 bug。
 * 两边的导航栏都不隐藏：车机 Dock 上有返回键，手机手势条需要可见。
 */
object ImmersiveHelper {

    /** 车机 UI（Automotive 或车机 UI mode）。 */
    fun isCarUi(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (uiMode == Configuration.UI_MODE_TYPE_CAR) return true
        return runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        }.getOrDefault(false)
    }

    /**
     * 窗口级设置。**可以在 onCreate 里、setContentView 之前调**，也适合在
     * `onResume` / `onWindowFocusChanged` 里重复调（幂等）。
     */
    fun apply(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 透明栏：内容按 inset 让开位置，让出来的区域露出页面自己的背景，视觉上连成一片
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // API 29+ 系统会给浅色底上的导航栏强加一条深色对比带（页面底部出现黑缝）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (isCarUi(activity)) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            // 手机上必须显式 show：否则从车机模式（或从全屏视频那种调用方）退回来时
            // 状态栏会一直藏着，用户以为系统坏了。
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    /**
     * 把系统栏 / 刘海的安全区变成每个 root 的 padding。
     *
     * 用 `setOnApplyWindowInsetsListener` 而不是 `fitsSystemWindows="true"`：
     * 后者只会把 inset 加到 padding 上，且会被 DrawerLayout 的层叠结构吃掉，
     * 抽屉面板是独立一层，永远拿不到真正需要的那份 inset。
     *
     * 返回 [WindowInsetsCompat.CONSUMED]：安全区已经由这里消费掉，
     * 不要再往下传给子 View，否则页面内部会二次偏移。
     *
     * 会**覆盖**root 原有的 padding（两个 root 原本都没有 padding），
     * 所以不要拿它去处理自带内边距的业务 View。
     *
     * @param extraTopPaddingPx 车机专用兜底：隐藏系统状态栏之后，部分车机 ROM 的
     *   `getInsets(statusBars()).top` 会返回 0，但那条系统条仍是系统 overlay、
     *   **物理占在屏幕顶端**。此时从 y=0 起绘的抽屉第一项会被压住裁切。
     *   传入一个最小顶部留白，取 `max(真实 inset, 该值)`；只在车机形态下生效，
     *   手机状态栏正常显示、inset 准确，不受影响。
     * @param extraBottomPaddingPx 车机专用兜底：车机底部的系统 Dock（空调 / 返回 /
     *   主页那条）同样是 overlay，隐藏状态栏后 `getInsets(...).bottom` 常返回 0，
     *   抽屉底部固定三格（扫描 / 计数 / 主题）会被 Dock 压住显示不全。
     *   取 `max(真实 inset, 该值)`，**只在车机形态、且传入该参数的 root 上生效**。
     *   注意不能像 5.6.0 之前那样在布局里塞一个「底部 60dp 空 View」：那会把
     *   手机整页一起抬高（见 `values/dimens.xml` 的 `car_dock_safe_area` 记录）。
     *   这里用 padding + 车机判定，只影响抽屉这一层，contentColumn 不传该参数。
     */
    fun applyInsets(
        vararg roots: View,
        extraTopPaddingPx: Int = 0,
        extraBottomPaddingPx: Int = 0,
    ) {
        roots.forEach { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                val car = isCarUi(view.context)
                val top = if (extraTopPaddingPx > 0 && car) {
                    maxOf(bars.top, extraTopPaddingPx)
                } else {
                    bars.top
                }
                val bottom = if (extraBottomPaddingPx > 0 && car) {
                    maxOf(bars.bottom, extraBottomPaddingPx)
                } else {
                    bars.bottom
                }
                view.setPadding(bars.left, top, bars.right, bottom)
                WindowInsetsCompat.CONSUMED
            }
            // listener 挂上之后必须主动要一次：窗口已经分发过一轮 inset 时，
            // 不 request 就要等到下一次系统栏变化（旋转屏幕）才生效。
            ViewCompat.requestApplyInsets(root)
        }
    }

    /**
     * 状态栏 / 导航栏图标明暗。
     *
     * @param lightBackground true = 背景亮 → 图标要暗（`isAppearanceLight* = true`）
     */
    fun applyBarAppearance(activity: Activity, lightBackground: Boolean) {
        runCatching {
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = lightBackground
                // 注意是 NavigationBar**s**（复数）：androidx 的 setter 叫
                // setAppearanceLightNavigationBars，少写一个 s 编译不过。
                isAppearanceLightNavigationBars = lightBackground
            }
        }
    }
}
