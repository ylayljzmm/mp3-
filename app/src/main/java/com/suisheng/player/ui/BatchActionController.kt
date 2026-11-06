package com.suisheng.player.ui

import android.view.View
import android.widget.TextView
import com.suisheng.player.R

/**
 * Q-3 批量操作条（12 号文 §6.2）：「已选 N 首」+ 加入队列 / 收藏 / 移出队列 / 取消。
 *
 * 只管显隐、计数与空态置灰，不持有曲库、不直接调播放服务；三个动作由页面回调。
 * 「移出队列」是从播放队列移除，**不是删文件**。
 */
class BatchActionController(
    private val root: View,
    private val countView: TextView,
    private val actions: List<TextView>,
    onAddToQueue: () -> Unit,
    onFavorite: () -> Unit,
    onRemoveFromQueue: () -> Unit,
    onCancel: () -> Unit,
) {
    init {
        require(actions.size == 4) { "批量操作条需要 4 个按钮" }
        actions[0].setOnClickListener { onAddToQueue() }
        actions[1].setOnClickListener { onFavorite() }
        actions[2].setOnClickListener { onRemoveFromQueue() }
        actions[3].setOnClickListener { onCancel() }
        hide()
    }

    val isVisible: Boolean get() = root.visibility == View.VISIBLE

    fun show(count: Int) {
        root.visibility = View.VISIBLE
        countView.text = root.context.getString(R.string.batch_selected, count)
        val enabled = count > 0
        actions.forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.45f
        }
    }

    fun hide() {
        root.visibility = View.GONE
    }
}
