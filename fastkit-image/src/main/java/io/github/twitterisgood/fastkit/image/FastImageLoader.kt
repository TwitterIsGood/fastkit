package io.github.twitterisgood.fastkit.image

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 尺寸感知 + 两级缓存的图片加载器。
 *
 * 与常见库的区别：
 * - 缓存键永远是 (url, 显示宽, 显示高)，并且允许"近似尺寸"命中；
 * - 近似尺寸靠 per-url 的 TreeMap 索引，主线程查找 O(log n)，不复制整个缓存；
 * - 同一 (url, 尺寸) 的并发请求共享一次下载和解码；
 * - 可见区域的请求排在预取之前；
 * - 收到系统内存压力回调时主动缩容。
 */
class FastImageLoader private constructor(
    appContext: Context,
    private val memoryBytes: Int,
    diskBytes: Long
) {

    private val main = Handler(Looper.getMainLooper())
    private val disk = DiskStore(File(appContext.cacheDir, "fastimg"), diskBytes)
    private val index = SizeIndex()
    private val inFlight = ConcurrentHashMap<String, Request>()

    private val memory = object : LruCache<String, Bitmap>(memoryBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            index.remove(key)
        }
    }

    private val workers = ThreadPoolExecutor(
        THREADS, THREADS, 30L, TimeUnit.SECONDS,
        PriorityBlockingQueue(16, Comparator<Runnable> { a, b ->
            priorityOf(a).compareTo(priorityOf(b))
        })
    )

    @Volatile
    var decodeConfig: Bitmap.Config = Bitmap.Config.RGB_565

    init {
        appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = trim(level)
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = clearMemory()
        })
    }

    /** 尺寸未知时先挂起，等 onLayout 拿到真实宽高再发请求（Play 的 FIFE 图也是这个行为）。 */
    fun load(view: ImageView, url: String) {
        val (w, h) = viewSize(view)
        if (w > 0 && h > 0) {
            load(view, url, w, h)
            return
        }
        release(view)
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                v.removeOnLayoutChangeListener(this)
                val pending = v.tag as? DeferredLoad ?: return
                if (pending.listener !== this) return
                v.tag = null
                load(v as ImageView, url)
            }
        }
        view.tag = DeferredLoad(listener)
        view.addOnLayoutChangeListener(listener)
    }

    fun load(view: ImageView, url: String, width: Int, height: Int) {
        release(view)
        val hit = memory.get(keyOf(url, width, height)) ?: nearest(url, width, height)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        val req = enqueue(url, width, height, PRIORITY_VISIBLE)
        req.addTarget(view)
        view.tag = req
    }

    /** 滚动预取：只解到内存，不碰 UI；排在可见请求之后。 */
    fun prefetch(url: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (memory.get(keyOf(url, width, height)) != null) return
        if (nearest(url, width, height) != null) return
        enqueue(url, width, height, PRIORITY_PREFETCH)
    }

    /** 取消该 view 的未完成请求，保留当前显示的图。 */
    fun cancel(view: ImageView) = release(view)

    /** 取消请求并清空 drawable。`onViewRecycled` 里必须调用，防止复用串图。 */
    fun clear(view: ImageView) {
        release(view)
        view.setImageDrawable(null)
    }

    fun clearMemory() = memory.evictAll()

    fun clearDisk() = disk.clear()

    // ComponentCallbacks2.onTrimMemory 在 API 35 起被标记弃用，但 minSdk 21 仍需要它
    @Suppress("DEPRECATION")
    fun trim(level: Int) {
        val target = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> 0

            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> memoryBytes / 2
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> memoryBytes * 3 / 4

            else -> return
        }
        memory.trimToSize(target)
    }

    private fun enqueue(url: String, w: Int, h: Int, priority: Int): Request {
        val key = keyOf(url, w, h)
        inFlight[key]?.let { return it }
        val req = Request(url, w, h, key)
        val raced = inFlight.putIfAbsent(key, req)
        if (raced != null) return raced
        workers.execute(DecodeTask(priority) { decodeInto(req) })
        return req
    }

    private fun decodeInto(req: Request) {
        try {
            if (req.cancelled) return
            val bytes = bytesFor(req.url) ?: return
            if (req.cancelled) return
            val bitmap = decodeWithRetry(bytes, req.width, req.height) ?: return
            memory.put(req.key, bitmap)
            index.add(req.url, req.width, req.height, req.key)
            main.post { req.deliver(bitmap) }
        } finally {
            inFlight.remove(req.key, req)
        }
    }

    private fun decodeWithRetry(bytes: ByteArray, w: Int, h: Int): Bitmap? = try {
        decodeSampled(bytes, w, h, decodeConfig)
    } catch (oom: OutOfMemoryError) {
        // 低端机上大图解码是真的会 OOM，清空缓存后再试一次
        memory.evictAll()
        try {
            decodeSampled(bytes, w, h, decodeConfig)
        } catch (oom2: OutOfMemoryError) {
            null
        }
    }

    private fun bytesFor(url: String): ByteArray? {
        disk.get(url)?.let { return it }
        val bytes = when {
            url.startsWith("local://") -> SyntheticSource.bytes(url)
            else -> httpGet(url)
        } ?: return null
        disk.put(url, bytes)
        return bytes
    }

    private fun httpGet(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes() }
        } catch (t: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun nearest(url: String, w: Int, h: Int): Bitmap? =
        index.nearest(url, w, h)?.let { memory.get(it) }

    private fun release(view: ImageView) {
        (view.tag as? Pending)?.detach(view)
        view.tag = null
    }

    private fun keyOf(url: String, w: Int, h: Int) = "#$url#${w}x$h"

    private fun viewSize(view: ImageView): Pair<Int, Int> {
        var w = view.width
        var h = view.height
        if (w > 0 && h > 0) return w to h
        val lp = view.layoutParams
        if (lp != null) {
            if (w <= 0 && lp.width > 0) w = lp.width
            if (h <= 0 && lp.height > 0) h = lp.height
        }
        return w to h
    }

    private interface Pending {
        fun detach(view: ImageView)
    }

    private inner class Request(
        val url: String,
        val width: Int,
        val height: Int,
        val key: String
    ) : Pending {
        @Volatile
        var cancelled = false

        private val targets = CopyOnWriteArrayList<WeakReference<ImageView>>()

        fun addTarget(view: ImageView) {
            targets.add(WeakReference(view))
        }

        override fun detach(view: ImageView) {
            targets.removeAll { it.get() == null || it.get() === view }
            if (targets.isEmpty()) cancelled = true
        }

        fun deliver(bitmap: Bitmap) {
            if (cancelled) return
            for (ref in targets) {
                val view = ref.get() ?: continue
                if (view.tag !== this) continue
                view.setImageBitmap(bitmap)
            }
            targets.removeAll { it.get() == null }
        }
    }

    private class DeferredLoad(
        val listener: View.OnLayoutChangeListener
    ) : Pending {
        override fun detach(view: ImageView) {
            view.removeOnLayoutChangeListener(listener)
        }
    }

    private class DecodeTask(val priority: Int, val work: () -> Unit) : Runnable {
        override fun run() = work()
    }

    private class SizeIndex {
        private val byUrl = HashMap<String, TreeMap<Long, MutableList<String>>>()
        private val meta = HashMap<String, KeyMeta>()

        private class KeyMeta(val url: String, val area: Long)

        @Synchronized
        fun add(url: String, w: Int, h: Int, key: String) {
            val area = w.toLong() * h
            byUrl.getOrPut(url) { TreeMap() }
                .getOrPut(area) { ArrayList(1) }
                .add(key)
            meta[key] = KeyMeta(url, area)
        }

        @Synchronized
        fun remove(key: String) {
            val m = meta.remove(key) ?: return
            val tree = byUrl[m.url] ?: return
            val bucket = tree[m.area] ?: return
            bucket.remove(key)
            if (bucket.isEmpty()) tree.remove(m.area)
            if (tree.isEmpty()) byUrl.remove(m.url)
        }

        /** 返回面积最接近的缓存键；主线程只做两次 TreeMap 定位，不复制缓存。 */
        @Synchronized
        fun nearest(url: String, w: Int, h: Int): String? {
            val tree = byUrl[url] ?: return null
            val area = w.toLong() * h
            val ceil = tree.ceilingEntry(area)
            val floor = tree.floorEntry(area)
            val up = ceil?.value?.lastOrNull()
            val down = floor?.value?.lastOrNull()
            return when {
                up == null -> down
                down == null -> up
                ceil.key - area <= area - floor.key -> up
                else -> down
            }
        }
    }

    companion object {
        private const val THREADS = 4
        private const val PRIORITY_VISIBLE = 0
        private const val PRIORITY_PREFETCH = 1

        private fun priorityOf(task: Runnable): Int =
            (task as? DecodeTask)?.priority ?: Int.MAX_VALUE

        @Volatile
        private var instance: FastImageLoader? = null

        fun init(
            context: Context,
            memoryFraction: Float = 0.25f,
            diskBytes: Long = 64L * 1024 * 1024
        ): FastImageLoader {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val max = Runtime.getRuntime().maxMemory()
                val mem = (max * memoryFraction).toInt().coerceIn(4 * 1024 * 1024, 64 * 1024 * 1024)
                return FastImageLoader(context.applicationContext, mem, diskBytes)
                    .also { instance = it }
            }
        }

        fun get(): FastImageLoader = instance
            ?: throw IllegalStateException("FastImageLoader.init(context) 未调用")
    }
}

