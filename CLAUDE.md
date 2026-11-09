# CLAUDE.md

本仓库的工程规范与硬约束集中在 [AGENTS.md](AGENTS.md)，开工前先读它。

要点速记：

- 两个互不依赖的 SDK：`:fastkit-list`（扁平 Section 列表）、`:fastkit-image`（尺寸感知图片）。
  不要为了省事让它们互相依赖。
- 一屏多区块 = 一条 RecyclerView + 多个 `Section`，禁止垂直嵌套列表。
- 网络图片一律走 `FastImageLoader`，缓存键必须含目标宽高，同一请求只下一遍。
- 解码不上主线程；复用即 `clear()`。
- 局部刷新用 payload，不用 `notifyDataSetChanged()`。
- 改完列表/图片代码要附 `dumpsys gfxinfo` 实测帧数据（判定口径见 AGENTS.md）。
- 构建：`./gradlew :app:assembleDebug`；版本号写在 `gradle/libs.versions.toml`。
