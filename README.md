# fastkit

一个把"列表不卡"做成默认值的 Android 脚手架：扁平 Section 列表 + 尺寸感知图片加载。

面向的是一类很常见的问题——商品页、内容流、应用商店这类"一屏多个卡片"的界面，
在低端机和老设备上滑动掉帧。这份代码把其中占大头的那几件事固化成了默认实现。

```
列表层  FlatSectionAdapter   一条 RecyclerView 承载所有区块，前缀和定位
图片层  FastImageLoader      缓存键含尺寸、近似尺寸命中、后台解码、可取消
接入层  CdnSize              按控件尺寸向图床要图（OSS / COS / 七牛 / imgproxy / FIFE）
滚动层  PrefetchScrollListener 按滚动方向提前解码
```

## 实测

在一台 2016 年的 Android 6.0 / 3GB 内存设备（720×1280）上，601 个 item 的列表连续滑动：

| 场景 | 帧数 | 卡顿率 | p90 | p99 |
|---|---|---|---|---|
| 有缓存 | 394 | 0.25% | 8ms | 15ms |
| 清空内存+磁盘缓存 | 394 | 0.00% | 7ms | 12ms |

第二行是关键：缓存全空也不掉帧，说明解码完全没占用 UI 线程。
冷启动 `am start -W` 为 374ms。

## 运行

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Demo 用 `local://seed/WxH` 在本地生成 JPEG，走完整的下载→缓存→两遍解码链路，
不依赖网络。接真实图床时替换 `FastImageLoader.bytesFor()` 里的分支即可。

## 六条默认规则

1. **整页一条 RecyclerView。** 区块只是数据源，不嵌套垂直列表。
2. **图片缓存键带尺寸。** `(url, w, h)`，尺寸接近就复用，差 1px 不重新解码。
3. **尺寸未知先不发请求。** 等 `onLayout` 拿到真实宽高再发。
4. **解码永远在后台线程。** UI 线程只做 `setImageBitmap`。
5. **复用即可取消。** `onViewRecycled` 里 cancel 并清空 drawable，防串图。
6. **能服务端裁图就别客户端裁。** 请求时就把宽高带给 CDN。

细节见 [AGENTS.md](AGENTS.md)。

## License

Apache-2.0
