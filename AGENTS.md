# AGENTS.md

本工程是后续所有 Android App 的基线。下面的规则是硬约束，新增代码默认遵守；
要违反必须在 PR 里写明理由和实测数据。

## 硬规则

### 列表

- **禁止垂直 RecyclerView 嵌套垂直 RecyclerView。** 一屏多区块用 `FlatSectionAdapter`，
  每个区块实现一个 `Section`（只描述 item 数量、布局、绑定）。
- 新增区块 = 新增一个 `Section` 子类，不得改动 `FlatSectionAdapter` 的定位逻辑。
- 列表初始化统一调用 `RecyclerView.setupForFeed()`：关闭 itemAnimator、固定尺寸、
  限制每类 ViewHolder 缓存。
- `onViewRecycled` 必须做清理（取消图片、清空 drawable、解绑监听）。
- 需要横向滚动时，子列表 `setNestedScrollingEnabled(false)`。

### 图片

- **所有网络图片必须走 `FastImageLoader`。** 不允许直接 `BitmapFactory.decodeStream`
  贴到 ImageView。
- 缓存键必须含目标宽高。不允许出现只按 URL 索引的图片缓存。
- 解码不得发生在主线程。主线程只允许：命中缓存后 `setImageBitmap`。
- 位图优先 `RGB_565`；需要透明/渐变时才用 `ARGB_8888`。
- View 复用或销毁时取消未完成的请求。
- 图片 URL 若由自家 CDN 提供，一律经 `CdnSize.apply()` 带上尺寸参数。

### 渲染与线程

- 列表滚动期间不做同步 I/O、数据库查询、JSON 解析。
- 任何耗时超过 ~4ms 的主线程工作都要挪到后台。
- 不在 `onBindViewHolder` 里创建对象池之外的重对象（`Paint`、`Path`、格式化器要复用）。

## 性能验证

改完列表/图片相关代码，必须给出实测数据，不接受主观描述。

```bash
# 冷启动
adb shell am force-stop <pkg>
adb shell am start -W -n <pkg>/.MainActivity

# 滑动帧数据
adb shell dumpsys gfxinfo <pkg> reset
adb shell input swipe 360 1000 360 200 200   # 重复数次
adb shell dumpsys gfxinfo <pkg>
```

判定口径（60Hz，单帧预算 16.7ms）：

| 指标 | 可接受 | 需要修 |
|---|---|---|
| Janky frames | < 1% | > 3% |
| p90 | ≤ 8ms | > 12ms |
| p99 | ≤ 16ms | > 32ms |

测的时候要区分「有缓存」和「清空内存+磁盘缓存」两种，后者才反映真实最坏情况。
屏幕锁定会让 gfxinfo 采不到帧，测前先 `input keyevent KEYCODE_WAKEUP`。

## 构建

```bash
JAVA_HOME=<jdk17> ./gradlew :app:assembleDebug
```

- Gradle 8.5+，AGP 8.5，Kotlin 2.1，JDK 17。
- `local.properties` 不入库，由各自的机器生成。
- 新增依赖前先确认对低端机的代价；能用平台 API 就不加库。

## 不要做的事

- 不要为了"功能更全"引入新的图片加载库，会与 `FastImageLoader` 的缓存语义冲突。
- 不要在 item 布局里用 `wrap_content` 承载图片；尺寸不确定会导致请求被推迟或尺寸跳变。
- 不要在 `onBindViewHolder` 里 `requestLayout()`。
- 不要用 `notifyDataSetChanged()`，用区块级的 `notifyItemRange*`。
- 不要把第三方 App 的内部实现细节、类名或资源写进代码和文档。

## 目录约定

```
app/src/main/java/<namespace>/fastkit/
  FastImageLoader.kt        图片加载（缓存/解码/取消/预取）
  FlatSectionAdapter.kt     扁平列表
  PrefetchScrollListener.kt 滚动预取
  CdnSize.kt                图床尺寸参数
  DemoActivity.kt           示例
```

新增模块放进独立 package，不要塞进 `fastkit` 根包。
