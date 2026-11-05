package com.suisheng.player.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.suisheng.player.R
import com.suisheng.player.databinding.ItemQueueBinding
import com.suisheng.player.databinding.PanelQueueBinding
import com.suisheng.player.model.Song
import com.suisheng.player.playback.PlaybackStateHolder

/**
 * 播放队列面板（`design/05 §3.4` 传输行第 5 位 / 顶栏队列按钮）。
 *
 * 与 [SpeedPanel] 同一个模式：`object` + `BottomSheetDialog` + ViewBinding，面板**不持有业务逻辑**：
 * - 点行 → [onJump] 传「队列内索引」给宿主，宿主自己发 `queue_jump`；
 * - 点 ×→ 本地先移除该行（不 dismiss，方便连续删），再 [onRemove] 通知宿主；
 * - 点「清空」→ 二次确认后 [onClear] 并关闭面板。
 *
 * 队列数据是**打开面板那一刻**的快照（[PlaybackStateHolder.songs] / [PlaybackStateHolder.currentIndex]）。
 * 删行时本地快照与后台 `playlist` 按同一规则同步缩短，所以下标始终对得上。
 */
object QueuePanel {

    /**
     * 弹出队列面板。
     *
     * @param onJump 跳转到队列内第 index 首
     * @param onRemove 移出队列内第 index 首
     * @param onClear 清空整个队列
     */
    fun show(
        context: Context,
        onJump: (Int) -> Unit,
        onRemove: (Int) -> Unit,
        onClear: () -> Unit,
    ) {
        val binding = PanelQueueBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(binding.root)
        capListHeight(context, binding)

        val adapter = QueueAdapter(
            items = PlaybackStateHolder.songs.toMutableList(),
            currentIndex = PlaybackStateHolder.currentIndex,
            onClick = { index ->
                onJump(index)
                dialog.dismiss()
            },
        )
        // removeAt 之后再回调宿主：先让面板自己的行、计数、空态正确，再发指令给服务
        adapter.onRemoveClick = { index ->
            if (adapter.removeAt(index)) {
                refreshHeader(context, binding, adapter.itemCount)
                onRemove(index)
            }
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

        refreshHeader(context, binding, adapter.itemCount)
        dialog.show()
    }

    /** 「共 N 首」+ 空态切换；清空按钮在空队列时置灰。 */
    private fun refreshHeader(context: Context, binding: PanelQueueBinding, count: Int) {
        binding.queueCount.text = context.getString(R.string.queue_count, count)
        val empty = count == 0
        binding.queueEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.queueList.visibility = if (empty) View.GONE else View.VISIBLE
        binding.btnClearQueue.isEnabled = !empty
        binding.btnClearQueue.alpha = if (empty) 0.5f else 1f
    }

    /**
     * 列表高度兜底：XML 里给的是 360dp，这里再按屏高压一次（<= 屏高 55%），
     * 保证小屏 / 横屏车机上面板不会顶出屏幕（BottomSheet 不会帮你裁 wrap_content 内容）。
     */
    private fun capListHeight(context: Context, binding: PanelQueueBinding) {
        val screenPx = context.resources.displayMetrics.heightPixels
        val capPx = (screenPx * MAX_LIST_HEIGHT_RATIO).toInt()
        val params = binding.queueList.layoutParams
        if (params.height > capPx) {
            params.height = capPx
            binding.queueList.layoutParams = params
        }
    }

    /** 列表 360dp 与屏高 55% 取小。 */
    private const val MAX_LIST_HEIGHT_RATIO = 0.55f

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
