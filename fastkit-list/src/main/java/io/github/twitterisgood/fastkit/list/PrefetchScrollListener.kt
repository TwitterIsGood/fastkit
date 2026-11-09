package io.github.twitterisgood.fastkit.list

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 按滚动方向预取"即将进入屏幕"的若干 item，滚动停下前图片已在内存里。 */
class PrefetchScrollListener(
    private val distance: Int = 4,
    private val onPrefetch: (position: Int) -> Unit
) : RecyclerView.OnScrollListener() {

    private var lastAnchor = RecyclerView.NO_POSITION

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        if (dy == 0) return
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val count = recyclerView.adapter?.itemCount ?: return
        if (count == 0) return

        val anchor = if (dy > 0) {
            lm.findLastVisibleItemPosition()
        } else {
            lm.findFirstVisibleItemPosition()
        }
        if (anchor == lastAnchor || anchor == RecyclerView.NO_POSITION) return
        lastAnchor = anchor

        if (dy > 0) {
            for (p in (anchor + 1)..minOf(anchor + distance, count - 1)) onPrefetch(p)
        } else {
            for (p in maxOf(0, anchor - distance) until anchor) onPrefetch(p)
        }
    }
}
