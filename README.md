# goodhub-mcp-sdk

**GoodHub 本地插件接入 SDK** —— 让第三方 Android App「5 分钟」接入 GoodHub 的同机 MCP 通道。

写业务方法、加两行注解，GoodHub 就能自动发现你的 App、把你的方法暴露成 MCP 工具。
你不需要知道 Binder 是什么，也不需要手写一行 AIDL。

```kotlin
class FlashlightMcpPlugin : GoodHubPluginService(
    serverId = "flashlight",
    serverTitle = "手电筒工具",
) {
    @McpTool(description = "开关手电筒")
    fun toggleTorch(@Param("enable", description = "是否开启") enable: Boolean): String {
        CameraHelper.setTorch(this, enable)
        return "手电筒已" + if (enable) "开启" else "关闭"
    }
}
```

```xml
<service
    android:name=".FlashlightMcpPlugin"
    android:exported="true">
    <intent-filter>
        <action android:name="com.zzh.goodhub.action.MCP_SERVER" />
    </intent-filter>
    <meta-data android:name="com.zzh.goodhub.SERVER_ID" android:value="flashlight" />
    <meta-data android:name="com.zzh.goodhub.SERVER_TITLE" android:value="手电筒工具" />
</service>
```

安装即被 GoodHub 感知，无需任何注册或配置界面。

---

## 1. 仓库构成

| 模块 | 产物 | 职责 |
|---|---|---|
| `:contract` | `goodhub-mcp-contract.aar` | **契约唯一物理副本**：AIDL（`IMcpChannel` / `IMcpCallback`）+ 发现约定常量。宿主与插件侧共用，杜绝跨仓手抄副本漂移。 |
| `:plugin-core` | `goodhub-mcp-plugin-core.aar` | **插件侧运行时 SDK**：`GoodHubPluginService` 基类、`@McpTool` / `@Param` 注解、反射派发器、异步上下文。零第三方依赖、零 Flutter 依赖。 |
| `:sample-dummy-plugin` | `sample-dummy-plugin-*.apk` | 可安装的靶子 / 示例 App，同时是宿主真机联调的 e2e 靶子（`serverId=dummy`）。 |

> `:contract` 与 `:plugin-core` 是**两个** AAR。手工引 AAR 时需两个都引；用 Maven 引 `:plugin-core` 时 `:contract` 会作为传递依赖自动带入。

---

## 2. 接入方式

### 2.1 Maven 坐标（已发布）

```kotlin
dependencies {
    implementation("com.zzh.goodhub.mcp:goodhub-mcp-plugin-core:0.1.0")
}
```

`:contract` 随 `api` 依赖传递引入，无需单独声明。

仓库地址：`https://maven.pkg.github.com/loveheng/goodhub-mcp-sdk`（GitHub Packages，由 `v0.1.0` tag 触发 CI 发布）。

