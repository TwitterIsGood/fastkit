package io.github.twitterisgood.fastkit.image

import org.junit.Assert.assertEquals
import org.junit.Test

class CdnSizeTest {

    private val url = "https://cdn.example.com/a/b.jpg"

    @Test
    fun `fife 尾缀拼接`() {
        assertEquals(
            "$url=w360-h540-e365",
            CdnSize.apply(url, 360, 540, CdnSize.Provider.FIFE)
        )
    }

    @Test
    fun `aliyun 首个参数用问号`() {
        assertEquals(
            "$url?x-oss-process=image/resize,w_360,h_540,m_lfit",
            CdnSize.apply(url, 360, 540, CdnSize.Provider.ALIYUN_OSS)
        )
    }

    @Test
    fun `aliyun 已有查询串时用与号`() {
        assertEquals(
            "$url?token=1&x-oss-process=image/resize,w_360,h_540,m_lfit",
            CdnSize.apply("$url?token=1", 360, 540, CdnSize.Provider.ALIYUN_OSS)
        )
    }

    @Test
    fun `cos 与 qiniu`() {
        assertEquals(
            "$url?imageMogr2/thumbnail/360x540",
            CdnSize.apply(url, 360, 540, CdnSize.Provider.TENCENT_COS)
        )
        assertEquals(
            "$url?imageView2/2/w/360/h/540",
            CdnSize.apply(url, 360, 540, CdnSize.Provider.QINIU)
        )
    }

    @Test
    fun `imgproxy 需要 base 否则原样返回`() {
        assertEquals(url, CdnSize.apply(url, 360, 540, CdnSize.Provider.IMGPROXY))
        assertEquals(
            "https://img.example.com/unsafe/rs:fill:360:540/plain/" +
                "https%3A%2F%2Fcdn.example.com%2Fa%2Fb.jpg",
            CdnSize.apply(
                url, 360, 540, CdnSize.Provider.IMGPROXY,
                imgproxyBase = "https://img.example.com/"
            )
        )
    }

    @Test
    fun `none 与零尺寸不改变 url`() {
        assertEquals(url, CdnSize.apply(url, 360, 540, CdnSize.Provider.NONE))
        assertEquals(url, CdnSize.apply(url, 0, 0, CdnSize.Provider.FIFE))
    }
}
