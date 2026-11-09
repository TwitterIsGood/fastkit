package io.github.twitterisgood.fastkit

import android.app.Activity
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.ImageView
import android.widget.TextView

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
            val section = adapter.sectionOf(position)
            if (section is CardSection) {
                val width = list.width.takeIf { it > 0 } ?: 720
                val local = adapter.localPositionOf(position)
                loader.prefetch(section.imageUrl(local), width, itemHeight)
            }
        })
    }

    private class BannerSection : Section() {
        override fun itemCount() = 1
        override fun layoutRes(position: Int) = R.layout.item_card

        override fun onBind(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            v.findViewById<TextView>(R.id.title).text = "精选推荐"
            v.findViewById<TextView>(R.id.subtitle).text = "扁平列表 + 尺寸感知图片"
            FastImageLoader.get().load(v.findViewById(R.id.image), "local://banner/1600x900")
        }
    }

    private class CardSection(private val index: Int) : Section() {
        override fun itemCount() = 10
        override fun layoutRes(position: Int) = R.layout.item_card

        fun imageUrl(local: Int) = "local://card${index}_$local/1200x1600"

        override fun onBind(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            v.findViewById<TextView>(R.id.title).text = "应用 $index - $position"
            v.findViewById<TextView>(R.id.subtitle).text = "section=$index item=$position"
            FastImageLoader.get().load(v.findViewById<ImageView>(R.id.image), imageUrl(position))
        }

        override fun onRecycled(holder: RecyclerView.ViewHolder, position: Int) {
            val image = holder.itemView.findViewById<ImageView>(R.id.image)
            FastImageLoader.get().cancel(image)
            image.setImageDrawable(null)
        }
    }
}
