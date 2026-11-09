# CLAUDE.md

本仓库的工程规范与硬约束集中在 [AGENTS.md](AGENTS.md)，开工前先读它。

要点速记：

- 一屏多区块 = 一条 RecyclerView + 多个 `Section`，禁止垂直嵌套列表。
- 网络图片一律走 `FastImageLoader`，缓存键必须含目标宽高。
- 解码不上主线程；复用即取消。
- 改完列表/图片代码要附 `dumpsys gfxinfo` 实测帧数据（判定口径见 AGENTS.md）。
- 构建：`JAVA_HOME=<jdk17> ./gradlew :app:assembleDebug`。
