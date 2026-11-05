import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.library")
    // AGP 9.1 内置 Kotlin（与宿主 app 模块同一路径，不 apply org.jetbrains.kotlin.android；
    // 同一构建内混用内置与外置 Kotlin 插件有冲突风险）
    // 引擎融合后本模块为纯逻辑库（无 Compose UI），compose 插件与依赖已移除
}

val appVersionName = "1.1.4"

android {
    namespace = "com.GhostKey.app"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
        // release 混淆规则随库下发，供外壳 App 的 R8 统一消费（Shizuku/AIDL/ViewModel 的 keep 规则）
        consumerProguardFiles("proguard-rules.pro")
        ndk {
            // 仅 arm64：native exploit 只提供 64 位实现（外壳 APK 也仅打包 arm64）
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=none")
                cppFlags("")
            }
        }
    }

    buildFeatures {
        aidl = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Cve98260UserService 是 Shizuku UserService（非标准 android.app.Service），
        // lint 会误报 Instantiatable，沿用原工程的抑制配置。
        disable += "Instantiatable"
    }
}

// 与外壳工程保持同一 JDK 工具链（AGP 9 + Kotlin 2.4）
kotlin {
    jvmToolchain(21)
}

dependencies {
    // Shizuku
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")

    // 单测：StageTracker 的纯逻辑验证
    testImplementation("junit:junit:4.13.2")
}
