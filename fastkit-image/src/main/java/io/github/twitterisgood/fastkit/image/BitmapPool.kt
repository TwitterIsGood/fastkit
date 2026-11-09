package io.github.twitterisgood.fastkit.image

import android.graphics.Bitmap

/**
 * `BitmapFactory.Options.inBitmap` 的回收池。
 *
 * 只接受"尺寸和 config 完全一致"的复用候选：KITKAT 之前要求宽高严格相等，
 * 之后才放宽到"分配字节数足够"。只做精确匹配，在所有 API 上行为一致。
 *
 * 池里的位图必须满足：不在 LRU 缓存里、没有被任何 ImageView 显示（由调用方的引用计数保证），
 * 否则就地复用会把屏幕上正在显示的图覆盖掉。
 */
internal class BitmapPool(private val maxBytes: Int) {

    private val free = ArrayList<Bitmap>(8)
    private var bytes = 0

    @Synchronized
    fun take(width: Int, height: Int, config: Bitmap.Config): Bitmap? {
        var i = 0
        while (i < free.size) {
            val candidate = free[i]
            if (candidate.isRecycled) {
                bytes -= candidate.byteCount
                free.removeAt(i)
                continue
            }
            if (candidate.width == width && candidate.height == height &&
                candidate.config == config && candidate.isMutable
            ) {
                free.removeAt(i)
                bytes -= candidate.byteCount
                return candidate
            }
            i++
        }
        return null
    }

    @Synchronized
    fun offer(bitmap: Bitmap) {
        if (bitmap.isRecycled || !bitmap.isMutable) return
        val size = bitmap.byteCount
        if (size > maxBytes) return
        // 丢掉最老的，给新来的腾地方
        while (bytes + size > maxBytes && free.isNotEmpty()) {
            bytes -= free.removeAt(0).byteCount
        }
        if (bytes + size > maxBytes) return
        free.add(bitmap)
        bytes += size
    }

    @Synchronized
    fun clear() {
        free.clear()
        bytes = 0
    }
}
