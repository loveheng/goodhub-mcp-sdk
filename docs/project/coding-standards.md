---
status: active
updated: 2026-11-09
---

# goodhub-mcp-sdk · 写法规范（Engineering Specification / Architecture SSOT）

> 本档是**写码规则 SSOT**（分层依赖/落点决策/红线/门禁映射）；索引数据住 `docs/project/index.md`（`goodhub-mcp-sdk-index` skill），命令与环境住 `goodhub-mcp-sdk-workflow` skill——三件套分工见该 skill「定位边界」表。条款随代码提炼增量生长，机械护栏（测试/CI）只镜像本档规则，**代码不立法**。

## 1. 架构拓扑与分层依赖（Dependency Rules）

### 1.0 分层矩阵

| 层 | 角色 | 允许出向依赖 | 禁止 |
|---|---|---|---|
| `:contract` | AIDL 接口 + `McpContract` 常量（线级契约唯一物理副本） | 无模块内依赖；`testImplementation` junit | 引入任何业务模块；改动 AIDL 包名 / 发现 Action / meta-data key / 权限名 |
| `:plugin-core` | 插件侧运行时 SDK（Service 基类、注解、派发器） | `api(project(":contract"))`、零第三方运行时依赖 | 依赖 Flutter / AI 模块；`bindService` 与连接状态机（归宿主） |
| `:goodhub-ai-client` | AI 消费方纯 API 面（四入口 + 自有类型） | 仅 `kotlinx-coroutines-core`；零模块内依赖 | 引用 `com.zzh.goodhub.mcp.*`、`IMcpChannel`/`IMcpCallback` 等 AIDL 类型 |
| `:goodhub-ai-runtime` | AI 消费方实现件（帧路由 / 取消 / 死亡收敛） | `api(project(":contract"))` + `api(project(":goodhub-ai-client"))` | 让 client 反向依赖 runtime；把裸异常抛给门面 |
| `:sample-dummy-plugin` | 可安装示例 / 真机靶子 | `implementation` 上述三个工件 | 产出对外 AAR |

编译依赖方向 `runtime → client`（`goodhub-ai-runtime/build.gradle.kts`），业务调用方向 client 门面 → runtime 实现；`goodhub-ai-client/build.gradle.kts` 的破层红线由本模块单测源级断言钉死。

### 1.5 落点决策表（新功能放哪）

| 信号/技术形态 | 落点域 | 依赖边界 |
|---|---|---|
| 新增/改动线级常量、AIDL 方法 | `contract/` | 冻结项见 §5，变更须升契约版本并保持向下兼容 |
| 新增 `@McpTool` 能力、prompts 模板、派发语义 | `plugin-core/.../plugin/` | 只经 `:contract` 出向，禁第三方运行时依赖 |
| 新增 AI 消费方公开类型或入口 | `goodhub-ai-client/.../ai/` | 纯 Kotlin 自有类型，禁 IPC 类型渗入 |
| 新增连接、多帧关联、取消、阶段翻译 | `goodhub-ai-runtime/.../runtime/` | 实现件，业务层零 Manifest/零 IPC 感知 |
| 新增可运行验证靶子 | `sample-dummy-plugin/` | 只消费，不产出 SDK 工件 |
| 对外接口/契约文档 | 根 `api.md`（存量）或 `docs/`（工程内） | 禁在两处复述同一契约正文 |

### 1.8 跨域依赖违例处置

先改结构（端口注入 / 类型下沉 contract）再写 import；确属历史遗留走白名单纪律（**只减不增**）；债登记归 `docs/project/index.md` 债表。

## 2. 状态与生命周期

`GoodHubPluginService` 只实现 `IMcpChannel.Stub`：不 `bindService`、不持 `DeathRecipient`，连接生命周期与挂起队列状态机归宿主。AI 侧在途请求由 `AiRuntimeCore` 的 `pending` 表承载，终帧或失败必须清表；Service 级可见性标记 `mcpConsumable` 默认 `false`（default-deny），与工具级 `ToolVisibility.PRIVATE` 同哲学。

## 3. 并发与写安全

