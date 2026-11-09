package io.github.twitterisgood.fastkit.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 生成 baseline.prof：把冷启动 + 连续滑动的主路径 AOT 编译掉。
 *
 * 需要一个 API 28+ 的模拟器或真机（A57 是 API 23，跑不了这个）。
 *   ./gradlew :baselineprofile:connectedBenchmarkAndroidTest
 * 产物在 app/src/main/baseline-prof.txt，提交后 AGP 会打进 APK。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(packageName = TARGET) {
            pressHome()
            startActivityAndWait()

            val w = device.displayWidth
            val h = device.displayHeight
            repeat(3) {
                device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 12)
                device.waitForIdle()
            }
        }
    }

    private companion object {
        const val TARGET = "io.github.twitterisgood.fastkit.demo"
    }
}