internal fun decodeSampled(
    bytes: ByteArray,
    reqW: Int,
    reqH: Int,
    config: Bitmap.Config
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val srcW = bounds.outWidth
    val srcH = bounds.outHeight
    if (srcW <= 0 || srcH <= 0) return null

    var sample = 1
    if (reqW > 0 && reqH > 0) {
        while (srcW / (sample * 2) >= reqW && srcH / (sample * 2) >= reqH) sample *= 2
    }

    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = config
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

    if (reqW <= 0 || reqH <= 0) return decoded
    // 采样只按 2 的幂，结果可能仍明显大于目标；再缩一次，省掉每帧的绘制缩放
    val scale = maxOf(reqW.toFloat() / decoded.width, reqH.toFloat() / decoded.height)
    if (scale >= 0.75f) return decoded
    val w = (decoded.width * scale).toInt().coerceAtLeast(1)
    val h = (decoded.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(decoded, w, h, true)
    if (scaled !== decoded) decoded.recycle()
    return scaled
}

internal class DiskStore(private val dir: File, private val maxBytes: Long) {

    init {
        dir.mkdirs()
        // 上次写入被打断会留下半截文件
        dir.listFiles()?.forEach { if (it.name.endsWith(TMP_SUFFIX)) it.delete() }
    }

    fun get(key: String): ByteArray? = synchronized(this) {
        val f = fileFor(key)
        if (!f.exists()) return null
        val bytes = runCatching { f.readBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            f.delete()
            return null
        }
        f.setLastModified(System.currentTimeMillis())
        bytes
    }

    fun put(key: String, bytes: ByteArray) = synchronized(this) {
        val target = fileFor(key)
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        runCatching {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            // rename 是原子的：读到的一定是完整文件，不会出现半截
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) tmp.delete()
            }
            trim()
        }.onFailure { tmp.delete() }
        Unit
    }

    fun clear() = synchronized(this) {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun trim() {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= maxBytes) return
            total -= f.length()
            f.delete()
        }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(dir, name)
    }

    private companion object {
        const val TMP_SUFFIX = ".tmp"
    }
}

/** 离线自测用：local://seed/WxH 生成真实 JPEG，走完整解码链路。 */
internal object SyntheticSource {
    private val cache = HashMap<String, ByteArray>()

    fun bytes(url: String): ByteArray? = synchronized(cache) {
        cache[url] ?: runCatching {
            val spec = url.removePrefix("local://")
            val seed = spec.substringBefore('/').hashCode()
            val dims = spec.substringAfter('/')
            val w = dims.substringBefore('x').toInt()
            val h = dims.substringAfter('x').toInt()
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint()
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(
                    0xFF000000.toInt() or (seed and 0xFFFFFF),
                    0xFF000000.toInt() or ((seed * 31) and 0xFFFFFF)
                ),
                null, android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            bitmap.recycle()
            out.toByteArray()
        }.getOrNull()?.also { cache[url] = it }
    }
}
