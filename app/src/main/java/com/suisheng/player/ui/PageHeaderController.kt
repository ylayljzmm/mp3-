package com.suisheng.player.ui

import android.app.Activity
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.suisheng.player.MainActivity
import com.suisheng.player.R

/**
 * 全局页头控制器。
 *
 * 侧边栏改版后，每个二级页共用 [com.suisheng.player.R.layout.activity_main] 里的
 * `pageHeader`：左边一格是「返回 / 菜单」二选一，中间是标题（可带一行副标题），
 * 右边预留一个动作位。页面自己不再有标题条 —— 之前每个 `fragment_*.xml` 顶部都挂着
 * 一条 `MaterialToolbar` 或等高自定义条，同一个页面里会出现「页头一个标题 + 页面内
 * 又一个标题」的重复。
 *
 * 这个类只做一件事件：把「当前目的地 + 能不能返回」翻译成这一条页头的可视状态。
 * 它不持有导航逻辑 —— 返回、开抽屉都通过构造参数回传给宿主。
 *
 * @param activity 宿主 Activity，只用来 `getString(labelRes)`
 * @param root `pageHeader` 这个 View
 * @param onBack 左上角显示返回箭头时，点击做什么
 * @param onMenu 左上角显示汉堡键时，点击做什么（打开抽屉）
 */
class PageHeaderController(
    private val activity: Activity,
    private val root: View,
    private val onBack: () -> Unit,
    private val onMenu: () -> Unit,
) {

    private val title: TextView = root.findViewById(R.id.pageTitle)
    private val subtitle: TextView = root.findViewById(R.id.pageSubtitle)
    private val backButton: ImageButton = root.findViewById(R.id.btnPageBack)
    private val menuButton: ImageButton = root.findViewById(R.id.btnPageMenu)
    private val actionButton: ImageButton = root.findViewById(R.id.btnPageAction)

    init {
        backButton.setOnClickListener { onBack() }
        menuButton.setOnClickListener { onMenu() }
    }

    /**
     * 目的地变了：换标题、复位副标题与动作位、重算左上角按钮。
     *
     * 每次 [bind] 都无条件把副标题与右侧动作位复位 —— 上一个页面留下的「共 12 首」
     * 或一个动作图标，如果不在切页时清掉，会挂在新页面的标题下面，看起来像是新页面的数据。
     *
     * **播放页与主题页整条隐藏**：这两页各自带一条完整的顶栏（返回 + 自己 27sp 标题），
     * 叠上全局页头就成了同一个页面两个标题。主题页的顶栏在 `fragment_theme.xml` 里，
     * 本期不动它，所以规则放在这里，由页头自己让位。
     *
     * @param destination 当前目的地
     * @param canGoBack true = 左上角显示返回箭头（还有上一页可退），false = 显示汉堡键
     */
    fun bind(destination: Destination, canGoBack: Boolean) {
        val hidden = destination == Destination.PLAYER || destination == Destination.THEME
        root.visibility = if (hidden) View.GONE else View.VISIBLE
        title.text = activity.getString(destination.labelRes)
        setSubtitle(null)
        setAction(0, null, null)
        setCanGoBack(canGoBack)
    }

    /**
     * 只改左上角按钮，不动标题。
     *
     * 页面内部进入/退出子层级（歌单详情、歌手详情、文件夹下钻）时用这个：
     * 标题还是那个目的地，但「退一层」的语义要立刻反映到箭头上。
     */
    fun setCanGoBack(canGoBack: Boolean) {
        backButton.visibility = if (canGoBack) View.VISIBLE else View.GONE
        menuButton.visibility = if (canGoBack) View.GONE else View.VISIBLE
    }

    /** 副标题：传 null 或空串即隐藏整行（标题保持垂直居中）。 */
    fun setSubtitle(text: CharSequence?) {
        if (text.isNullOrEmpty()) {
            subtitle.text = null
            subtitle.visibility = View.GONE
        } else {
            subtitle.text = text
            subtitle.visibility = View.VISIBLE
        }
    }

    /**
     * 右侧动作位。
     *
     * @param iconRes 图标；`0` 表示清空（同时隐藏并摘掉点击）
     * @param description 无障碍描述
     * @param onClick 点击回调；为 null 时同样清空
     */
    fun setAction(iconRes: Int, description: CharSequence?, onClick: (() -> Unit)?) {
        if (iconRes == 0 || onClick == null) {
            actionButton.setOnClickListener(null)
            actionButton.visibility = View.GONE
            return
        }
        actionButton.setImageResource(iconRes)
        actionButton.contentDescription = description
        actionButton.setOnClickListener { onClick() }
        actionButton.visibility = View.VISIBLE
    }
}

/**
 * 页面内部还有一层时的返回语义。
 *
 * 三个页面会实现它：歌曲页（进了某个歌手）、在线页（进了歌单详情 / 「我的」/ 搜索结果）、
 * 文件夹页（下钻到子目录）。没有这一层的时候，页头的返回键在用户眼里只有一种意思
 * ——「退一层」，可实际上它退的是**目的地**。在页面内部状态下先让页面自己消费返回，
 * 语义才对得上。
 *
 * 实现方在进入/退出子层级后必须调用 [syncPageHeaderBack]，让页头知道该显示箭头还是汉堡。
 */
interface PageBackHandler {
    /** 页面内部是否还有可退的一层。 */
    fun hasInnerLevel(): Boolean

    /** 退掉内层；返回 true 表示已消费这次返回。 */
    fun handlePageBack(): Boolean
}

/**
 * 把副标题交给全局页头。
 *
 * 只有 `MainActivity` 持有页头，所以这里做一次集中转换：页面出现的地方不需要 import
 * 具体的 Activity 类型，将来换宿主也只改这一个函数。
 */
internal fun Fragment.setPageSubtitle(text: CharSequence?) {
    (activity as? MainActivity)?.setPageSubtitle(text)
}

/** 页面内部层级变化后，请宿主重算页头左上角显示「返回」还是「汉堡」。 */
internal fun Fragment.syncPageHeaderBack() {
    (activity as? MainActivity)?.refreshPageHeaderBack()
}
