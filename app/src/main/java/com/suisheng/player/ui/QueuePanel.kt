package com.suisheng.player.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.suisheng.player.R
import com.suisheng.player.databinding.ItemQueueBinding
import com.suisheng.player.databinding.PanelQueueBinding
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder

/**
 * 播放队列面板（`design/05 §3.4` 功能行第 4 格）。
 *
 * 与 [SpeedPanel] 同一个模式：`object` + [PanelHost] + ViewBinding，面板**不持有业务逻辑**：
 * - 点行 → [onJump] 传「队列内索引」给宿主，宿主自己发 `queue_jump`；
 * - 点 ×→ 本地先移除该行（不 dismiss，方便连续删），再 [onRemove] 通知宿主；
 * - 点「清空」→ 二次确认后 [onClear] 并关闭面板。
 *
 * ## 5.8.0：打开就能看见「现在播到第几首」
 *
 * 旧实现打开面板只是把列表摆在最上面，几百首的队列里用户要自己往下翻才能找到 ♪。
 * 现在做了两件事：头部直接报出「正在播放第 M 首」，列表自动滚到当前项（并刻意让当前项
 * 落在偏上的位置，好让「后面还有哪些」也一并露出来）。
 *
 * ## 5.8.0：移出后的两步操作条
 *
 * 点 × 的常见意图只是「这首这次不想听了」，所以移出本身**不弹任何对话框**，只在下方
 * 出现一条操作条：[撤销] 或 [删除文件]。删除文件会走 [onDeleteFiles] → 宿主的二次确认
 * （列文件名），因为删文件不可恢复，且用户裁决「只管刚移出的那一首」。
 *
 * 撤销只保**最近一首**（用户裁决 5）：再移出一首就覆盖掉上一首的撤销机会。这不需要持久化，
 * 面板关掉即失效 —— 撤销是「手滑补救」，不是回收站。
 */
object QueuePanel {

    /** 刚被移出的一行：撤销要恢复它，删除文件要知道删谁。 */
    private data class Removed(val index: Int, val song: Song)

