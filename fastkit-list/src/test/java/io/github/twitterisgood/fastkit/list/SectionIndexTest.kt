package io.github.twitterisgood.fastkit.list

import org.junit.Assert.assertEquals
import org.junit.Test

class SectionIndexTest {

    private fun build(vararg counts: Int): SectionIndex =
        SectionIndex().apply { rebuild(counts.toList()) }

    @Test
    fun `空列表`() {
        val index = build()
        assertEquals(0, index.total)
        assertEquals(0, index.locate(0))
        assertEquals(0, index.localOf(0))
        assertEquals(0, index.offsetOf(0))
    }

    @Test
    fun `区块边界与区块内偏移`() {
        // 3 + 1 + 5 = 9
        val index = build(3, 1, 5)
        assertEquals(9, index.total)
        assertEquals(0, index.offsetOf(0))
        assertEquals(3, index.offsetOf(1))
        assertEquals(4, index.offsetOf(2))

        assertEquals(0, index.locate(0))
        assertEquals(0, index.locate(2))
        assertEquals(1, index.locate(3))
        assertEquals(2, index.locate(4))
        assertEquals(2, index.locate(8))

        assertEquals(2, index.localOf(2))
        assertEquals(0, index.localOf(3))
        assertEquals(4, index.localOf(8))
    }

    @Test
    fun `单个区块与零项区块`() {
        val index = build(7)
        assertEquals(7, index.total)
        assertEquals(6, index.localOf(6))

        // 中间有零项区块时，定位应跳过它
        val withGap = build(2, 0, 3)
        assertEquals(5, withGap.total)
        assertEquals(0, withGap.locate(1))
        assertEquals(2, withGap.locate(2))
        assertEquals(0, withGap.localOf(2))
    }

    @Test
    fun `重建后旧偏移不残留`() {
        val index = build(3, 3)
        index.rebuild(listOf(10))
        assertEquals(10, index.total)
        assertEquals(0, index.locate(9))
        assertEquals(9, index.localOf(9))
    }
}
