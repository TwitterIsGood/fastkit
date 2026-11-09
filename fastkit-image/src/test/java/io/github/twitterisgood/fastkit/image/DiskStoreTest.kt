package io.github.twitterisgood.fastkit.image

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir() = tmp.newFolder("disk")

    @Test
    fun `写入后可读回`() {
        val store = DiskStore(dir(), 1024)
        val bytes = ByteArray(200) { it.toByte() }
        store.put("https://a/1.jpg", bytes)
        assertArrayEquals(bytes, store.get("https://a/1.jpg"))
        assertNull(store.get("https://a/2.jpg"))
    }

    @Test
    fun `落盘不留临时文件`() {
        val d = dir()
        val store = DiskStore(d, 1024)
        store.put("k", ByteArray(10))
        assertTrue(d.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `首次读盘时清掉上次写入的残片`() {
        val d = dir()
        File(d, "half-written.tmp").writeBytes(ByteArray(5))
        val store = DiskStore(d, 1024)
        // 构造不能碰磁盘（会在 Application.onCreate 主线程上执行），所以清理推迟到第一次访问
        assertTrue(d.listFiles()!!.any { it.name.endsWith(".tmp") })
        store.get("miss")
        assertTrue(d.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `超限时删最久未访问的`() {
        val d = dir()
        val store = DiskStore(d, 100)
        store.put("old", ByteArray(60))
        d.listFiles()!!.forEach { it.setLastModified(1_000L) }
        store.put("new", ByteArray(60))

        assertNull(store.get("old"))
        assertArrayEquals(ByteArray(60), store.get("new"))
        assertTrue(d.listFiles()!!.sumOf { it.length() } <= 100)
    }

    @Test
    fun `空文件视为未命中并删除`() {
        val d = dir()
        val store = DiskStore(d, 1024)
        store.put("k", ByteArray(10))
        d.listFiles()!!.forEach { it.writeBytes(ByteArray(0)) }
        assertNull(store.get("k"))
        assertTrue(d.listFiles()!!.isEmpty())
    }
}