    /**
     * 弹出队列面板。
     *
     * @param onJump 跳转到队列内第 index 首
     * @param onRemove 移出队列内第 index 首
     * @param onInsert 把 song 插回队列内第 index 位（撤销）
     * @param onDeleteFiles 用户确认要删这首歌的本地文件；二次确认由宿主负责
     * @param onClear 清空整个队列
     */
    fun show(
        context: Context,
        onJump: (Int) -> Unit,
        onRemove: (Int) -> Unit,
        onInsert: (Int, Song) -> Unit,
        onDeleteFiles: (Song) -> Unit,
        onClear: () -> Unit,
    ) {
        val binding = PanelQueueBinding.inflate(LayoutInflater.from(context))
        // 队列面板里本身有一个会滚的 RecyclerView，绝不能让 [PanelHost] 再套 NestedScrollView
        // （那会一次性 measure 全部 item，几百首队列直接卡死），所以传 scrollable = false，
        // 改用 capFixedListHeight 按屏高反算列表高度。
        val dialog = PanelHost.build(
            context,
            binding.root,
            scrollable = false,
            onShown = { availableHeight ->
                // show 之后头部才真正测量完；用宿主给出的**本面板实际可用高**反算
                // 列表高，车机居中卡片与手机贴底抽屉各按自己的窗口尺寸收口，
                // 避免列表比窗口还高、把「清空」和底部内边距挤出屏幕。
                PanelHost.capFixedListHeight(
                    context,
                    list = binding.queueList,
                    header = binding.queueHeader,
                    sheetRoot = binding.root,
                    capPx = availableHeight,
                )
            },
        )

        val adapter = QueueAdapter(
            items = PlaybackStateHolder.songs.toMutableList(),
            currentIndex = PlaybackStateHolder.currentIndex,
            onClick = { index ->
                onJump(index)
                dialog.dismiss()
            },
        )

        // 只保最近一首：撤销的目标永远是「最后一次移出的那一首」。
        var lastRemoved: Removed? = null

        fun hideActionBar() {
            binding.queueActionBar.visibility = View.GONE
            lastRemoved = null
        }

        fun showActionBar(removed: Removed) {
            lastRemoved = removed
            binding.queueActionHint.text =
                context.getString(R.string.queue_removed_hint, removed.song.title)
            binding.queueActionBar.visibility = View.VISIBLE
        }

        // 先让面板自己的行、计数、空态正确，再发指令给服务
        adapter.onRemoveClick = { index ->
            // 必须先取歌再删行：删完 itemAt(index) 就是下一首了。
            val removedSong = adapter.itemAt(index)
            if (removedSong != null && adapter.removeAt(index)) {
                refreshHeader(context, binding, adapter.itemCount, adapter.currentItemIndex())
                onRemove(index)
                showActionBar(Removed(index, removedSong))
            }
        }

        binding.btnQueueUndo.setOnClickListener {
            val removed = lastRemoved ?: return@setOnClickListener
            if (adapter.insertAt(removed.index, removed.song)) {
                refreshHeader(context, binding, adapter.itemCount, adapter.currentItemIndex())
                onInsert(removed.index, removed.song)
                // 插回去之后用户可以马上再点一次 ×，所以滚回到它所在的位置。
                scrollToCurrent(binding)
            }
            hideActionBar()
        }

        binding.btnQueueDeleteFiles.setOnClickListener {
            val removed = lastRemoved ?: return@setOnClickListener
            hideActionBar()
            // 先关面板再弹二次确认：两层对话框叠在一起，确认框会把队列挡住，
            // 用户点「取消」回来时看到的是一个已经变过形的面板。
            dialog.dismiss()
            onDeleteFiles(removed.song)
        }

        binding.queueList.layoutManager = LinearLayoutManager(context)
        binding.queueList.adapter = adapter

        binding.btnClearQueue.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.queue_clear)
                .setMessage(R.string.queue_clear_confirm)
                .setPositiveButton(R.string.queue_clear) { _, _ ->
                    onClear()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }

