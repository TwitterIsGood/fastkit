package io.github.twitterisgood.fastkit

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * 一个"区块"，对应 Play 里的 section controller。
 *
 * 关键约束：区块只描述自己的 item，绝不能持有子 RecyclerView。
 * 整页是一条扁平的 item 流，位置由 [FlatSectionAdapter] 用前缀和算出来。
 */
abstract class Section {

    abstract fun itemCount(): Int
    abstract fun layoutRes(position: Int): Int

    open fun viewType(position: Int): Int = 0

    open fun onCreateViewHolder(parent: ViewGroup, layoutRes: Int): RecyclerView.ViewHolder =
        SectionHolder(LayoutInflater.from(parent.context).inflate(layoutRes, parent, false))

    abstract fun onBind(holder: RecyclerView.ViewHolder, position: Int)

    open fun onRecycled(holder: RecyclerView.ViewHolder, position: Int) = Unit
}

open class SectionHolder(view: View) : RecyclerView.ViewHolder(view) {
    internal var section: Section? = null
    internal var localPosition: Int = -1
}

class FlatSectionAdapter(
    initial: List<Section> = emptyList()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val sections = ArrayList<Section>(initial)
    private var offsets = IntArray(0)
    private var total = 0

    private val typeIds = HashMap<Long, Int>()
    private val typeLayout = ArrayList<Int>()
    private val typeOwner = ArrayList<Int>()

    init {
        rebuild()
    }

    override fun getItemCount(): Int = total

    override fun getItemViewType(position: Int): Int {
        val (index, local) = locate(position)
        val section = sections[index]
        val key = (section.layoutRes(local).toLong() shl 32) or
            (section.viewType(local).toLong() and 0xFFFFFFFFL)
        typeIds[key]?.let { return it }
        val id = typeLayout.size
        typeLayout.add(section.layoutRes(local))
        typeOwner.add(index)
        typeIds[key] = id
        return id
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val section = sections[typeOwner[viewType]]
        val holder = section.onCreateViewHolder(parent, typeLayout[viewType])
        if (holder is SectionHolder) holder.section = section
        return holder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val (index, local) = locate(position)
        val section = sections[index]
        if (holder is SectionHolder) {
            holder.section = section
            holder.localPosition = local
        }
        section.onBind(holder, local)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is SectionHolder) {
            val section = holder.section ?: return
            if (holder.localPosition >= 0) section.onRecycled(holder, holder.localPosition)
            holder.localPosition = -1
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        // 同类型 ViewHolder 只保 2~3 个，避免长列表把整屏的 View 都攥在内存里
        typeLayout.indices.forEach { type ->
            recyclerView.recycledViewPool.setMaxRecycledViews(type, CACHE_PER_TYPE)
        }
    }

    fun sectionOf(position: Int): Section = sections[locate(position).first]

    fun localPositionOf(position: Int): Int = locate(position).second

    fun addSection(index: Int, section: Section) {
        val at = offsets.getOrElse(index.coerceAtMost(sections.size)) { total }
        sections.add(index.coerceAtMost(sections.size), section)
        rebuild()
        notifyItemRangeInserted(at, section.itemCount())
    }

    fun replaceAll(next: List<Section>) {
        sections.clear()
        sections.addAll(next)
        rebuild()
        notifyDataSetChanged()
    }

    fun notifySectionChanged(section: Section, start: Int, count: Int, payload: Any? = null) {
        val index = sections.indexOf(section)
        if (index < 0) return
        val global = offsetOf(index) + start
        notifyItemRangeChanged(global, count, payload)
    }

    internal fun offsetOf(index: Int): Int = if (index <= 0) 0 else offsets[index - 1]

    private fun rebuild() {
        offsets = IntArray(sections.size)
        var acc = 0
        for (i in sections.indices) {
            acc += sections[i].itemCount()
            offsets[i] = acc
        }
        total = acc
    }

    private fun locate(position: Int): Pair<Int, Int> {
        if (sections.isEmpty()) return 0 to 0
        var lo = 0
        var hi = offsets.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[mid] <= position) lo = mid + 1 else hi = mid
        }
        val base = if (lo <= 0) 0 else offsets[lo - 1]
        return lo to (position - base)
    }

    companion object {
        private const val CACHE_PER_TYPE = 3
    }
}

/** 把列表本身调成"信息流"模式：这是 Play 那种顺滑的默认值。 */
fun RecyclerView.setupForFeed() {
    itemAnimator = null
    setHasFixedSize(true)
    setItemViewCacheSize(20)
    overScrollMode = View.OVER_SCROLL_NEVER
    setNestedScrollingEnabled(true)
}