- 异步 `@McpTool(async = true)` 长任务在 `ToolDispatcher` 的守护线程池跑，**禁阻塞 Binder 事务线程**；call 内立即回 `{"_async": true}`，终态经 `McpToolContext.complete/fail` 投递。
- 跨线程可见字段用 `@Volatile`（`GoodHubPluginService.callback`）；在途表用 `ConcurrentHashMap`，序号用 `AtomicLong`（`pendingReverse`/`reverseCounter`、`pending`/`seq`）。
- 业务层调用须绑定 `viewModelScope` / Lifecycle；**禁 `GlobalScope` 跑无界任务**（页面退出后仍烧宿主算力）。

## 4. 容错与降级（对齐 ai-sideeffect-guard DEGRADE 约定）

- 业务异常在派发层拦截并转 `-32603`，**绝不穿透 Binder 致宿主跨进程 Crash**（`ToolDispatcher` 类注释红线）。
- `AiRuntimeCore` 发射失败（`isHostConnected == false` 或 `pushFrame` 抛异常）一律**回滚 pending 并以 `HOST_OFFLINE` 结构化失败收口**，禁把裸异常抛给门面致 Flow 抛异常/回调档静默悬挂；四档 API 都必须收到终态事件。
- 输出清洗失败保留原文；`retryAfterMs` 仅提示非承诺，SDK 不自动重试。降级路径须留痕，禁静默吞异常。

## 5. 接口契约与标识符

永久冻结：AIDL 包名 `com.zzh.goodhub.mcp`、发现 Action `com.zzh.goodhub.action.MCP_SERVER`、meta-data key `com.zzh.goodhub.SERVER_ID` / `SERVER_TITLE`、权限名 `com.zzh.goodhub.permission.MCP_SERVER`；MCP 协议版本 `2025-06-18`，契约版本 `v1`。唯一代码出处 `contract/src/main/java/com/zzh/goodhub/mcp/McpContract.kt`。破坏性变更须升契约版本并保持向下兼容（Expand-Contract 流程见 dev-loop §2）。失败码十二值、阶段四值与线级 `_meta` 同值单源，禁两套枚举。

## 6. 安全护栏

- 同签名判定、信任卡白名单、配额与内容围栏归宿主受理链；SDK 不绕过、不自建授权判定。
- `mcpConsumable` 与工具 visibility 是**给宿主的声明，不是 SDK 的访问控制**：不改变 `tools/list` 条目数、不拦截 `tools/call`。
- GitHub Packages 凭证经 `GITHUB_TOKEN` 环境变量注入；`local.properties`、token、口令禁入仓与文档（dev-loop 护栏 8）。

## 7. 资源释放

协程 scope 取消 → SDK 自动发 `ai/cancel` 并让出宿主算力；异步工具线程池为守护线程；在途表在终帧/失败/宿主死亡时确定性清空。

## 8. 自动化门禁与看门狗（CI Guardrails）

| 规则编号 | 规则 | 机械镜像（测试/CI 路径） |
|---|---|---|
| R1 | `goodhub-ai-client` 源零 contract / AIDL 引用；门面四入口 + 五类型、阶段四值、失败十二码冻结 | `goodhub-ai-client/src/test/java/com/zzh/goodhub/ai/GoodHubAiLayerTest.kt` |
| R2 | AIDL 包名 / 发现常量 / 契约版本冻结 | `contract/src/test/java/com/zzh/goodhub/mcp/McpContractTest.kt` |
| R3 | 五模块 assemble + JVM 单测全绿才归档产物 | `.github/workflows/ci.yml` build job |
| R4 | tag 版本与 `VERSION` 一致才允许发布四工件 | `.github/workflows/ci.yml` publish job |
| R5 | 静态探测以宿主包启动 Activity 为目标（禁查 `MCP_SERVER` service）；`<queries>` 由 runtime 库随包声明、client 保持零 Android 资源，且包名与 `GoodHubAi.hostPackageName` 同源 | `goodhub-ai-client/src/test/java/com/zzh/goodhub/ai/GoodHubAiVisibilityTest.kt` |

> 白名单纪律：护栏命中确属历史遗留 → 白名单登记（只减不增），规则正文在本档，代码只镜像。

## 9. 与 dev-loop 工程护栏的对齐

| 本档条款 | dev-loop 对应护栏 |
|---|---|
| §8 门禁 | 护栏 5「静态强约束」（compile/lint 校验后才交付） |
| §1.8 违例处置 | 护栏 6「契约保护」（大面积跨文件改动先列影响清单） |
| §5 冻结项 | 护栏 6「契约保护」（Expand-Contract，不直接改线级形状） |
