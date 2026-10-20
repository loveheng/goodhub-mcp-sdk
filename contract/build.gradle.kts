plugins {
    id("com.android.library")
    id("maven-publish")
}

// 契约模块：GoodHub 本地插件接入契约的唯一物理副本。
//  - AIDL（IMcpChannel / IMcpCallback）：线级契约，包名 com.zzh.goodhub.mcp 永久冻结
//    （AIDL 生成的 DESCRIPTOR = 接口全限定名，随每次 Binder 事务作为 interface token 上线）。
//  - 发现约定常量（Action / meta-data / permission）：同样是线级常量，改名即打断存量插件。
// 宿主（GoodHub）与插件侧（plugin-core）都依赖本模块，杜绝跨仓手抄副本漂移。
android {
    namespace = "com.zzh.goodhub.mcp"
    compileSdk = 35

    buildFeatures {
        aidl = true
    }

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 声明 release 变体为可发布组件（AGP 8+ 起 Maven 发布需显式声明）。
    publishing {
        singleVariant("release")
    }
}

dependencies {
    // 契约冻结自检（CI 跑 :contract:test）
    testImplementation("junit:junit:4.13.2")
}

base {
    archivesName.set("goodhub-mcp-contract")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "goodhub-mcp-contract"
            version = project.version.toString()

            pom {
                name.set("GoodHub MCP Contract")
                description.set(
                    "GoodHub 本地插件接入契约：AIDL（IMcpChannel / IMcpCallback）与发现约定常量。" +
                        "AIDL 包名 com.zzh.goodhub.mcp 永久冻结（DESCRIPTOR 随每次 Binder 事务上线）。"
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
