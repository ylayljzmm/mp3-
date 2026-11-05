package com.suisheng.player.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.MaterialColors
import com.suisheng.player.BuildConfig
import com.suisheng.player.R
import com.suisheng.player.databinding.ActivityMainBinding
import com.suisheng.player.databinding.ItemDrawerEntryBinding
import com.suisheng.player.databinding.ItemDrawerGroupBinding
import com.suisheng.player.model.LibraryGrouper
import com.suisheng.player.model.Song
import java.io.File

/**
 * 抽屉面板的渲染器（`design/04 §3.5`）。
 *
 * 面板刻意**不用 `NavigationView`**：`NavigationView` 的菜单只能从 `res/menu` 静态声明，
 * 而我们的条目要在右侧挂「共 N 首」这类运行时计数，还要做「已落地/未落地」两态，
 * 与其和它的私有样式搏斗，不如用一个 [android.widget.LinearLayout] 自己填。
 *
 * 8 条条目 + 3 个分组标题在 [bind] 里一次性 inflate 出来（总共 11 个很轻的 View），
 * 之后只改颜色与文字，不重建。
 *
 * @param activity 宿主 Activity，用于取资源与 string
 * @param binding `activity_main.xml` 的 ViewBinding
 * @param onSelect 条目被点击（未落地的条目也会回调，由调用方决定提示）
 * @param onScan 底部「扫描音乐」药丸被点击
 * @param onTheme 底部「主题」药丸被点击
 */
