plugins {
    id("com.android.library")
    id("maven-publish")
}

// AI 消费方门面（ai-capability-export M-C §3.5）：业务层唯一依赖的稳定 API 面。
// 破层红线：公开 API（本模块全部 public 声明）禁出现 contract / AIDL / transport
// 类型——AiResult / AiEvent / AiFailure 全部自有类型；单测钉死（源级断言）。
android {
    namespace = "com.zzh.goodhub.ai"
    compileSdk = 35

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // 依赖方向（§3.5 分层红线）：runtime → client 单向；client 零模块内依赖
    //（纯 API 面 + Provider 槽），破层红线由本模块单测源级断言钉死。

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

base {
    archivesName.set("goodhub-ai-client")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "goodhub-ai-client"
            version = project.version.toString()

            pom {
                name.set("GoodHub AI Client")
                description.set(
                    "GoodHub 对外 AI 能力消费方门面：suspend/Flow/回调三档 API + " +
                        "结构化失败十二码 + 静态探测（ai/complete 契约 0.4.0）。"
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
