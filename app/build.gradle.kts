plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

baselineProfile {
    // 生成的 profile 直接写到 app/src/main，跟着仓库走
    saveInSrc = true
}

android {
    namespace = "io.github.twitterisgood.fastkit.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.twitterisgood.fastkit.demo"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("benchmark") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // generateBaselineProfile 在这台托管模拟器上采集，CI 的 ubuntu runner 有 KVM。
    // 本机的 Windows 构建跑不了采集（见 AGENTS.md），所以这条路只给 CI 用。
    testOptions {
        managedDevices {
            localDevices {
                create("profileDevice") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp"
                }
            }
        }
    }
}

dependencies {
    implementation(project(":fastkit-image"))
    implementation(project(":fastkit-list"))
    implementation(libs.androidx.profileinstaller)
    // generateBaselineProfile 从这里找采集用例；不挂这条配置就什么都不会采
    baselineProfile(project(":baselineprofile"))
}
