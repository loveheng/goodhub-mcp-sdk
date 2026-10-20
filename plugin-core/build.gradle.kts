plugins {
    id("com.android.library")
    id("maven-publish")
}

// 插件侧运行时 SDK（对外交付物：goodhub-mcp-plugin-core.aar）。
// 零第三方依赖、零 Flutter 依赖——第三方原生 App 只引一个包即可接入。
android {
    namespace = "com.zzh.goodhub.mcp.plugin"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 34
        // SDK 版本号显式下发（library 模块的 BuildConfig 无 VERSION_NAME）：
        // 供 initialize 应答的 serverInfo.version 上报，宿主据此判断契约兼容度。
        buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 声明 release 变体为可发布组件（AGP 8+ 起 Maven 发布需显式声明）。
    publishing {
        singleVariant("release")
    }

    // 派发器单测跑在 JVM 上：Android 框架方法返回默认值（免 mockable-jar 抛异常），
    // org.json 换成真实实现（mockable jar 的桩实现会返回空值，跑不了 JSON 断言）。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // AIDL 契约唯一副本在 :contract。
    // 用 api 而非 implementation：消费方（第三方插件 App）编译期需同时可见契约类
    // （GoodHubPluginService 实现 IMcpChannel.Stub，Manifest 需引用契约常量）。
    api(project(":contract"))

    testImplementation("junit:junit:4.13.2")
    // 真实 org.json 实现（Android 侧 org.json 在 JVM 单测里是空壳桩）
    testImplementation("org.json:json:20231013")
}

base {
    archivesName.set("goodhub-mcp-plugin-core")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "goodhub-mcp-plugin-core"
            version = project.version.toString()

            pom {
                name.set("GoodHub MCP Plugin Core")
                description.set(
                    "GoodHub 本地插件接入 SDK：GoodHubPluginService 基类 + @McpTool 注解 + 反射派发器。" +
                        "零第三方依赖、零 Flutter 依赖，业务逻辑在 Dart 的宿主可重写 handleJsonRpc 接管路由。"
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
