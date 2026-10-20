pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("com.android.application") version "9.1.0" apply false
    id("com.android.library") version "9.1.0" apply false
}

// :contract —— AIDL + 发现约定契约的唯一物理副本（宿主与插件侧共用）
include(":contract")
// :plugin-core —— 插件侧运行时 SDK（对外交付物 goodhub-mcp-plugin-core.aar）
include(":plugin-core")
// :sample-dummy-plugin —— 靶子/示例 App（可安装 APK，被 GoodHub 自动发现）
include(":sample-dummy-plugin")
