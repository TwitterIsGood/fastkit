# fastkit

把"列表不卡"做成默认值的 Android 基线，拆成两套**互不依赖**的 SDK：

| 模块 | 依赖 | 内容 |
|---|---|---|
| `:fastkit-list` | `androidx.recyclerview` | `FlatSectionAdapter`、`Section`、`RecyclerView.setupForFeed()`、`PrefetchScrollListener` |
| `:fastkit-image` | 无（平台 API） | `FastImageLoader`、`CdnSize` |
| `:baselineprofile` | `com.android.test` | 采集 `baseline.prof`，把冷启动 + 滑动路径 AOT 编译 |
| `:app` | 前两个 | 601 个 item 的 demo |

只用图片加载就 `implementation(project(":fastkit-image"))`，不用被列表代码牵进来。

```
列表层  FlatSectionAdapter   一条 RecyclerView 承载所有区块，前缀和定位
图片层  FastImageLoader      缓存键含尺寸、近似尺寸命中、后台解码、可取消、请求去重
接入层  CdnSize              按控件尺寸向图床要图（OSS / COS / 七牛 / imgproxy / FIFE）
滚动层  PrefetchScrollListener 按滚动方向提前解码
```

## 实测

在一台 2016 年的 Android 6.0 / 3GB 内存设备（720×1280）上，601 个 item 的列表连续滑动：

| 场景 | 帧数 | 卡顿率 | p90 | p95 | p99 |
|---|---|---|---|---|---|
| 有缓存 | 393 | 0.51% | 8ms | 10ms | 15ms |
| 清空内存+磁盘缓存 | 392 | 0.26% | 7ms | 9ms | 14ms |

第二行是关键：缓存全空也不掉帧，说明解码完全没占用 UI 线程。
两种场景的 `Missed Vsync` 都是 0。冷启动 `am start -W` 取三次：274 / 276 / 280ms。

## 运行

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Demo 用 `local://seed/WxH` 在本地生成 JPEG，走完整的下载→缓存→两遍解码链路，
不依赖网络。接真实图床时替换 `FastImageLoader.bytesFor()` 里的分支即可。

生成 baseline profile 需要一个 API 28+ 的设备或模拟器：

```bash
./gradlew :app:generateBaselineProfile
```

## 默认规则

1. **整页一条 RecyclerView。** 区块只是数据源，不嵌套垂直列表。
2. **图片缓存键带尺寸。** `(url, w, h)`，尺寸接近就复用，索引是 per-url 的 TreeMap。
3. **尺寸未知先不发请求。** 等 `onLayout` 拿到真实宽高再发。
4. **解码永远在后台线程。** UI 线程只做 `setImageBitmap`。
5. **复用即可取消。** `onViewRecycled` 里 `clear()`，防串图。
6. **同一请求只下一遍。** 可见区域优先级高于预取，重复请求共享结果。
7. **局部刷新用 payload。** 区块级 `notifyItemRange*`，不用 `notifyDataSetChanged()`。
8. **能服务端裁图就别客户端裁。** 请求时就把宽高带给 CDN。

细节见 [AGENTS.md](AGENTS.md)。

## License

Apache-2.0
