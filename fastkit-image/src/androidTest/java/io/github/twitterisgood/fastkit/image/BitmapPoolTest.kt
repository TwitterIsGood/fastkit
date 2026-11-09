package io.github.twitterisgood.fastkit.image

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BitmapPoolTest {

    private fun bitmap(w: Int, h: Int, config: Bitmap.Config = Bitmap.Config.RGB_565) =
        Bitmap.createBitmap(w, h, config)

    @Test
    fun reusesOnlyWhenSizeAndConfigMatch() {
        val pool = BitmapPool(1 shl 20)
        val b = bitmap(64, 64)
        pool.offer(b)

        assertNull("尺寸不同不能复用", pool.take(32, 64, Bitmap.Config.RGB_565))
        assertNull("config 不同不能复用", pool.take(64, 64, Bitmap.Config.ARGB_8888))
        assertSame(b, pool.take(64, 64, Bitmap.Config.RGB_565))
        assertNull("取走后不应再命中", pool.take(64, 64, Bitmap.Config.RGB_565))
    }

    @Test
    fun rejectsBitmapsLargerThanCapacity() {
        val pool = BitmapPool(1024)
        // 128x128 RGB_565 = 32768 字节，超过 1KB 上限
        pool.offer(bitmap(128, 128))
        assertNull(pool.take(128, 128, Bitmap.Config.RGB_565))
    }

    @Test
    fun evictsOldestWhenFull() {
        // 64x64 RGB_565 = 8192 字节，只能放一张
        val pool = BitmapPool(8192)
        val first = bitmap(64, 64)
        val second = bitmap(64, 64)
        pool.offer(first)
        pool.offer(second)

        val got = pool.take(64, 64, Bitmap.Config.RGB_565)
        assertNotNull(got)
        assertSame("应保留较新的那张", second, got)
    }

    @Test
    fun clearDropsEverything() {
        val pool = BitmapPool(1 shl 20)
        pool.offer(bitmap(8, 8))
        pool.clear()
        assertNull(pool.take(8, 8, Bitmap.Config.RGB_565))
    }

    @Test
    fun rejectsImmutableBitmaps() {
        val pool = BitmapPool(1 shl 20)
        // 从颜色数组创建的位图一定是不可变的（createBitmap/createScaledBitmap 的产物在旧版本上不保证）
        val immutable = Bitmap.createBitmap(
            IntArray(16 * 16), 16, 16, Bitmap.Config.RGB_565
        )
        assertFalse("测试前提：这张图必须不可变", immutable.isMutable)
        pool.offer(immutable)
        assertEquals(null, pool.take(16, 16, Bitmap.Config.RGB_565))
    }
}