        refreshHeader(context, binding, adapter.itemCount, adapter.currentItemIndex())
        dialog.show()
        // show 之后 capFixedListHeight 才把列表高度定下来，所以定位必须放到它后面，
        // 否则算出来的偏移是拿 360dp 的 XML 初值来的。
        scrollToCurrent(binding)
    }

    /** 把列表滚到当前播放项，并刻意留出上方三分之一，让「后面还有哪些」也露出来。 */
    private fun scrollToCurrent(binding: PanelQueueBinding) {
        val manager = binding.queueList.layoutManager as? LinearLayoutManager ?: return
        binding.queueList.post {
            val index = (binding.queueList.adapter as? QueueAdapter)?.currentItemIndex() ?: -1
            if (index < 0) return@post
            manager.scrollToPositionWithOffset(index, binding.queueList.height / 3)
        }
    }

    /** 「共 N 首」/「共 N 首 · 正在播放第 M 首」+ 空态切换；清空按钮在空队列时置灰。 */
    private fun refreshHeader(
        context: Context,
        binding: PanelQueueBinding,
        count: Int,
        currentIndex: Int,
    ) {
        binding.queueCount.text = if (currentIndex in 0 until count) {
            context.getString(R.string.queue_count_current, count, currentIndex + 1)
        } else {
            context.getString(R.string.queue_count, count)
        }
        val empty = count == 0
        binding.queueEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.queueList.visibility = if (empty) View.GONE else View.VISIBLE
        binding.btnClearQueue.isEnabled = !empty
        binding.btnClearQueue.alpha = if (empty) 0.5f else 1f
    }

    /**
     * 队列行适配器。
     *
     * 刻意不用 `ListAdapter`：面板内的增删是同步的（删一行就 `notifyItemRemoved`），
     * 走 DiffUtil 反而会把「点了就消失」拖成一次异步 diff。
     */
    private class QueueAdapter(
        private val items: MutableList<Song>,
        private var currentIndex: Int,
        private val onClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<QueueAdapter.RowHolder>() {

        /** 删除回调由面板在构造后接上（面板还要同步计数与空态）。 */
        var onRemoveClick: (Int) -> Unit = {}

        fun itemAt(position: Int): Song? = items.getOrNull(position)

        fun currentItemIndex(): Int = currentIndex

        /**
         * 本地移除一行。
         *
         * 当前项下标的调整规则与后台 `MusicPlaybackService.ACTION_QUEUE_REMOVE` 完全一致：
         * - 删的是当前项之前的行 → 当前项前移一位；
         * - 删的是当前项本身 → 服务端会接着播原来的下一首，而那一首现在正落在同一个下标上；
         * - 删空 → 当前项变 -1。
         */
        fun removeAt(position: Int): Boolean {
            if (position !in items.indices) return false
            items.removeAt(position)
            if (position < currentIndex) currentIndex -= 1
            if (currentIndex > items.lastIndex) currentIndex = items.lastIndex
            notifyItemRemoved(position)
            val rest = items.size - position
            if (rest > 0) notifyItemRangeChanged(position, rest)
            return true
        }

        /**
         * 撤销用：把 [song] 插回 [position]。
         *
         * 下标规则与 [com.suisheng.player.MusicPlaybackService] 的 `insertIntoQueue` 一致：
         * 插在当前项之前（含同位）时当前项整体后移一位，否则不动。
         */
        fun insertAt(position: Int, song: Song): Boolean {
            if (position !in 0..items.size) return false
            items.add(position, song)
            if (currentIndex < 0) {
                // 删空后撤销：让它成为当前项，与 MusicPlaybackService.insertIntoQueue 一致
                currentIndex = position
            } else if (position <= currentIndex) {
                currentIndex += 1
            }
            notifyItemInserted(position)
            val rest = items.size - position - 1
            if (rest > 0) notifyItemRangeChanged(position + 1, rest)
            return true
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val binding = ItemQueueBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return RowHolder(binding)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            holder.bind(items[position], position)
        }

        inner class RowHolder(private val binding: ItemQueueBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(song: Song, position: Int) {
                val isCurrent = position == currentIndex
                val onSurface = MaterialColors.getColor(
                    binding.root,
                    com.google.android.material.R.attr.colorOnSurface,
                )
                val onSurfaceVariant = MaterialColors.getColor(
                    binding.root,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                )
                val primary = MaterialColors.getColor(
                    binding.root,
                    com.google.android.material.R.attr.colorPrimary,
                )
                val highlight = MaterialColors.getColor(
                    binding.root,
                    com.google.android.material.R.attr.colorSurfaceContainerHigh,
                )

                binding.queueIndex.text = if (isCurrent) PLAYING_MARK else (position + 1).toString()
                binding.queueIndex.setTextColor(if (isCurrent) primary else onSurfaceVariant)
                binding.queueIndex.setTypeface(
                    null,
                    if (isCurrent) Typeface.BOLD else Typeface.NORMAL,
                )

                binding.queueItemTitle.text = song.title
                binding.queueItemTitle.setTextColor(if (isCurrent) primary else onSurface)
                binding.queueItemArtist.text = song.artist

                binding.root.setBackgroundColor(if (isCurrent) highlight else Color.TRANSPARENT)

                binding.root.setOnClickListener {
                    val index = bindingAdapterPosition
                    if (index != RecyclerView.NO_POSITION) onClick(index)
                }
                binding.queueItemRemove.setOnClickListener {
                    val index = bindingAdapterPosition
                    if (index != RecyclerView.NO_POSITION) onRemoveClick(index)
                }
            }
        }

        private companion object {
            /** 当前项序号位显示的音符，比再画一个图标省事且一定能渲染。 */
            const val PLAYING_MARK = "♪"
        }
    }
}
