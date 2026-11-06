package com.suisheng.player.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.suisheng.player.R
import com.suisheng.player.data.HiddenFolderStore
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.databinding.FragmentGroupedLibraryBinding
import com.suisheng.player.model.LibraryGroup
import com.suisheng.player.model.LibraryGrouper
import com.suisheng.player.model.LibraryKind
import com.suisheng.player.model.Song
import java.io.File
import kotlin.math.max

/**
 * 专辑墙 / 艺术家 / 文件夹三个目的地共用的一页（`design/04 §3.5` 第 3–5 条）。
 *
 * 三页的差别只有两处：**怎么分组**（[Grouping]）与**格子还是行**（[LibraryGroupAdapter.Mode]），
 * 所以做成一个 Fragment + 一个适配器，而不是三份 90% 相同的代码。
 * 分组算法本身在 `LibraryGrouper`（纯 Kotlin，有单测），这里只负责画。
 *
 * 文件夹模式额外有：面包屑、下钻、「播放本文件夹」，以及**记忆上次层级**
 * （存绝对路径，下次从抽屉进来还在原处）。
 *
 * 页面本身没有标题条了：标题由全局页头按目的地给出（专辑 / 艺术家 / 文件夹），
 * 分组数改成页头的副标题（`setPageSubtitle`）。文件夹下钻属于「页面内部的层级」，
 * 所以本页实现 [PageBackHandler]：页头的返回键在子目录里先退一层目录，
 * 退到扫描根之后再走目的地栈。
 */
class GroupedLibraryFragment : Fragment(), PageBackHandler {

    /** 三种分组方式，与抽屉的 3 条条目一一对应。 */
    enum class Grouping(
        @StringRes val titleRes: Int,
        val adapterMode: LibraryGroupAdapter.Mode,
    ) {
        ALBUM(R.string.library_albums_title, LibraryGroupAdapter.Mode.GRID),
        ARTIST(R.string.library_artists_title, LibraryGroupAdapter.Mode.LIST),
        FOLDER(R.string.library_folders_title, LibraryGroupAdapter.Mode.LIST),
    }

    private var _binding: FragmentGroupedLibraryBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: SettingsRepository
    private lateinit var adapter: LibraryGroupAdapter

    /** 隐藏文件夹的存储。只在这一页用到，所以不放宿主。 */
    private val hiddenStore by lazy { HiddenFolderStore(requireContext()) }

    private var grouping: Grouping = Grouping.ALBUM
    private var rootDir: File? = null
    private var currentDir: File? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentGroupedLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsRepository(requireContext())
        grouping = runCatching {
            Grouping.valueOf(requireArguments().getString(ARG_GROUPING) ?: Grouping.ALBUM.name)
        }.getOrDefault(Grouping.ALBUM)

        binding.folderBar.visibility =
            if (grouping == Grouping.FOLDER) View.VISIBLE else View.GONE

        val coverLoader = AlbumCoverLoader(requireContext(), viewLifecycleOwner.lifecycleScope)
        adapter = LibraryGroupAdapter(
            mode = grouping.adapterMode,
            coverLoader = coverLoader.takeIf { grouping == Grouping.ALBUM },
            countText = { group -> getString(R.string.library_song_count, group.subtitle) },
            onClick = ::onGroupClicked,
            // 只有「文件夹」有长按语义：专辑/艺术家长按隐藏没有意义（同一张专辑散落在多个目录）
            onLongClick = if (grouping == Grouping.FOLDER) {
                { group: LibraryGroup -> onGroupLongClicked(group) }
            } else {
                null
            },
        )
        binding.groupList.adapter = adapter
        binding.groupList.layoutManager = if (grouping.adapterMode == LibraryGroupAdapter.Mode.GRID) {
            GridLayoutManager(requireContext(), albumSpanCount())
        } else {
            LinearLayoutManager(requireContext())
        }
        binding.btnPlayFolder.setOnClickListener { playCurrentFolder() }

