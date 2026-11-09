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
    private val pool = BitmapPool(minOf(memoryBytes / 4, 8 * 1024 * 1024))

    // 引用计数 + 在屏追踪：只有"不在缓存、也没被任何 view 显示"的位图才允许进池复用
    private val recycleLock = Any()
    private val refs = ConcurrentHashMap<Bitmap, java.util.concurrent.atomic.AtomicInteger>()
    // newKeySet() 要 API 24，minSdk 21 只能用这个等价写法
    private val cached: MutableSet<Bitmap> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<Bitmap, Boolean>())
    private val viewBitmaps = java.util.WeakHashMap<ImageView, Bitmap>()

    private val memory = object : LruCache<String, Bitmap>(memoryBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            index.remove(key)
            cached.remove(oldValue)
            synchronized(recycleLock) {
                if ((refs[oldValue]?.get() ?: 0) == 0) {
                    refs.remove(oldValue)
                    pool.offer(oldValue)
                }
            }
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

    /**
     * 尺寸未知时先挂起，等 onLayout 拿到真实宽高再发请求（Play 的 FIFE 图也是这个行为）。
     *
     * [onSettled] 在这次加载真正结束时回调一次，成功、失败、被复用取消都算，永远在主线程。
     * 首屏计数、转场、埋点都该用它，不要靠轮询 `drawable` 判活。
     */
    fun load(view: ImageView, url: String, onSettled: (() -> Unit)? = null) {
        val (w, h) = viewSize(view)
        if (w > 0 && h > 0) {
            load(view, url, w, h, onSettled)
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
                load(v as ImageView, url, pending.onSettled)
            }
        }
        view.tag = DeferredLoad(listener, onSettled)
        view.addOnLayoutChangeListener(listener)
    }

    fun load(view: ImageView, url: String, width: Int, height: Int, onSettled: (() -> Unit)? = null) {
        release(view)
        val exact = memory.get(keyOf(url, width, height))
        if (exact != null) {
            show(view, exact)
            onSettled?.invoke()
            return
        }
        // 尺寸够接近的缓存图直接当结果；差太多的先当占位，同时请求正确尺寸
        val near = nearest(url, width, height)
        if (near != null && !isTooSmall(near, width, height)) {
            show(view, near)
            onSettled?.invoke()
            return
        }
        val req = enqueue(url, width, height, PRIORITY_VISIBLE)
        req.addTarget(view, onSettled)
        view.tag = req
        if (near != null) show(view, near)
    }

    /** 滚动预取：只解到内存，不碰 UI；排在可见请求之后。 */
    fun prefetch(url: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (memory.get(keyOf(url, width, height)) != null) return
        nearest(url, width, height)?.let { if (!isTooSmall(it, width, height)) return }
        enqueue(url, width, height, PRIORITY_PREFETCH)
    }

    /** 取消该 view 的未完成请求，保留当前显示的图。 */
    fun cancel(view: ImageView) = release(view)

    /** 把位图贴到 view 上，并登记它在屏，防止被复用池覆盖。 */
    private fun show(view: ImageView, bitmap: Bitmap) {
        view.setImageBitmap(bitmap)
        synchronized(recycleLock) {
            val old = viewBitmaps.put(view, bitmap)
            if (old === bitmap) return
            if (old != null) unpinLocked(old)
            val counter = refs[bitmap]
            if (counter == null) {
                refs[bitmap] = java.util.concurrent.atomic.AtomicInteger(1)
            } else {
                counter.incrementAndGet()
            }
        }
    }

    private fun unpinLocked(bitmap: Bitmap) {
        val counter = refs[bitmap] ?: return
        if (counter.decrementAndGet() > 0) return
        refs.remove(bitmap)
        if (!cached.contains(bitmap)) pool.offer(bitmap)
    }

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
        if (target == 0) pool.clear()
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

            // 有 UI 在等时先解一张 1/4 尺寸的粗图，内容先上屏，再从模糊换到清晰。
            // 预取没有 UI，跳过这一遍。
            if (req.hasTargets() && req.width * req.height >= MIN_COARSE_AREA) {
                val cw = (req.width / COARSE_DIVISOR).coerceAtLeast(1)
                val ch = (req.height / COARSE_DIVISOR).coerceAtLeast(1)
                decodeWithRetry(bytes, cw, ch)?.let { coarse ->
                    if (req.cancelled) return
                    main.post { req.deliver(coarse, final = false) }
                }
            }

            if (req.cancelled) return
            val bitmap = decodeWithRetry(bytes, req.width, req.height) ?: return
            cached.add(bitmap)
            memory.put(req.key, bitmap)
            index.add(req.url, req.width, req.height, req.key)
            main.post { req.deliver(bitmap, final = true) }
        } finally {
            inFlight.remove(req.key, req)
            // 失败或在中途被取消时不会走到 deliver，兜底保证 onSettled 一定回调
            main.post { req.settle() }
        }
    }

    private fun decodeWithRetry(bytes: ByteArray, w: Int, h: Int): Bitmap? = try {
        decodeSampled(bytes, w, h, decodeConfig, pool)
    } catch (oom: OutOfMemoryError) {
        // 低端机上大图解码是真的会 OOM，清空缓存后再试一次
        memory.evictAll()
        pool.clear()
        try {
            decodeSampled(bytes, w, h, decodeConfig, pool)
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

    /** 面积不到目标一半的缓存图只能当占位，不能当结果。 */
    private fun isTooSmall(bitmap: Bitmap, w: Int, h: Int): Boolean =
        bitmap.width.toLong() * bitmap.height * 2 < w.toLong() * h

    private fun release(view: ImageView) {
        (view.tag as? Pending)?.detach(view)
        view.tag = null
        synchronized(recycleLock) {
            viewBitmaps.remove(view)?.let { unpinLocked(it) }
        }
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
        private val settlers = CopyOnWriteArrayList<() -> Unit>()

        fun addTarget(view: ImageView, onSettled: (() -> Unit)?) {
            targets.add(WeakReference(view))
            if (onSettled != null) settlers.add(onSettled)
        }

        fun hasTargets(): Boolean = targets.isNotEmpty()

        override fun detach(view: ImageView) {
            targets.removeAll { it.get() == null || it.get() === view }
            if (targets.isEmpty()) {
                cancelled = true
                settle()
            }
        }

        /**
         * 每个请求只结算一次。callers 分布在主线程（deliver / detach）和 worker 兜底，
         * 靠清空 settlers 保证幂等。
         */
        fun settle() {
            if (settlers.isEmpty()) return
            val pending = settlers.toList()
            settlers.clear()
            pending.forEach { it() }
        }

        /** [final] 为 false 时是 1/4 粗图，只上屏不结算。 */
        fun deliver(bitmap: Bitmap, final: Boolean) {
            if (cancelled) return
            for (ref in targets) {
                val view = ref.get() ?: continue
                if (view.tag !== this) continue
                show(view, bitmap)
            }
            targets.removeAll { it.get() == null }
            if (final) settle()
        }
    }

    private class DeferredLoad(
        val listener: View.OnLayoutChangeListener,
        val onSettled: (() -> Unit)?
    ) : Pending {
        override fun detach(view: ImageView) {
            view.removeOnLayoutChangeListener(listener)
            onSettled?.invoke()
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
        private const val COARSE_DIVISOR = 4
        private const val MIN_COARSE_AREA = 128 * 128

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
    config: Bitmap.Config,
    pool: BitmapPool? = null
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
        // 解码结果默认是不可变的，而 inBitmap 只接受可变位图；打开它，结果才能回池
        inMutable = pool != null
    }
    // 采样后的尺寸（向上取整）拿来在池里找一个同尺寸的空闲位图，直接写进去，省一次分配
    val slotW = (srcW + sample - 1) / sample
    val slotH = (srcH + sample - 1) / sample
    pool?.take(slotW, slotH, config)?.let { opts.inBitmap = it }

    val decoded = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (e: IllegalArgumentException) {
        // 不同版本对 inSampleSize 的取整规则不一致，候选对不上就退回普通解码
        opts.inBitmap = null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } ?: return null

    if (reqW <= 0 || reqH <= 0) return decoded
    // 采样只按 2 的幂，结果可能仍明显大于目标；再缩一次，省掉每帧的绘制缩放
    val scale = maxOf(reqW.toFloat() / decoded.width, reqH.toFloat() / decoded.height)
    if (scale >= 0.75f) return decoded
    val w = (decoded.width * scale).toInt().coerceAtLeast(1)
    val h = (decoded.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(decoded, w, h, true)
    if (scaled !== decoded) {
        // 缩放结果不可变，不能回池；中间那张采样图可以留着下次复用
        if (decoded.isMutable) pool?.offer(decoded) else decoded.recycle()
    }
    return scaled
}

internal class DiskStore(private val dir: File, private val maxBytes: Long) {

    private var ready = false

    /**
     * 建目录和扫残留都是磁盘 I/O。构造函数会在 `init()` 里被调用，而 `init()` 通常在
     * `Application.onCreate` 主线程上，所以这里必须懒到第一次真正读盘时再做。
     */
    private fun prepare() {
        if (ready) return
        dir.mkdirs()
        // 上次写入被打断会留下半截文件
        dir.listFiles()?.forEach { if (it.name.endsWith(TMP_SUFFIX)) it.delete() }
        ready = true
    }

    fun get(key: String): ByteArray? = synchronized(this) {
        prepare()
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
        prepare()
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
        prepare()
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