class DrawerPanelController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val onSelect: (Destination) -> Unit,
    private val onScan: () -> Unit,
    private val onTheme: () -> Unit,
) {

    /** 一条条目里需要换色的 4 个部件。 */
    private class Row(
        val highlight: View,
        val icon: ImageView,
        val label: TextView,
        val count: TextView,
    )

    private val rows = LinkedHashMap<Destination, Row>()
    private var selected: Destination = Destination.HOME
    private var totalSongs = 0
    private var scanning = false

    /** 建面板：背景、头部、8 条条目、底部三格。只应调用一次（Activity 每次重建各调一次）。 */
    fun bind() {
        binding.drawerPanelBody.background = panelBackground()
        binding.drawerHeaderSubtitle.text = activity.getString(
            R.string.drawer_subtitle,
            BuildConfig.VERSION_NAME,
        )
        binding.btnDrawerScan.setOnClickListener { onScan() }
        binding.btnDrawerTheme.setOnClickListener { onTheme() }

        binding.drawerMenuContainer.removeAllViews()
        rows.clear()
        val inflater = LayoutInflater.from(activity)
        var renderedGroup: DrawerGroup? = null
        Destination.entries.filter { it.shownInDrawer }.forEach { destination ->
            if (destination.group != renderedGroup) {
                renderedGroup = destination.group
                val group = ItemDrawerGroupBinding.inflate(inflater, binding.drawerMenuContainer, false)
                group.root.text = activity.getString(destination.group.titleRes)
                binding.drawerMenuContainer.addView(group.root)
            }
            val entry = ItemDrawerEntryBinding.inflate(inflater, binding.drawerMenuContainer, false)
            entry.itemIcon.setImageResource(destination.iconRes)
            entry.itemLabel.text = activity.getString(destination.labelRes)
            entry.itemCount.visibility = View.GONE
            entry.root.setOnClickListener { onSelect(destination) }
            // 未落地的条目保留点击（否则 Toast 永远不触发，用户点了完全没反馈），
            // 只用 alpha 0.38 表达「还不可用」。
            entry.root.alpha = if (destination.available) 1f else UNAVAILABLE_ALPHA
            rows[destination] = Row(
                highlight = entry.itemHighlight,
                icon = entry.itemIcon,
                label = entry.itemLabel,
                count = entry.itemCount,
            )
            binding.drawerMenuContainer.addView(entry.root)
        }
        applySelection(selected)
        renderBottomBar()
    }

    /** 把某个条目置为选中态（其余回到未选中）。 */
    fun select(destination: Destination) {
        selected = destination
        applySelection(destination)
    }

    /**
     * 曲库变化后刷新计数。
     *
     * 计数缺失时的规矩是**宁可不显示，也不显示 0**：曲库为空时四条都不挂数字。
     * 四个数字都来自 [LibraryGrouper]，与四个分组页里看到的分组数完全同源，
     * 不会出现「抽屉说 12 张专辑、点进去 11 张」这种自相矛盾。
     */
    fun updateLibrary(songs: List<Song>) {
        totalSongs = songs.size
        val unknownAlbum = activity.getString(R.string.library_unknown_album)
        val unknownArtist = activity.getString(R.string.library_unknown_artist)
        val albums = LibraryGrouper.byAlbum(songs, unknownAlbum).size
        val artists = LibraryGrouper.byArtist(songs, unknownArtist).size
        val folders = songs.map { folderOf(it.path) }.toSet().size
        setCount(Destination.SONGS, totalSongs.takeIf { it > 0 })
        setCount(Destination.ALBUMS, albums.takeIf { it > 0 })
        setCount(Destination.ARTISTS, artists.takeIf { it > 0 })
        setCount(Destination.FOLDERS, folders.takeIf { it > 0 })
        renderBottomBar()
    }

    /** 扫描开关：底部中间格在「扫描中…」与「共 N 首」之间切换。 */
    fun setScanning(value: Boolean) {
        scanning = value
        renderBottomBar()
    }

    private fun applySelection(destination: Destination) {
        val accent = color(com.google.android.material.R.attr.colorPrimaryContainer)
        val onAccent = color(com.google.android.material.R.attr.colorOnPrimaryContainer)
        val idleLabel = color(com.google.android.material.R.attr.colorOnSurface)
        val idleIcon = color(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val pill = pillBackground(accent)
        rows.forEach { (candidate, row) ->
            val isSelected = candidate == destination
            row.highlight.background = if (isSelected) pill else null
            val tint = if (isSelected) onAccent else idleIcon
            row.icon.imageTintList = ColorStateList.valueOf(tint)
            row.label.setTextColor(if (isSelected) onAccent else idleLabel)
            row.count.setTextColor(if (isSelected) onAccent else idleIcon)
        }
    }

    private fun setCount(destination: Destination, value: Int?) {
        val count = rows[destination]?.count ?: return
        if (value == null) {
            count.text = ""
            count.visibility = View.GONE
        } else {
            count.text = value.toString()
            count.visibility = View.VISIBLE
        }
    }

    private fun renderBottomBar() {
        binding.drawerCount.text = if (scanning) {
            activity.getString(R.string.drawer_scanning)
        } else {
            activity.getString(R.string.song_count, totalSongs)
        }
    }

    /** 面板底：`?attr/colorSurfaceContainer`，只有右上/右下两个 28dp 圆角，左侧贴边直角。 */
    private fun panelBackground(): GradientDrawable {
        val radius = activity.resources.getDimension(R.dimen.drawer_corner_radius)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color(com.google.android.material.R.attr.colorSurfaceContainer))
            // cornerRadii 顺序：左上 x, 左上 y, 右上 x, 右上 y, 右下 x, 右下 y, 左下 x, 左下 y
            setCornerRadii(floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f))
        }
    }

    /** 选中态药丸：四周已由布局的 8dp 内缩让出，这里只需要把圆角做成「full」。 */
    private fun pillBackground(fillColor: Int): GradientDrawable {
        val height = activity.resources.getDimension(R.dimen.drawer_item_height)
        val inset = activity.resources.getDimension(R.dimen.drawer_item_inset)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            setCornerRadius((height - inset * 2f) / 2f)
        }
    }

    /** 主题色一律走 `?attr/`，这样十套 accent × 明暗两版都自动正确。 */
    @ColorInt
    private fun color(@AttrRes attr: Int): Int = MaterialColors.getColor(binding.drawerPanel, attr)

    private fun folderOf(path: String): String = File(path).parent ?: path

    private companion object {
        /** 未落地条目的透明度（`design/04 §3.5` 的置灰值）。 */
        const val UNAVAILABLE_ALPHA = 0.38f
    }
}
