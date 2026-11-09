package io.github.twitterisgood.fastkit.demo

import android.app.Application
import android.os.StrictMode

/**
 * debug 包默认打开 StrictMode：主线程的磁盘、网络、泄漏都在当场暴露，
 * 而不是等到线上用户反馈「滑动偶尔顿一下」。
 *
 * `penaltyDeath()` 会让违规直接崩溃——这是有意的，debug 就该炸在写错的那一行。
 * release 不装，用户端不会因为一个 I/O 读而闪退。
 */
class DemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) installStrictMode()
    }

    private fun installStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .penaltyDeath()
                .build()
        )

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectActivityLeaks()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectFileUriExposure()
                .penaltyLog()
                .build()
        )
    }
}
