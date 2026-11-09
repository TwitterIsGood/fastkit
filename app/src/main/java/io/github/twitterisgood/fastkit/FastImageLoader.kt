package io.github.twitterisgood.fastkit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 尺寸感知 + 两级缓存的图片加载器。
 *
 * 与常见库的区别：缓存键永远是 (url, 显示宽, 显示高)，并且允许"近似尺寸"命中，
 * 这样滚动时不会因为控件差 1px 就重新解码。
 */
class FastImageLoader private constructor(
    appContext: Context,
    memoryBytes: Int,
    diskBytes: Long
) {

    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(4)
    private val prefetchWorker = Executors.newSingleThreadExecutor()
    private val disk = DiskStore(File(appContext.cacheDir, "fastimg"), diskBytes)

    private val memory = object : LruCache<String, Bitmap>(memoryBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @Volatile
    var decodeConfig: Bitmap.Config = Bitmap.Config.RGB_565

    fun load(view: ImageView, url: String) {
        val (w, h) = viewSize(view)
        if (w > 0 && h > 0) {
            load(view, url, w, h)
            return
        }
        cancel(view)
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                v.removeOnLayoutChangeListener(this)
                val pending = v.tag as? DeferredLoad ?: return
                if (pending.url == url) loadViewInner(v as ImageView, url)
            }
        }
        val deferred = DeferredLoad(view, url, listener)
        view.tag = deferred
        view.addOnLayoutChangeListener(listener)
    }

    fun load(view: ImageView, url: String, width: Int, height: Int) {
        cancel(view)
        val req = Request(url, width, height)
        view.tag = req
        val hit = memory.get(keyOf(url, width, height)) ?: nearest(url, width, height)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        submit(view, req, workers)
    }

    /** 滚动预取：只解到内存，不碰 UI。 */
    fun prefetch(url: String, width: Int, height: Int) {
        if (memory.get(keyOf(url, width, height)) != null) return
        submit(null, Request(url, width, height), prefetchWorker)
    }

    fun cancel(view: ImageView) {
        (view.tag as? Pending)?.cancel()
        view.tag = null
    }

    fun clearMemory() = memory.evictAll()

    fun clearDisk() = disk.clear()

    private fun loadViewInner(view: ImageView, url: String) {
        val (w, h) = viewSize(view)
        if (w > 0 && h > 0) load(view, url, w, h)
    }

    private fun submit(target: ImageView?, req: Request, executor: java.util.concurrent.Executor) {
        executor.execute {
            if (req.cancelled.get()) return@execute
            val bytes = bytesFor(req.url) ?: return@execute
            if (req.cancelled.get()) return@execute
            val bitmap = decodeSampled(bytes, req.width, req.height, decodeConfig)
                ?: return@execute
            memory.put(keyOf(req.url, req.width, req.height), bitmap)
            if (target == null) return@execute
            main.post {
                if (target.tag !== req || req.cancelled.get()) return@post
                target.setImageBitmap(bitmap)
            }
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

    private fun nearest(url: String, w: Int, h: Int): Bitmap? {
        var best: Bitmap? = null
        var bestScore = Long.MAX_VALUE
        for ((key, bitmap) in memory.snapshot()) {
            if (!key.startsWith("#$url#")) continue
            if (bitmap.isRecycled) continue
            val score = kotlin.math.abs(bitmap.width.toLong() * bitmap.height - w.toLong() * h)
            if (score < bestScore) {
                bestScore = score
                best = bitmap
            }
        }
        return best
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
        fun cancel()
    }

    private class Request(val url: String, val width: Int, val height: Int) : Pending {
        val cancelled = AtomicBoolean(false)
        override fun cancel() {
            cancelled.set(true)
        }
    }

    private class DeferredLoad(
        val view: ImageView,
        val url: String,
        val listener: View.OnLayoutChangeListener
    ) : Pending {
        override fun cancel() {
            view.removeOnLayoutChangeListener(listener)
        }
    }

    companion object {
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
    }

    fun get(key: String): ByteArray? = synchronized(this) {
        val f = fileFor(key)
        if (!f.exists()) return null
        f.setLastModified(System.currentTimeMillis())
        runCatching { f.readBytes() }.getOrNull()
    }

    fun put(key: String, bytes: ByteArray) = synchronized(this) {
        runCatching {
            fileFor(key).writeBytes(bytes)
            trim()
        }
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
