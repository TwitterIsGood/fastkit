package io.github.twitterisgood.fastkit.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 生成 baseline.prof：把冷启动 + 连续滑动的主路径 AOT 编译掉。
 *
 * 需要一个 API 28+ 的模拟器或真机（A57 是 API 23，跑不了这个）。
 * CI 上由 `.github/workflows/baseline-profile.yml` 触发，产物在 app/src/main/baseline-prof.txt。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(packageName = TARGET) {
            pressHome()
            launchApp()

            val w = device.displayWidth
            val h = device.displayHeight
            repeat(3) {
                device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 12)
                device.waitForIdle()
            }
        }
    }

    /**
     * 无 GPU 的模拟器（CI 上的 swiftshader）经常拿不到 `gfxinfo framestats`，
     * `startActivityAndWait()` 会因为等不到首帧统计而抛异常。应用其实已经起来了，
     * 退回普通启动 + 固定等待即可，Profile 采集本身不依赖这次确认。
     */
    private fun MacrobenchmarkScope.launchApp() {
        try {
            startActivityAndWait()
        } catch (e: IllegalStateException) {
            startActivity(TARGET, ACTIVITY)
            Thread.sleep(START_FALLBACK_WAIT_MS)
        }
    }

    private companion object {
        const val TARGET = "io.github.twitterisgood.fastkit.demo"
        const val ACTIVITY = "io.github.twitterisgood.fastkit.demo.DemoActivity"
        const val START_FALLBACK_WAIT_MS = 2_000L
    }
}
