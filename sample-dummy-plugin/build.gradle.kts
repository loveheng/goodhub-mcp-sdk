plugins {
    id("com.android.application")
}

// 靶子 / 示例插件 App：既是 SDK 的最小可运行示例，也是 GoodHub 真机联调的 e2e 靶子
// （被宿主按 com.zzh.goodhub.action.MCP_SERVER 扫描发现，serverId=dummy）。
android {
    namespace = "com.zzh.goodhub.sample.dummy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zzh.goodhub.sample.dummy"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // 契约类（:contract）经 plugin-core 的 api 依赖传递进来，无需重复声明。
    implementation(project(":plugin-core"))
}