        refresh()
    }

    override fun onDestroyView() {
        binding.groupList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    // ---------------------------------------------------------------- 渲染

    private fun refresh() {
        val songs = allSongs()
        val groups = when (grouping) {
            Grouping.ALBUM -> LibraryGrouper.byAlbum(songs, getString(R.string.library_unknown_album))
            Grouping.ARTIST -> LibraryGrouper.byArtist(songs, getString(R.string.library_unknown_artist))
            Grouping.FOLDER -> folderGroups(songs)
        }
        adapter.submit(groups)
        renderHeader(songs, groups)
    }

    /**
     * 宿主（`MainActivity`）在曲库变化后调用：扫描完成、隐藏了某个文件夹。
     *
     * 公共转发 [refresh] 而不是把 `refresh` 本身改成 public：重画只应在「数据真的变了」
     * 的时候由宿主推动，页面外部不该随手调。
     */
    fun refreshFromHost() {
        if (_binding == null) return
        refresh()
    }

    private fun folderGroups(songs: List<Song>): List<LibraryGroup> {
        val root = LibraryGrouper.commonRoot(songs)
        rootDir = root
        if (root == null) {
            currentDir = null
            binding.breadcrumbContainer.removeAllViews()
            return emptyList()
        }
        // 记忆上次层级：只有仍落在当前曲库根之下才复用，否则回到根
        val remembered = settings.folderBrowsePath
            ?.let { File(it) }
            ?.takeIf { LibraryGrouper.isAncestorOrSelf(root, it) }
        val dir = remembered ?: currentDir?.takeIf { LibraryGrouper.isAncestorOrSelf(root, it) } ?: root
        currentDir = dir
        settings.folderBrowsePath = dir.absolutePath
        renderBreadcrumb(root, dir)
        return LibraryGrouper.folderChildren(songs, dir)
    }

    private fun renderHeader(songs: List<Song>, groups: List<LibraryGroup>) {
        // 分组数从页面里那条 56dp 计数条搬到了页头副标题：页面只剩一处标题（页头的），
        // 数字却一个都没少。
        val countText = when (grouping) {
            Grouping.ALBUM -> getString(R.string.library_album_count, groups.size)

            Grouping.ARTIST -> getString(R.string.library_artist_count, groups.size)

            Grouping.FOLDER -> {
                val dir = currentDir
                val visible = if (dir == null) 0 else LibraryGrouper.songsUnder(songs, dir).size
                getString(R.string.library_song_count, visible)
            }
        }
        setPageSubtitle(countText)
        syncPageHeaderBack()

        val empty = groups.isEmpty()
        binding.groupList.visibility = if (empty) View.GONE else View.VISIBLE
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.btnPlayFolder.isEnabled = songs.isNotEmpty()
        binding.btnPlayFolder.alpha = if (songs.isEmpty()) DISABLED_ALPHA else 1f
        if (!empty) return

        // 「藏到一首不剩」三个页面都可能发生（把唯一的目录隐藏掉就会出现）。
        // 此时如果还说「还没扫描音乐」，用户会以为曲库被清空、跑去重新扫描 —— 统一换成隐藏相关的文案。
        if (songs.isEmpty() && hiddenStore.count() > 0) {
            binding.emptyTitle.setText(R.string.hidden_folder_all_hidden)
            binding.emptyHint.setText(R.string.hidden_folder_all_hidden_hint)
            return
        }

        // 三个页面的空态必须说得不一样：「没找到专辑」和「还没扫描音乐」是两种处境。
        // 文件夹再细分三档：整库没歌 / 有歌但没有可浏览的本地目录（全是在线曲目或 SAF 路径）/
        // 当前这一层没有子文件夹（这种情况直接点「播放本文件夹」就行）。
        val (titleRes, hintRes) = when (grouping) {
            Grouping.ALBUM -> R.string.library_empty_album to R.string.library_empty_album_hint
            Grouping.ARTIST -> R.string.library_empty_artist to R.string.library_empty_artist_hint
            Grouping.FOLDER -> when {
                songs.isEmpty() ->
                    R.string.library_empty_songs to R.string.library_empty_songs_hint

                currentDir == null ->
                    R.string.library_empty_folder to R.string.library_empty_folder_hint

                else ->
                    R.string.library_empty_folder_flat to R.string.library_empty_folder_flat_hint
            }
        }
        binding.emptyTitle.setText(titleRes)
        binding.emptyHint.setText(hintRes)
    }

    /** 面包屑：根 → 当前层，每一级可点，点当前层无动作。 */
    private fun renderBreadcrumb(root: File, current: File) {
        val container = binding.breadcrumbContainer
        container.removeAllViews()
        val crumbs = LibraryGrouper.breadcrumb(root, current)
        crumbs.forEachIndexed { index, dir ->
            if (index > 0) {
                container.addView(buildCrumbSeparator())
            }
            container.addView(buildCrumb(dir, isCurrent = index == crumbs.lastIndex) {
                if (dir == current) return@buildCrumb
                jumpToDirectory(dir)
            })
        }
        binding.breadcrumbScroll.post {
            binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT)
        }
    }

    private fun jumpToDirectory(dir: File) {
        currentDir = dir
        settings.folderBrowsePath = dir.absolutePath
        refresh()
    }

    private fun buildCrumbSeparator(): TextView = TextView(requireContext()).apply {
        text = "/"
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        setPadding(dp(6), 0, dp(6), 0)
    }

    private fun buildCrumb(dir: File, isCurrent: Boolean, onJump: () -> Unit): TextView =
        TextView(requireContext()).apply {
            text = if (dir == rootDir) getString(R.string.library_breadcrumb_root) else dir.name
            textSize = 17f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setTextColor(
                attrColor(
                    if (isCurrent) {
                        com.google.android.material.R.attr.colorOnSurface
                    } else {
                        com.google.android.material.R.attr.colorPrimary
                    }
                )
            )
            if (!isCurrent) {
                isClickable = true
                setOnClickListener { onJump() }
            }
        }

    // ---------------------------------------------------------------- 交互

    private fun onGroupClicked(group: LibraryGroup) {
        when (grouping) {
            Grouping.ALBUM, Grouping.ARTIST -> playAndOpen(group.songs)
            Grouping.FOLDER -> {
                val dir = File(group.key)
                // 还有下一层就下钻；已经是叶子目录就直接播放（省掉「进去再点播放」这一跳）
                if (LibraryGrouper.folderChildren(group.songs, dir).isNotEmpty()) {
                    currentDir = dir
                    settings.folderBrowsePath = dir.absolutePath
                    refresh()
                } else {
                    playAndOpen(group.songs)
                }
            }
        }
    }

    /**
     * 长按文件夹 → 确认后隐藏整棵子树。
     *
     * 隐藏的粒度是**整棵子树**：`LibraryGrouper` 的文件夹条目本来就带着整个子目录的歌，
     * 所以确认框里报的 `group.songs.size` 就是「这次会一起消失多少首」，用户不必猜。
     */
    private fun onGroupLongClicked(group: LibraryGroup) {
        if (grouping != Grouping.FOLDER) return
        if (!isAdded) return
        val dir = File(group.key)
        val songCount = group.songs.size
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.hidden_folder_title)
            .setMessage(getString(R.string.hidden_folder_message, group.title, songCount))
            .setNegativeButton(R.string.hidden_folder_cancel, null)
            .setPositiveButton(R.string.hidden_folder_confirm) { _, _ -> hideFolder(dir) }
            .show()
    }

    /**
     * 真正落盘 + 通知宿主 + 重画本页。
     *
     * 顺序是有讲究的：先写存储（宿主重算过滤时读的就是它），再让宿主重算全局可见曲库
     * （抽屉计数、首页列表跟着变），最后才重画本页 —— 本页 `refresh()` 会重新走
     * `folderGroups()` 并读一次 `allSongs()`，此时宿主的数据已经是新的了。
     */
    private fun hideFolder(dir: File) {
        hiddenStore.hide(dir.absolutePath)
        retreatIfBrowsingHidden()
        (activity as? MainHost)?.refreshHiddenFilter()
        refresh()
        Toast.makeText(
            requireContext(),
            getString(R.string.hidden_folder_hidden, dir.name),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * 隐藏后如果**当前浏览位置**正好被隐藏覆盖，退回扫描根。
     *
     * 不做这一步的话，用户会在原地下钻过的层级里看到空目录，而面包屑还在，
     * 又因为 `settings.folderBrowsePath` 会被记忆，退出再进来仍然是那个空目录
     * （`folderGroups()` 只校验「是否仍在根之下」，不校验「是否被隐藏」）。
     * 所以这里必须同时改 `currentDir` 与记忆值，两处都指向根才真的回得去。
     */
    private fun retreatIfBrowsingHidden() {
        val current = currentDir ?: return
        val root = rootDir ?: return
        val covered = hiddenStore.hiddenDirs()
            .any { LibraryGrouper.isAncestorOrSelf(it, current) }
        if (!covered) return
        currentDir = root
        settings.folderBrowsePath = root.absolutePath
    }

    private fun playCurrentFolder() {
        val dir = currentDir ?: return
        playAndOpen(LibraryGrouper.songsUnder(allSongs(), dir))
    }

    private fun playAndOpen(songs: List<Song>) {
        val host = activity as? MainHost ?: return
        if (songs.isEmpty()) return
        host.playSongSubset(songs, 0, LibraryKind.MUSIC)
        host.selectDestination(Destination.PLAYER)
    }

    private fun allSongs(): List<Song> = (activity as? MainHost)?.allSongs().orEmpty()

    // ------------------------------------------------------- 页面内层级（返回键）

    /** 只有文件夹模式会下钻，下钻之后才算「还有一层」。 */
    override fun hasInnerLevel(): Boolean {
        if (grouping != Grouping.FOLDER) return false
        val root = rootDir ?: return false
        val current = currentDir ?: return false
        return current != root
    }

    /**
     * 返回键：先在文件夹树里上一级，退到扫描根之后才交还给目的地栈。
     *
     * 上跳时用 `isAncestorOrSelf` 夹一下：父目录有可能已经在扫描根之外
     * （扫描根是「所有歌曲的公共根」，不一定是 `/`），那就直接回根，不能跳出曲库。
     */
    override fun handlePageBack(): Boolean {
        if (!hasInnerLevel()) return false
        val root = rootDir ?: return false
        val current = currentDir ?: return false
        val parent = current.parentFile ?: root
        jumpToDirectory(if (LibraryGrouper.isAncestorOrSelf(root, parent)) parent else root)
        return true
    }

    // ---------------------------------------------------------------- 工具

    /** 横屏一屏 5 张、竖屏 2 张：按「可用宽度 / 200dp」算，最少 2 列。 */
    private fun albumSpanCount(): Int {
        val marginDp = resources.getDimension(R.dimen.library_page_margin_horizontal) /
            resources.displayMetrics.density
        val widthDp = resources.configuration.screenWidthDp - marginDp * 2
        return max(2, (widthDp / 200f).toInt())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun attrColor(attr: Int): Int =
        MaterialColors.getColor(requireContext(), attr, Color.TRANSPARENT)

    companion object {
        private const val ARG_GROUPING = "grouping"
        private const val DISABLED_ALPHA = 0.38f

        fun newInstance(grouping: Grouping): GroupedLibraryFragment =
            GroupedLibraryFragment().apply {
                arguments = Bundle().apply { putString(ARG_GROUPING, grouping.name) }
            }
    }
}
