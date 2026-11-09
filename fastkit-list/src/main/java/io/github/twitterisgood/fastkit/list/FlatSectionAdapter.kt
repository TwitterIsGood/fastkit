package io.github.twitterisgood.fastkit.list

import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference

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

    /** 带 payload 的局部刷新；默认退化成整块 rebind。 */
    open fun onBindPayload(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: List<Any>
    ) = onBind(holder, position)

    open fun onRecycled(holder: RecyclerView.ViewHolder, position: Int) = Unit
}

open class SectionHolder(view: View) : RecyclerView.ViewHolder(view) {
    internal var section: Section? = null
    internal var localPosition: Int = -1

    private val views = SparseArray<View>(4)

    /** 缓存 findViewById 结果，避免每次 bind 都走一遍视图树。 */
    @Suppress("UNCHECKED_CAST")
    fun <T : View> view(id: Int): T {
        val cached = views.get(id)
        if (cached != null) return cached as T
        val found = itemView.findViewById<T>(id)
        views.put(id, found)
        return found
    }
}

class FlatSectionAdapter(
    initial: List<Section> = emptyList()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val sections = ArrayList<Section>(initial)
    private val index = SectionIndex()
    private var attached: WeakReference<RecyclerView>? = null

    private val typeIds = HashMap<Long, Int>()
    private val typeLayout = ArrayList<Int>()
    private val typeSection = ArrayList<Section>()

    init {
        rebuild()
    }

    override fun getItemCount(): Int = index.total

    override fun getItemViewType(position: Int): Int {
        val (index, local) = locate(position)
        val section = sections[index]
        val layout = section.layoutRes(local)
        val key = (layout.toLong() shl 32) or (section.viewType(local).toLong() and 0xFFFFFFFFL)
        typeIds[key]?.let { return it }

        val id = typeLayout.size
        typeLayout.add(layout)
        typeSection.add(section)
        typeIds[key] = id
        // viewType 是懒注册的，attach 时还不知道有哪些类型，所以在这里补设缓存上限
        attached?.get()?.recycledViewPool?.setMaxRecycledViews(id, CACHE_PER_TYPE)
        return id
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val section = typeSection[viewType]
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

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        val (index, local) = locate(position)
        val section = sections[index]
        if (holder is SectionHolder) {
            holder.section = section
            holder.localPosition = local
        }
        section.onBindPayload(holder, local, payloads)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        val section = (holder as? SectionHolder)?.section ?: return
        val local = holder.localPosition
        if (local >= 0) section.onRecycled(holder, local)
        holder.localPosition = -1
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        attached = WeakReference(recyclerView)
        // 已经注册过的类型补设上限
        typeLayout.indices.forEach { type ->
            recyclerView.recycledViewPool.setMaxRecycledViews(type, CACHE_PER_TYPE)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        attached = null
    }

    fun sectionOf(position: Int): Section = sections[locate(position).first]

    fun localPositionOf(position: Int): Int = locate(position).second

    fun addSection(index: Int, section: Section) {
        val at = index.coerceIn(0, sections.size)
        val start = this.index.offsetOf(at)
        sections.add(at, section)
        rebuild()
        if (section.itemCount() > 0) notifyItemRangeInserted(start, section.itemCount())
    }

    /**
     * 全量替换。用 range 通知而不是 notifyDataSetChanged：
     * 同时清掉类型表和回收池，避免旧类型视图被新列表误用。
     */
    fun replaceAll(next: List<Section>) {
        val oldTotal = index.total
        sections.clear()
        sections.addAll(next)
        rebuild()
        typeIds.clear()
        typeLayout.clear()
        typeSection.clear()

        if (oldTotal > 0) notifyItemRangeRemoved(0, oldTotal)
        if (index.total > 0) notifyItemRangeInserted(0, index.total)
        attached?.get()?.recycledViewPool?.clear()
    }

    fun notifySectionChanged(section: Section, start: Int, count: Int, payload: Any? = null) {
        val index = sections.indexOf(section)
        if (index < 0 || count <= 0) return
        notifyItemRangeChanged(offsetOf(index) + start, count, payload)
    }

    internal fun offsetOf(index: Int): Int = this.index.offsetOf(index)

    private fun rebuild() = index.rebuild(sections.map { it.itemCount() })

    private fun locate(position: Int): Pair<Int, Int> = index.locateLocal(position)

    companion object {
        private const val CACHE_PER_TYPE = 3
    }
}

/**
 * 把列表本身调成"信息流"模式：这是 Play 那种顺滑的默认值。
 * 必须在设置 layoutManager 之后调用（初始预取数量要写到 layoutManager 上）。
 */
fun RecyclerView.setupForFeed(initialPrefetch: Int = 4) {
    itemAnimator = null
    setHasFixedSize(true)
    setItemViewCacheSize(20)
    overScrollMode = View.OVER_SCROLL_NEVER
    setNestedScrollingEnabled(true)
    (layoutManager as? LinearLayoutManager)?.initialPrefetchItemCount = initialPrefetch
}
