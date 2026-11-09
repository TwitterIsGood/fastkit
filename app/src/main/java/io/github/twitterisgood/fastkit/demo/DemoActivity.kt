package io.github.twitterisgood.fastkit.demo

import android.app.Activity
import android.os.Bundle
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.twitterisgood.fastkit.image.FastImageLoader
import io.github.twitterisgood.fastkit.list.FlatSectionAdapter
import io.github.twitterisgood.fastkit.list.PrefetchScrollListener
import io.github.twitterisgood.fastkit.list.Section
import io.github.twitterisgood.fastkit.list.SectionHolder
import io.github.twitterisgood.fastkit.list.setupForFeed

class DemoActivity : Activity() {

    private val loader by lazy { FastImageLoader.get() }

    // 首屏的图片还没上齐就调用 reportFullyDrawn()，等于把「骨架屏出现」当成「内容可见」，
    // 启动耗时会被系统性低估。这里按真实上屏时刻报。
    private var pendingFirstScreen = 0
    private var firstLayoutDone = false
    private var fullyDrawnReported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FastImageLoader.init(this)
        setContentView(R.layout.activity_demo)

        val sections = ArrayList<Section>()
        sections.add(BannerSection(::loadTracked))
        repeat(60) { sections.add(CardSection(it, ::loadTracked)) }

        val adapter = FlatSectionAdapter(sections)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.setupForFeed()
        list.adapter = adapter

        val density = resources.displayMetrics.density
        val itemHeight = (180 * density).toInt()

        list.addOnScrollListener(PrefetchScrollListener(distance = 4) { position ->
            val section = adapter.sectionOf(position) as? CardSection
                ?: return@PrefetchScrollListener
            val width = list.width.takeIf { it > 0 } ?: 720
            loader.prefetch(section.imageUrl(adapter.localPositionOf(position)), width, itemHeight)
        })

        list.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                list.viewTreeObserver.removeOnPreDrawListener(this)
                // post 到这一帧画完之后，保证 report 的是「已经上屏」的时刻
                list.post {
                    firstLayoutDone = true
                    reportWhenReady()
                }
                return true
            }
        })
    }

    /** 首屏绑定时计数，图片真正结束时减一。滚动后才绑的项不参与，避免永远等不齐。 */
    private fun loadTracked(view: ImageView, url: String) {
        val tracked = !firstLayoutDone
        if (tracked) pendingFirstScreen++
        loader.load(view, url) {
            if (!tracked) return@load
            pendingFirstScreen--
            reportWhenReady()
        }
    }

    private fun reportWhenReady() {
        if (fullyDrawnReported || !firstLayoutDone || pendingFirstScreen > 0) return
        fullyDrawnReported = true
        reportFullyDrawn()
    }

    private class BannerSection(
        private val loadImage: (ImageView, String) -> Unit
    ) : Section() {
        override fun itemCount() = 1
        override fun layoutRes(position: Int) = R.layout.item_card

        override fun onBind(holder: RecyclerView.ViewHolder, position: Int) {
            val h = holder as SectionHolder
            h.view<TextView>(R.id.title).text = "精选推荐"
            h.view<TextView>(R.id.subtitle).text = "扁平列表 + 尺寸感知图片"
            loadImage(h.view(R.id.image), "local://banner/1600x900")
        }

        override fun onRecycled(holder: RecyclerView.ViewHolder, position: Int) =
            releaseImage(holder)
    }

    private class CardSection(
        private val index: Int,
        private val loadImage: (ImageView, String) -> Unit
    ) : Section() {
        override fun itemCount() = 10
        override fun layoutRes(position: Int) = R.layout.item_card

        fun imageUrl(local: Int) = "local://card${index}_$local/1200x1600"

        override fun onBind(holder: RecyclerView.ViewHolder, position: Int) {
            val h = holder as SectionHolder
            h.view<TextView>(R.id.title).text = "应用 $index - $position"
            h.view<TextView>(R.id.subtitle).text = "section=$index item=$position"
            loadImage(h.view(R.id.image), imageUrl(position))
        }

        override fun onRecycled(holder: RecyclerView.ViewHolder, position: Int) =
            releaseImage(holder)
    }

    private companion object {
        /** 复用即清理：取消请求 + 清空 drawable，否则会串图。 */
        fun releaseImage(holder: RecyclerView.ViewHolder) {
            FastImageLoader.get().clear(holder.itemView.findViewById(R.id.image))
        }
    }
}
