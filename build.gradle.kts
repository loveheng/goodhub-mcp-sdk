// 仓级版本事实源 = VERSION 文件（version / mcp_protocol_version / contract_version）。
// Gradle 的 project.version 由此派生，BuildConfig.SDK_VERSION 与 Maven 坐标同源，杜绝多份版本号漂移。
val versionProps = java.util.Properties().apply {
    file("VERSION").inputStream().use { load(it) }
}

allprojects {
    group = "com.zzh.goodhub.mcp"
    version = versionProps.getProperty("version")

    repositories {
        google()
        mavenCentral()
    }

    // 远端发布仓：GitHub Packages（https://maven.pkg.github.com/loveheng/goodhub-mcp-sdk）。
    // 需环境变量 GITHUB_TOKEN（+ GITHUB_ACTOR，默认 loveheng）；未设则不配置该仓，
    // 本地构建与 publishToMavenLocal 完全不受影响。
    // 用 plugins.withId 延迟到 maven-publish 真正应用后再配置扩展——
    // 直接在 allprojects 里写 publishing{} 会在插件应用前解析（扩展尚不存在）。
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                // 离线分发仓：产物发到 <仓>/dist/maven-repo（标准 Maven 目录布局），
                // 可直接 maven { url = uri(".../dist/maven-repo") } 消费，或整目录拷进 ~/.m2。
                // 与 GitHub Packages 并存：前者免认证/可内网分发，后者是公网坐标。
                maven {
                    name = "dist"
                    url = uri(rootProject.layout.projectDirectory.dir("dist/maven-repo").asFile.toURI())
                }
                val ghToken = System.getenv("GITHUB_TOKEN")
                if (!ghToken.isNullOrBlank()) {
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/loveheng/goodhub-mcp-sdk")
                        credentials {
                            username = System.getenv("GITHUB_ACTOR") ?: "loveheng"
                            password = ghToken
                        }
                    }
                }
            }
        }
    }
}
