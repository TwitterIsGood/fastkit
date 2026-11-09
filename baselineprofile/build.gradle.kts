plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    // 采集模块也要应用这个插件，才会带上下游变体匹配所需的属性
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "io.github.twitterisgood.fastkit.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    // 让本模块能对自己的 target 应用自测
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.benchmark.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.junit)
}
