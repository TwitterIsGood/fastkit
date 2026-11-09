package io.github.twitterisgood.fastkit.image

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DecodeSampledTest {

    private fun jpeg(w: Int, h: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (x in 0 until w) {
            for (y in 0 until h) bitmap.setPixel(x, y, 0xFF000000.toInt() or (x * 4096 + y))
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun downsamplesByPowerOfTwo() {
        val bytes = jpeg(256, 256)
        val decoded = decodeSampled(bytes, 64, 64, Bitmap.Config.RGB_565)
        assertNotNull(decoded)
        assertEquals(64, decoded!!.width)
        assertEquals(64, decoded.height)
    }

    @Test
    fun reusesPooledBitmapInsteadOfAllocating() {
        val bytes = jpeg(64, 64)
        val pool = BitmapPool(1 shl 20)

        val first = decodeSampled(bytes, 64, 64, Bitmap.Config.RGB_565, pool)
        assertNotNull(first)
        assertTrue(
            "解码结果必须可变才能回池：config=${first!!.config} mutable=${first.isMutable}",
            first.isMutable
        )
        // 把它当成"已经没人用"的位图还回池子，下一次解码应该直接写进这块内存
        pool.offer(first)

        val second = decodeSampled(bytes, 64, 64, Bitmap.Config.RGB_565, pool)
        assertNotNull(second)
        assertSame("命中池子时应复用同一实例", first, second)
    }

    @Test
    fun fallsBackWhenPoolHasNoMatch() {
        val bytes = jpeg(64, 64)
        val pool = BitmapPool(1 shl 20)
        val other = Bitmap.createBitmap(32, 32, Bitmap.Config.RGB_565)
        pool.offer(other)

        val decoded = decodeSampled(bytes, 64, 64, Bitmap.Config.RGB_565, pool)
        assertNotNull(decoded)
        assertNotSame("尺寸不匹配就不该拿它来写", other, decoded)
        assertEquals(64, decoded!!.width)
    }
}
