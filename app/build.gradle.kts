plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.swissknife"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.swissknife"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.7"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // 「空间回收」需要在 shell 进程里跑一个 AIDL 服务（ITruncateService），
    // 这是本项目第一次用到 aidl 源码集；显式打开，不依赖 AGP 的默认值。
    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Shizuku：提供 shell 身份，用于跨应用事件注入
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // 仅 JVM 单测用。SessionTimer 是纯计算状态机（每个入口都收显式 now），
    // 熄屏 20 秒边界、阈值到点这些真机掐表验不准，放这里几十毫秒跑完。
    // 不打进 APK，不影响运行时行为。
    testImplementation("junit:junit:4.13.2")
}
