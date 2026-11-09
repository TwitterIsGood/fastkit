package io.github.twitterisgood.fastkit.image

/**
 * 服务端缩放参数拼接 —— Play 的 `=w360-h540-e365` 就是同一套思路。
 * 只要图片 URL 走自己的 CDN，就能只下"屏幕需要的尺寸"。
 */
object CdnSize {

    enum class Provider {
        /** Google FIFE / 支持 `=w-h` 的图床 */
        FIFE,
        ALIYUN_OSS,
        TENCENT_COS,
        QINIU,
        /** 需要传 [apply] 的 base 参数，形如 https://img.example.com */
        IMGPROXY,
        /** 不支持服务端裁剪：原样返回，交给客户端 inSampleSize */
        NONE
    }

    fun apply(
        url: String,
        width: Int,
        height: Int,
        provider: Provider,
        imgproxyBase: String = ""
    ): String {
        if (width <= 0 && height <= 0) return url
        val sep = if (url.contains('?')) '&' else '?'
        return when (provider) {
            Provider.NONE -> url
            Provider.FIFE -> "$url=w${width}-h${height}-e365"
            Provider.ALIYUN_OSS ->
                "$url${sep}x-oss-process=image/resize,w_$width,h_$height,m_lfit"
            Provider.TENCENT_COS ->
                "$url${sep}imageMogr2/thumbnail/${width}x$height"
            Provider.QINIU -> "$url${sep}imageView2/2/w/$width/h/$height"
            Provider.IMGPROXY -> {
                if (imgproxyBase.isBlank()) return url
                val encoded = java.net.URLEncoder.encode(url, "UTF-8")
                "${imgproxyBase.trimEnd('/')}/unsafe/rs:fill:$width:$height/plain/$encoded"
            }
        }
    }
}
