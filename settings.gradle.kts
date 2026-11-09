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
// :goodhub-ai-runtime —— AI 消费方实现件（连接生命周期 / _async 关联 / phase
// 翻译 / ai/cancel 映射；ai-capability-export M-C §3.5）
include(":goodhub-ai-runtime")
// :goodhub-ai-client —— AI 消费方门面（业务层唯一依赖：suspend/Flow/回调
// 三档 API + 结构化失败 + 静态探测；破层红线 = 公开 API 禁触 contract/transport 类型）
include(":goodhub-ai-client")
// :sample-dummy-plugin —— 靶子/示例 App（可安装 APK，被 GoodHub 自动发现）
include(":sample-dummy-plugin")
