plugins {
    id("com.android.library")
    id("maven-publish")
}

// AI 消费方实现件（ai-capability-export M-C §3.5）：连接生命周期 / _async 关联 /
// phase 翻译 / ai/cancel 映射。业务层不直接 import 本工件——经 :goodhub-ai-client
// 传递（client → runtime → contract 三层，破层红线由 client 侧单测钉死）。
android {
    namespace = "com.zzh.goodhub.ai.runtime"
    compileSdk = 35

    defaultConfig {
        minSdk = 34
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release")
    }

    // 帧路由核心单测跑 JVM：Android 框架方法返回默认值 + 真实 org.json。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // AIDL 契约唯一副本（answerReverse / IMcpChannel / 发现常量）。
    api(project(":contract"))
    // 消费方门面 API（本工件是 client 的实现载体，依赖方向 client → runtime）。
    api(project(":goodhub-ai-client"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // 真实 org.json 实现（Android 侧 org.json 在 JVM 单测里是空壳桩）
    testImplementation("org.json:json:20231013")
}

base {
    archivesName.set("goodhub-ai-runtime")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "goodhub-ai-runtime"
            version = project.version.toString()

            pom {
                name.set("GoodHub AI Runtime")
                description.set(
                    "GoodHub 对外 AI 能力消费方实现件：连接生命周期 / _async 关联 / " +
                        "phase 翻译 / ai/cancel 映射（ai/complete 契约 0.4.0）。"
                )
                url.set("https://github.com/loveheng/goodhub-mcp-sdk")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://github.com/loveheng/goodhub-mcp-sdk/blob/main/LICENSE")
                    }
                }
            }

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