包本身**已是公开**（从公开仓发布，继承仓可见性；匿名可打开包详情页）。但 GitHub Packages 的 Maven 端点**对公开包同样强制要求认证**（官方原文：public 包也需要 access token 才能 install），匿名拉取返回 401 —— 所以消费方仍需带凭证：

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/loveheng/goodhub-mcp-sdk")
        credentials {
            username = System.getenv("GITHUB_ACTOR") ?: "你的 GitHub 用户名"
            password = System.getenv("GITHUB_TOKEN") ?: ""   // PAT，scope 至少 read:packages
        }
    }
}
```

### 2.2 离线分发件（免认证，推荐内网/无 token 场景）

`./scripts/dist.sh` 产出一个**标准 Maven 目录布局**的目录与压缩包，拷到任意机器即可用，无需任何凭证：

```
dist/maven-repo/                    ← 直接当 file 仓消费
dist/goodhub-mcp-sdk-0.1.0.zip      ← 同一份内容的压缩包
```

```kotlin
repositories {
    maven { url = uri("/path/to/dist/maven-repo") }   // 解压后的目录
}
```

或把整个 `maven-repo` 目录拷进 `~/.m2/repository`（标准路径布局，Gradle 自动识别）。

### 2.3 本地 Maven 仓（同机开发）

```bash
./gradlew publishToMavenLocal
```

```kotlin
repositories { mavenLocal() }
```

### 2.4 手工引 AAR（无仓库环境）

```kotlin
dependencies {
    implementation(files("libs/goodhub-mcp-plugin-core-release.aar"))
    implementation(files("libs/goodhub-mcp-contract-release.aar"))
}
```

构建产物路径：

```
plugin-core/build/outputs/aar/goodhub-mcp-plugin-core-release.aar
contract/build/outputs/aar/goodhub-mcp-contract-release.aar
```

### 2.3 接入清单（对照检查）

- [ ] `minSdk >= 34`
- [ ] Service 声明 `android:exported="true"` + `action="com.zzh.goodhub.action.MCP_SERVER"`
- [ ] Service 声明 meta-data `com.zzh.goodhub.SERVER_ID`（唯一 ID）与 `com.zzh.goodhub.SERVER_TITLE`（展示名）
- [ ] 业务方法用 `@McpTool` 标注（返回 `String` / `Int` / `Boolean` / `Long` / `Float` / `Double`）
- [ ] 超过 5s 的方法标 `@McpTool(async = true)`，用首参 `McpToolContext` 异步回报结果

---

## 3. 能力清单

### 已实现

| 能力 | 说明 |
|---|---|
| 自动 Schema | 扫描 `@McpTool` 方法生成合规 JSON Schema `inputSchema`（运行时反射，非 KSP） |
| 自动路由 | `tools/call` 按工具名路由到目标 Kotlin 方法，按类型自动 coerce 入参 |
| 异常隔离 | 业务方法抛出的任何异常在派发层拦截，转标准 JSON-RPC `-32603`，**绝不穿透 Binder 致宿主崩溃** |
| 异步长任务 | `@McpTool(async = true)` 立即回 `{"_async": true}`，真实结果经 `McpToolContext.complete/fail` 异步投递，宿主必被唤醒（不悬挂） |
| 进度推送 | `McpToolContext.progress()` 推 `notifications/progress`（带 `progressToken`） |
| 反向请求 | `McpToolContext.reverseRequest()` 发 server→client 请求（如 `sampling/createMessage`），经 `answerReverse` 回程闭环 |
| 命名不上仲裁 | SDK 只上报构造入参的 `serverId`，不做包名拼接、不做命名主权仲裁（属宿主职责） |
| 可见性标记 | `@McpTool(visibility = ToolVisibility.SHARED)` 声明工具可见性范围，下发到每个工具对象的 `_meta.goodhub.visibility`（默认 `PRIVATE` / default-deny）。**纯标记**：SDK 不据此剔除 `tools/list` 条目、不拦截任何 `tools/call`，如何聚合/分桶属宿主解析职责 |

### 已知缺口

| 缺口 | 影响 | 规避 |
|---|---|---|
| `mcp-blob://` 大报文降级未实现 | 单次调用报文须 < 128KB，超过会触发 Binder 事务上限 | 自行分页，或改走标准 Streamable HTTP MCP 通道 |
| Flutter 插件桥未实现 | Flutter 侧插件 App 无法直接引本 SDK | Flutter 插件请用 Kotlin 原生 Service 包装后接入 |

### 业务层不在 Kotlin 时的接管方式（Dart / Flutter 插件）

SDK 的默认路由是「运行时反射扫描本类 `@McpTool` 方法」，**业务逻辑必须写在 Kotlin**。
若业务逻辑在 Dart（Flutter 插件 App），反射派发器扫不到任何方法 → 工具列表会全量丢失。
这类宿主应重写 `handleJsonRpc` 接管全部请求路由，其余 AIDL 语义（回调注册 / 反向请求回程 / 事件推送）仍由基类承载：

```kotlin
class MyDartBridgePlugin : GoodHubPluginService("myapp", "My App") {

    // ① 接管全部 JSON-RPC 路由：转给 Dart 业务层，阻塞等回包（Binder 线程非主线程，不 ANR）
    override fun handleJsonRpc(jsonRpcRequest: String): String =
        dartBridge.dispatchBlocking(jsonRpcRequest)

    // ② 宿主注册 / 注销回调：据此把「宿主在线」状态转给 Dart
    override fun onHostCallback(cb: IMcpCallback?) {
        dartBridge.setHostOnline(cb != null)
    }

    // ③ 宿主对反向请求的应答：转给 Dart 侧的反向请求在途表
    override fun onHostAnswerReverse(jsonRpcResponse: String) {
        dartBridge.onHostAnswerReverse(jsonRpcResponse)
    }

    // ④ Dart 侧要向宿主推事件（progress / 异步终帧 / 反向请求帧）时调它
    fun pushToHost(jsonRpcMessage: String) = emitEvent(jsonRpcMessage)
}
```

