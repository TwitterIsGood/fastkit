package io.github.twitterisgood.fastkit

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
        IMGPROXY,
        /** 不支持服务端裁剪：原样返回，交给客户端 inSampleSize */
        NONE
    }

    fun apply(url: String, width: Int, height: Int, provider: Provider): String {
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
                val path = url.substringAfter("://").substringAfter('/', "")
                val host = "https://img.example.com/unsafe/rs:fill:$width:$height/plain/"
                val scheme = url.substringBefore("://")
                host + java.net.URLEncoder.encode("$scheme://$path", "UTF-8")
            }
        }
    }
}
