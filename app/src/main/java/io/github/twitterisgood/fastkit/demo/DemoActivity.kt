package io.github.twitterisgood.fastkit.demo

import android.app.Activity
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FastImageLoader.init(this)
        setContentView(R.layout.activity_demo)

        val sections = ArrayList<Section>()
        sections.add(BannerSection())
        repeat(60) { sections.add(CardSection(it)) }

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
    }

    private class BannerSection : Section() {
        override fun itemCount() = 1
        override fun layoutRes(position: Int) = R.layout.item_card

        override fun onBind(holder: RecyclerView.ViewHolder, position: Int) {
            val h = holder as SectionHolder
            h.view<TextView>(R.id.title).text = "精选推荐"
            h.view<TextView>(R.id.subtitle).text = "扁平列表 + 尺寸感知图片"
            FastImageLoader.get().load(
                h.view<ImageView>(R.id.image),
                "local://banner/1600x900"
            )
        }

        override fun onRecycled(holder: RecyclerView.ViewHolder, position: Int) =
            releaseImage(holder)
    }

    private class CardSection(private val index: Int) : Section() {
        override fun itemCount() = 10
        override fun layoutRes(position: Int) = R.layout.item_card

        fun imageUrl(local: Int) = "local://card${index}_$local/1200x1600"

        override fun onBind(holder: RecyclerView.ViewHolder, position: Int) {
            val h = holder as SectionHolder
            h.view<TextView>(R.id.title).text = "应用 $index - $position"
            h.view<TextView>(R.id.subtitle).text = "section=$index item=$position"
            FastImageLoader.get().load(h.view<ImageView>(R.id.image), imageUrl(position))
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