重写者须自行保证：返回合法 JSON-RPC 帧字符串、业务异常转 `-32603`（§1.2.7 异常隔离）、
同步调用在 Binder 事务预算内返回（§1.2.3，长任务改 `_async`）。

---

## 4. 契约与版本

本 SDK 与宿主之间的契约是**线级（wire-level）**的，改动即破坏兼容：

| 项 | 值 | 说明 |
|---|---|---|
| AIDL 包名 | `com.zzh.goodhub.mcp` | **永久冻结**。AIDL 生成的 `DESCRIPTOR` = 接口全限定名，随每次 Binder 事务作为 interface token 上线；改包名 = 存量插件全部 bind 失败 |
| 发现 Action | `com.zzh.goodhub.action.MCP_SERVER` | **永久冻结**（两端 Manifest / 宿主扫描逻辑硬编码） |
| meta-data key | `com.zzh.goodhub.SERVER_ID` / `com.zzh.goodhub.SERVER_TITLE` | **永久冻结** |
| 权限名 | `com.zzh.goodhub.permission.MCP_SERVER` | **永久冻结** |
| MCP 协议版本 | `2025-06-18` | 随 `initialize` 应答上报 |
| 契约版本 | `v1` | 破坏性变更须升版并保持向下兼容 |

上述常量在代码中的唯一出处是 `com.zzh.goodhub.mcp.McpContract`（`:contract` 模块），宿主与插件侧共用同一份。
仓级版本信息见 [`VERSION`](./VERSION)。

---

## 5. 职责边界

**SDK 做（插件侧 server 端）**：

- 实现 `IMcpChannel.Stub`，提供 `GoodHubPluginService` 基类
- 反射生成 `tools/list` Schema、路由 `tools/call`
- 异常隔离（转 `-32603`）、异步长任务、进度推送、反向请求发起
- 大报文降级（`mcp-blob://`）—— **待实现**

**SDK 不做（属 GoodHub 宿主 client 端）**：

- `bindService` / `ServiceConnection` / `DeathRecipient` / 连接状态机（`IDLE→CONNECTING→READY→DEAD`）
- 挂起请求队列（`PendingRequestQueue`）与超时预算
- 命名主权仲裁（PackageName 主键 + 冲突降级）—— SDK 只上报 `serverId`
- 安全闸门（同签名判定、信任卡片白名单）—— SDK 不绕过
- 大报文的 `FileProvider` / `ContentResolver` 解包侧

---

## 6. 构建与发布

```bash
export ANDROID_HOME="$HOME/android-sdk"

# 三模块构建
./gradlew :contract:assembleRelease :plugin-core:assembleRelease :sample-dummy-plugin:assembleDebug

# 单元测试（契约冻结自检）
./gradlew test

# 发布到本地 Maven 仓库
./gradlew publishToMavenLocal
```

环境要求：JDK 17、Android SDK（compileSdk 35 / minSdk 34）、Gradle 9.3.1（wrapper 自带）。

CI：`.github/workflows/ci.yml` 在每次 push / PR 上执行「构建 + 单测 + 产物归档」。

---

## 7. 与上游的关系

| 角色 | 仓库 | 关系 | 接入状态 |
|---|---|---|---|
| 宿主（client 端） | [goodhub](https://github.com/loveheng/goodhub) | 通过 `:contract` 的 AIDL 与本 SDK 通信；宿主侧另有连接生命周期与安全闸门职责 | **已接入**：宿主已删除自带 AIDL 副本，改经坐标 `com.zzh.goodhub.mcp:goodhub-mcp-contract` 消费（契约物理单副本） |
| 同生态插件 | [goodshare](https://github.com/loveheng/goodshare) | 业务层在 Dart → 继承 `GoodHubPluginService` 并重写 `handleJsonRpc` 接管路由 | 迁移中：自拷 AIDL + 手写 Stub 待换成 SDK |
| 同生态插件 | [shiguang](https://github.com/loveheng/shiguang) | 同上（另含 F1：tools/call 走 `_async` + onEvent 终帧切片） | 迁移中：同上 |

> 迁移范式（Flutter 插件 App，业务层在 Dart）：**继承基类 + 重写 `handleJsonRpc` 接管全部路由**，
> 其余 AIDL 语义（回调注册 / 反向请求回程 / 事件推送）由基类承载 —— 详见本文 §3 的接管方式。

上游设计稿（跨仓只读）：GoodHub 仓 `docs/design/thirdconnect.md` §1.2（接入契约）/ §11（对外接入 SDK）。

---

## 8. License

[MIT](./LICENSE)
