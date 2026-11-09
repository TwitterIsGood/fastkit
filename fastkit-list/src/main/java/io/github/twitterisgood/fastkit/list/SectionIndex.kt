package io.github.twitterisgood.fastkit.list

/**
 * 区块前缀和索引：把"全局 position"映射到"第几个区块 + 区块内第几项"。
 *
 * 纯 Kotlin、无 Android 依赖，便于单测。`offsets[i]` 是第 i 个区块结束后的累计 item 数。
 */
internal class SectionIndex {

    private var offsets = IntArray(0)

    var total: Int = 0
        private set

    /** 按各区块的 item 数重建前缀和。 */
    fun rebuild(counts: List<Int>) {
        offsets = IntArray(counts.size)
        var acc = 0
        for (i in counts.indices) {
            acc += counts[i]
            offsets[i] = acc
        }
        total = acc
    }

    fun size(): Int = offsets.size

    /** 第 [index] 个区块的起始全局位置。 */
    fun offsetOf(index: Int): Int = if (index <= 0) 0 else offsets[index - 1]

    /** 全局位置落在哪个区块；空列表或越界时返回 0。 */
    fun locate(position: Int): Int {
        if (offsets.isEmpty()) return 0
        var lo = 0
        var hi = offsets.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[mid] <= position) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 全局位置在所属区块内的下标。 */
    fun localOf(position: Int): Int {
        val index = locate(position)
        return position - offsetOf(index)
    }

    /** 一次二分同时拿到区块下标和区块内下标；bind 热路径用这个。 */
    fun locateLocal(position: Int): Pair<Int, Int> {
        val index = locate(position)
        return index to (position - offsetOf(index))
    }
}
