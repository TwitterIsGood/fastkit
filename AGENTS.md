# AGENTS.md

本工程是后续所有 Android App 的基线。下面的规则是硬约束，新增代码默认遵守；
要违反必须在 PR 里写明理由和实测数据。

## 硬规则

### 列表

- **禁止垂直 RecyclerView 嵌套垂直 RecyclerView。** 一屏多区块用 `FlatSectionAdapter`，
  每个区块实现一个 `Section`（只描述 item 数量、布局、绑定）。
- 新增区块 = 新增一个 `Section` 子类，不得改动 `FlatSectionAdapter` 的定位逻辑。
- 列表初始化统一调用 `RecyclerView.setupForFeed()`（必须在设置 layoutManager 之后）：
  关闭 itemAnimator、固定尺寸、限制每类 ViewHolder 缓存、设置初始预取数量。
- `onViewRecycled` 必须做清理（取消图片、清空 drawable、解绑监听）。
- 局部刷新用 `notifySectionChanged(section, start, count, payload)`，
  区块实现 `onBindPayload()`，不要整块重新 bind。
- 需要横向滚动时，子列表 `setNestedScrollingEnabled(false)`。

### 图片

- **所有网络图片必须走 `FastImageLoader`。** 不允许直接 `BitmapFactory.decodeStream`
  贴到 ImageView。
- 缓存键必须含目标宽高。不允许出现只按 URL 索引的图片缓存。
- 解码不得发生在主线程。主线程只允许：命中缓存后 `setImageBitmap`。
- 位图优先 `RGB_565`；需要透明/渐变时才用 `ARGB_8888`。
- View 复用或销毁时 `clear()`：取消未完成请求并清空 drawable。
- 同一 `(url, w, h)` 的并发请求必须共享一次下载和解码；滚动预取走 `prefetch()`，
  它的优先级低于可见区域。
- 大图必须分层出图：先解 1/4 尺寸的粗图占位，再解目标尺寸覆盖。粗图只上屏、不入缓存，
  预取不做粗图那一遍。近似尺寸的缓存图如果面积不到目标的一半，只能当占位，不能当结果。
- 图片 URL 若由自家 CDN 提供，一律经 `CdnSize.apply()` 带上尺寸参数。
- 磁盘缓存的写入必须是"临时文件 + rename"，不允许直接写目标文件。
- `inBitmap` 复用的位图，必须确认没有 view 还在显示它才允许回池（`BitmapPool` 用引用计数保证）；
  解码选项必须打开 `inMutable`，否则解码结果不可变，`inBitmap` 永远不生效。

### 渲染与线程

- 列表滚动期间不做同步 I/O、数据库查询、JSON 解析。
- 任何耗时超过 ~4ms 的主线程工作都要挪到后台。
- 不在 `onBindViewHolder` 里创建对象池之外的重对象（`Paint`、`Path`、格式化器要复用）。

## 性能验证

改完列表/图片相关代码，必须给出实测数据，不接受主观描述。

```bash
# 冷启动
adb shell am force-stop <pkg>
adb shell am start -W -n <pkg>/.DemoActivity   # demo 的入口

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
./gradlew :app:assembleDebug
./gradlew :fastkit-image:testDebugUnitTest :fastkit-list:testDebugUnitTest
```

- CI（`.github/workflows/android.yml`）跑单测、debug/release 构建和两个库的 lint。
- 解码/索引这类纯逻辑要有 JVM 单测；需要 `Bitmap`/`Canvas` 的放 instrumented 测试
  （方法名只能用英文，DEX 040 之前的版本不允许 SimpleName 带空格）。
- Gradle 8.14.3（wrapper 已入库），AGP 8.13，Kotlin 2.1，JDK 17。
- 版本统一在 `gradle/libs.versions.toml`，模块里不写死版本号。
- `local.properties` 不入库，由各自的机器生成。
- 新增依赖前先确认对低端机的代价；能用平台 API 就不加库。
- 新增能力放独立模块（`fastkit-*`），不要往现有两个 SDK 里塞无关功能。

### Baseline profile

`:baselineprofile` 采集冷启动 + 滑动路径，产物 `app/src/main/baseline-prof.txt`。
需要 API 28+ 的设备或模拟器：

```bash
ANDROID_SERIAL=<serial> ./gradlew :app:generateBaselineProfile
```

API 24~27 由 `androidx.profileinstaller` 在启动时安装 profile；API 28+ 由系统在安装时处理。

**已知限制：Windows 上跑不了。** 插件会为采集创建 `nonMinifiedRelease` /
`benchmarkRelease` 变体，这两个变体的 `:app:compile*Kotlin` 会把一个 Provider 当成
`scriptSources` 路径解析，报 `Could not stat file ...\app\provider(?)`。
Kotlin 2.1.20 / 2.2.20、插件 1.5.0 / 1.6.0-alpha01 都能复现；`doNotTrackState()` 不起作用。
在 macOS / Linux 或 CI 上执行同样的命令即可。

## 不要做的事

- 不要为了"功能更全"引入新的图片加载库，会与 `FastImageLoader` 的缓存语义冲突。
- 不要在 item 布局里用 `wrap_content` 承载图片；尺寸不确定会导致请求被推迟或尺寸跳变。
- 不要在 `onBindViewHolder` 里 `requestLayout()`。
- 不要用 `notifyDataSetChanged()`，用区块级的 `notifyItemRange*`。
- 不要把第三方 App 的内部实现细节、类名或资源写进代码和文档。

## 目录约定

```
fastkit-image/src/main/java/io/github/twitterisgood/fastkit/image/
  FastImageLoader.kt        图片加载（缓存/解码/取消/预取/去重/内存压力）
  BitmapPool.kt             inBitmap 复用池（引用计数，在屏位图不回池）
  CdnSize.kt                图床尺寸参数

fastkit-list/src/main/java/io/github/twitterisgood/fastkit/list/
  FlatSectionAdapter.kt     扁平列表 + SectionHolder
  SectionIndex.kt           区块前缀和（纯 Kotlin，可单测）
  PrefetchScrollListener.kt 滚动预取

app/src/main/java/io/github/twitterisgood/fastkit/demo/
  DemoActivity.kt           示例，同时依赖两个 SDK

baselineprofile/            baseline.prof 采集，不参与 app 构建
```

- `:fastkit-image` 不依赖 `:fastkit-list`，反之亦然。两者之间只能靠调用方用 lambda 连接。
- 新能力新建 Gradle 模块 + 独立 namespace，不要塞进现有模块的根包。
- 两个库都要有 `consumer-rules.pro`，并在 `defaultConfig` 里挂上（`consumerProguardFiles`），
  否则下游开 R8 时库这边没有任何规则入口。
