# fastkit

把「列表不卡」做成默认值的 Android 基线，拆成互不依赖的 SDK。
规则和硬约束见 [AGENTS.md](AGENTS.md)。

## 模块

| 模块 | 作用 | 关键类 | 依赖 |
|---|---|---|---|
| `:fastkit-list` | 一屏多区块全部由一条 RecyclerView 承载，不嵌套垂直列表；区块前缀和定位、滚动预取 | `FlatSectionAdapter`、`Section`、`SectionHolder`、`SectionIndex`、`RecyclerView.setupForFeed()`、`PrefetchScrollListener` | `androidx.recyclerview` |
| `:fastkit-image` | 尺寸感知的图片加载：缓存键含目标宽高、后台解码、请求去重、可取消、渐进出图、`inBitmap` 复用 | `FastImageLoader`、`BitmapPool`、`CdnSize` | 无（纯平台 API） |
| `:baselineprofile` | 采集冷启动 + 滑动的 baseline profile，让安装期直接 AOT 编译 | `BaselineProfileGenerator` | `com.android.test` |
| `:app` | 601 个 item 的 demo，同时依赖上面两个 SDK | `DemoActivity` | 前两个 |

只用图片就 `implementation(project(":fastkit-image"))`，不会被列表代码牵连。
两个 SDK 之间没有依赖，需要联动时由调用方用 lambda 接。

- **列表**：`Section` 只描述 item 数量、布局和绑定，定位交给 `SectionIndex` 的前缀和二分；
  局部刷新走 `notifySectionChanged(payload)`，不整块重 bind。
- **图片**：先解 1/4 尺寸粗图占位（实测 7~17ms 上屏），再解目标尺寸覆盖（14~56ms）；
  缓存里面积不足目标一半的近似图只当占位，不当结果。
- **去重与优先级**：同一 `(url, w, h)` 并发请求共享一次下载解码，可见区域优先级高于预取。

## 性能

全部用 `dumpsys gfxinfo` 采集，601 个 item 连续滑动 6 次。
判定口径：卡顿 <1% 可接受、>3% 需要修，p90 ≤8ms、p99 ≤16ms。

### 低端机（2016 年机型，Android 6.0，3GB RAM，720×1280）

同一 APK，只改 CPU 配置：

| 配置 | 冷启动 | 帧数 | 卡顿率 | p90 | p99 |
|---|---|---|---|---|---|
| 8 核 + performance 调频（有缓存） | 221 / 225 / 227ms | 392 | **0.00%** | 7ms | 13~14ms |
| 8 核 + performance 调频（清空内存+磁盘缓存） | — | 393 | 0.76% | 7ms | 15ms |
| 3 核 + interactive 调频，出厂均衡（有缓存） | 263 / 269ms | 391 | 3.81~3.84% | 10ms | 25~27ms |

清空缓存后仍然不掉帧，说明解码完全不占 UI 线程。两种配置的 `Missed Vsync` 基本都是 0。

均衡模式那行超过 3% 阈值，但隔离实验证明是设备配置所致，不是代码回归：

| 隔离配置 | 冷启动 | 卡顿率 | p90 | p99 |
|---|---|---|---|---|
| performance 调频 + 3 核 | 271ms | 0.51~0.77% | 8ms | 15ms |
| interactive 调频 + 8 核 | 229ms | 1.03~1.53% | 8ms | 17~22ms |

卡顿主要来自 `interactive` 的升频延迟，核心数把差距放大；冷启动则主要受核心数影响（约 20%）。
**回归测试必须固定一种配置，否则数据不可比。**

### 中端机（Android 15，1200×2670，R8 release）

| 场景 | 帧数 | 卡顿率 | p90 | p95 | p99 |
|---|---|---|---|---|---|
| 有缓存 | 785 | 0.25% | 6ms | 7ms | 9ms |
| 清空内存+磁盘缓存 | 777 | 0.13% | 6ms | 7ms | 9ms |

冷启动 110 / 118 / 113ms（清缓存后首启 139ms）。

首次安装后第一次测量会偏高（JIT 预热），稳态要重复跑几次取后面的数据。

## 运行

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Demo 用 `local://seed/WxH` 在本地生成 JPEG，走完整的下载→缓存→两遍解码链路，不依赖网络。
接真实图床时替换 `FastImageLoader.bytesFor()` 里的分支即可。

单测与 CI：

```bash
./gradlew :fastkit-image:testDebugUnitTest :fastkit-list:testDebugUnitTest
```

`.github/workflows/android.yml` 在推送时跑单测、debug/release 构建和两个库的 lint。

Baseline profile 需要 API 28+ 的设备或模拟器：

```bash
./gradlew :app:generateBaselineProfile
```

产物写到 `app/src/release/generated/baselineProfiles/`，提交后由 AGP 打进 APK。
CI 上用 `.github/workflows/baseline-profile.yml` 手动触发采集（详见 AGENTS.md）。

## License

Apache-2.0
