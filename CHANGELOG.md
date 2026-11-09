# Changelog

本文件记录 `goodhub-mcp-sdk` 的对外可见变更。契约破坏性变更必须在此标注并同步升 `CONTRACT_VERSION`。

## 0.6.0（2026-11-09）

### 新增
- **声明式 Prompt 能力（plugin-core prompts）**：`@McpPrompt`/`@PromptArg` 注解 +
  `PromptDispatcher`（`prompts/list` 契约下发 + `prompts/get` 的 `{{arg}}` 字面替换渲染 +
  capabilities 按有无声明）——**Kotlin 插件自带工具面 skill**（宿主 CoW 拉取 → appScoped
  skill，连接后才注入；与 Dart server 的 prompts 能力同权——ai-capability-export
  通用化轮：goodshare-data 内置 skill 退役后，来源侧自带通道补齐 Kotlin 侧缺口）。
  装配期急扫描 fail-fast（PromptArg 非 String / 返回非 String = 插件启动即崩，禁静默）。
- `initialize` capabilities 有 @McpPrompt 才声明 `prompts`（宿主 hasPrompts=false 自动降级）。
- 文档网补齐：`api.md` §2.4（@McpPrompt 字典）+ README §2.2.1 与接入清单项 + [AI_ONBOARDING.md](./AI_ONBOARDING.md) / AI_INTEGRATION_GUIDE 文档地图指针。

## 0.5.1（2026-11-09）

### 变更（退役清账）
- **私有 method `host/onDeviceComplete` 退役**：`OnDeviceOutcome.REVERSE_METHOD`
  切标准原语 `ai/complete`——`McpToolContext.onDeviceComplete(...)` 签名与联合态
  解析零变化（params/result 与契约 api.md §8.7 同形），插件代码零改动即迁移；
  **宿主须 ≥ 收编版**（ai/complete 反向受理），旧宿主回 -32601。
- `api.md` §7 更新收编迁移说明；拉取面 `host/onDeviceTaskStatus` 保留不变。

## 0.5.0（2026-11-09）

### 新增
- **deferred 联合态收编进 `ai/complete`（2026-11-09 用户拍板，收口 host/onDeviceComplete 前置）**：
  params 增 `allow_deferred`（缺省 false 存量零变化——冷态维持同步 fastFail）与 `expiry_ms`；
  冷态 + `allow_deferred: true` → 原子入池回联合态回执（`result.content.text` 含 `execution_mode`
  键的 JSON——解析纪律与 W6-a 同款：禁 JSON 嗅探误判同步产出）；结果拉取仍经
  `host/onDeviceTaskStatus`（Pull = 交付真理源）；`:contract` 增
  `AI_KEY_ALLOW_DEFERRED`/`AI_KEY_EXPIRY_MS`/`AI_KEY_EXECUTION_MODE`/`AI_KEY_TASK_ID`
  常量（冻结自检同步）。`api.md` 新增 §8.7「联合态回执」。

### 说明
- 契约演进为**纯增量**（params 字段 + 回执形态），`contract_version` 仍为 `v1`，AIDL 不动；
  缺省 `allow_deferred: false` 下行为与 0.4.0 完全一致，存量消费方零影响。

## 0.4.0（2026-11-08）

### 新增
- **AI 能力原语立法（M-A，goodhub ai-capability-export epic）**：`:contract` 新增 JSON-RPC method 层常量组
  （AIDL 接口签名零变更，`contract_version` 仍为 `v1`）——
  `METHOD_AI_COMPLETE`（`ai/complete`：`complete(instruction, input, config) -> 聚合文本`，任务语义由调用方编排）、
  `METHOD_AI_CANCEL`（`ai/cancel`：按 `cancel_id` 取消，幂等）、
  生成阶段四值 `AI_PHASE_*`（progress 帧 `params._meta.phase`，替代裸 message）、
  结构化失败码表十二码 `AI_FAIL_*`（`error.data.status`，统一 `error.code = -32000`；
  含 `DEVICE_THROTTLED` / `PREEMPTED_BY_HOST`）、建议退避字段 `AI_KEY_RETRY_AFTER_MS`（仅提示非承诺，SDK 不自动重试）、
  v2 流式预留位 `AI_PROGRESS_META_DELTA`（v1 禁发射，防 UTF-8 半字符外溢）。
- `McpContractTest` 冻结自检补两例（method/阶段常量 + 十二码表）。
- **AI 消费方工件（§3.5 双件拆分，ai-capability-export M-C）**：
  `:goodhub-ai-client`（业务层唯一 API 面：`GoodHubAi` 四入口——suspend `complete` /
  `completeStream` Flow / Java 回调档 / `completeSync` 零容忍便捷壳（主线程断言 +
  超预算熔断）；`AiRequest`/`AiConfig`/`AiEvent`/`AiPhase`/`AiFailure`/`AiSyncResult`
  自有类型；`isAvailable` 静态探测零 IPC；`AiFailure.createResolutionIntent` 授权引导件；
  `retryAfterMs` 透出）+ `:goodhub-ai-runtime`（连接生命周期 / `_async` 多帧关联 /
  phase 翻译 / 协程取消自动映射 `ai/cancel` / 宿主死亡确定性失败 `HOST_TERMINATED`；
  library Manifest 随包声明传输服务——业务层零 Manifest / 零服务注册）。
  **破层红线**：client 公开 API 零 contract/transport 类型（`GoodHubAiLayerTest`
  源级断言 + 门面 API 面冻结钉 + 探测零 IPC 断言）；runtime 帧路由核心
  （`AiRuntimeCore`）纯 Kotlin JVM 单测（_async ack 不断开 / progress token 关联 /
  cancel 幂等 / 死亡收敛 / 协程取消映射）。
- `api.md` 新增 §8「AI 能力原语」：请求/响应 JSON 形状、`_async` 握手、progress 阶段帧、取消幂等、
  十二码表升阶建议、文本模态边界（UTF-8 纯文本禁 Base64，超 128KB 线 `BUDGET_EXCEEDED`）、
  与 `sampling/createMessage`（收敛为适配壳）/ `host/onDeviceComplete`（退役映射别名）的收口关系；
  新增 §9「AI 消费方门面」（四入口心智对账 / 静态探测与授权引导 / 长文本与流式边界）。

### 说明
- 契约演进为**纯增量**（method 层常量 + 码表），AIDL 包名/方法集、发现约定四常量均未动；
  存量插件升级零影响，旧宿主对新 method 回 `-32601`（标准 JSON-RPC 语义）。
- 立法 SSOT：goodshare 仓 `docs/design/ai-app-split.md` §3.13；设计稿：goodhub 仓 `docs/design/ai-capability-export.md`。

## 0.3.0（2026-11-07）

### 新增
- **W6-a 联合态解析面**：`McpToolContext.onDeviceComplete(instruction, input, allowDeferred, expiryMs)`——
  借宿主端侧算力（私有 method `host/onDeviceComplete`，Tier 1 契约），回程统一解析为 `OnDeviceOutcome` 二态：
  `Immediate`（同步产出文本）/ `Deferred`（冷态入池回执：`task_id`/`status`/`expiry_ms`）。
  联合态装进现有 JSON 信封，`:contract` AIDL 不动（`contract_version` 仍为 `v1`）。
- `McpToolContext.onDeviceTaskStatus(taskId)`——按 Pull 以 `task_id` 拉取入池任务投影
  （私有 method `host/onDeviceTaskStatus`；**Pull = 交付真理源**，Push 不可依赖——freezer 吞 oneway）。
  ⚠️ 宿主 Binder 反向通路对该 method 的接线属 goodhub 后续工作包，未接线版本会异常完成。
- `api.md` 新增 §7「端侧算力转发」：联合态回执判定规则（`allow_deferred` 缺省 false = 存量语义零变化、
  `expiry_ms` 语义、deferred 缺 `task_id` 抛错）、Pull 拉取纪律与配额/计费口径。

### 说明
- 契约未变（`contract_version` 仍为 v1，AIDL 包名与方法集不变）；缺省 `allow_deferred: false` 下
  `onDeviceComplete` 行为与既有同步 failFast 语义一致，存量插件零影响。
- 解析判定红线：`content.text` 仅当为含 `execution_mode` 键的 JSON 对象才判联合态，其余一律 Immediate
  纯文本（禁 JSON 嗅探误判同步产出）。

## 0.2.0（2026-10-21）

### 新增
- `@McpTool` 新增 `visibility: ToolVisibility`（`PRIVATE` / `SHARED`，默认 `PRIVATE`）——工具**可见性范围**声明，
  由派发器写入 `tools/list` 每个工具对象的 `_meta.goodhub.visibility`（线级取值小写短名 `"private"` / `"shared"`）。
  **纯标记语义**：SDK 不据此剔除 `tools/list` 条目，也不拦截任何 `tools/call`——`tools/list` 始终反映 Server
  能力全集（不制造「写了方法却查不到」的调试死角），按标记如何聚合、是否收进共享目录全属宿主解析职责。
  默认 `PRIVATE` 即 default-deny：漏标即最严，与 explicit opt-in 原则一致。
- `@McpTool` 新增 MCP 标准 annotation `readOnlyHint` / `destructiveHint`：仅当显式声明 `true` 时才下发
  `annotations` 节点；两者都未声明则整个 `annotations` 缺席（hints 语义：未声明即无信息，不臆造 `false`）。
- 标记与 hints 分轨：`annotations` 归还 MCP 官方字段，GoodHub 私有扩展一律走 `_meta.goodhub`，互不污染命名空间。
- `GoodHubPluginService` 新增构造参数 `mcpConsumable: Boolean = false`——Server 级**自描述**标记，
  声明本服务是否可被宿主动态嵌入（注入到外部 LLM 会话 / workspace）。随 `initialize` 应答**恒下发**
  于 `_meta.goodhub.mcpConsumable`（默认 `false`，default-deny，与工具级 `visibility` 哲学一致）。
  **纯声明、非访问控制**：本 SDK 不据此做任何拦截或剔除，是否据此动态注入全属宿主（client 端）职责。

### 变更
- dummy 靶子：构造显式 `mcpConsumable = true`，作为「可被动态嵌入」的联调示例；
  `echo` 标 `visibility = SHARED` + `readOnlyHint`；`add` 只标 `readOnlyHint`（保持默认 `PRIVATE`）；
  `slowEcho` / `reverseDemo` 完全不标——三档组合供宿主侧对照验证分桶行为。

### 说明
- 契约未变（`contract_version` 仍为 v1，`mcp_protocol_version` 仍为 2025-06-18，AIDL 包名与方法集不变、发现约定四常量不变）
- 向后兼容 0.1.1：三个新注解参数均有默认值，存量插件源码无需改动即可升级；宿主侧暂不消费 `_meta.goodhub` 时行为完全不变

### 文档
- 新增 [`MANIFEST_SPEC.md`](./MANIFEST_SPEC.md)：《GoodHub MCP Manifest 跨平台语义规范 v1.0》。定义随包分发的静态语义契约（`serviceId` / `version` / `mcpConsumable` / `priority` / `keywords` / `regexPatterns` / `coreTools` 等），明确其**仅用于宿主本地 SQLite 路由与 LLM Context 装配、与传输层解耦**；并固化与现有 Android 运行时 SDK（`mcpConsumable` 构造参数、`visibility` 工具级标记）的映射关系，及 Split-Brain / iOS 传输 / 来源鉴权等 Open Issues。
- 新增 [`IOS_TRANSPORT_DESIGN.md`](./IOS_TRANSPORT_DESIGN.md)：《iOS Transport 设计：形态 A/B 选型推演》。专项目标为 iOS 的"派发执行"传输后端：形态 A（In-Process/Framework，宿主内内存调用，性能/安全最优但生态封闭）与形态 B（App Group 共享容器总线 + Darwin Notification，与 Android 第三方接入模型对称、开放但需同 team 与 HMAC 签名）；给出选型矩阵、与 Android 接入模型对齐的约束、及渐进落地路径（先 A 作 MVP → 抽象 Transport Interface → 后补 B）。
- `README.md` 新增「声明服务的可见性与动态嵌入（插件开发者必读）」教程章节，补全 `mcpConsumable`（Server 级）与 `visibility`（工具级）两级过滤网的接入指引、AND 选区语义、线级下发位置及与 [`MANIFEST_SPEC.md`](./MANIFEST_SPEC.md) 的关系；接入清单补 `mcpConsumable` 勾选项；`api.md` §2.2 最小示例补 `mcpConsumable = true` 构造演示。

## 0.1.1（2026-10-20）

### 新增
- `GoodHubPluginService.tryEmitEvent(String): Boolean`——推事件帧并回报成败（true=已投递 / false=宿主回调未注册或投递抛异常）。
  动机：`emitEvent` 内部静默吞掉投递异常，会吞掉「终帧推不出去」这个必须可观测的降级信号
  （终帧推不出 = 宿主侧悬挂，只能靠 goodhub 的 idle 超时兜底）。Dart 业务层宿主用本方法即可
  保留自己的 `[DEGRADE]` 日志能力。

### 说明
- 契约未变（`contract_version` 仍为 v1，`mcp_protocol_version` 仍为 2025-06-18，AIDL 包名与发现约定四常量不变）
- 本版为纯增量：向后兼容 0.1.0，宿主/插件可按需升级

## 0.1.0（2026-10-20）

首个独立发布版。来源：goodhub 仓 `android-sdk/` 目录拆分独立成仓（构建期本就零耦合，拆分不改行为）。

### 新增
- `:contract` 模块：GoodHub 本地插件接入契约的**唯一物理副本**
  - AIDL：`com.zzh.goodhub.mcp.IMcpChannel` / `IMcpCallback`（线级契约，包名永久冻结）
  - 常量：`McpContract`（发现 Action / meta-data key / permission 名 / 协议版本 / 契约版本）
- `:plugin-core` 模块：`goodhub-mcp-plugin-core.aar`（插件侧运行时 SDK，零第三方依赖、零 Flutter 依赖）
- `:sample-dummy-plugin` 模块：靶子/示例 APK（`serverId=dummy`），供宿主真机联调与外部接入参考
- Maven 发布坐标：`com.zzh.goodhub.mcp:goodhub-mcp-contract` / `com.zzh.goodhub.mcp:goodhub-mcp-plugin-core`
- 离线分发件：`scripts/dist.sh` 产出 `dist/maven-repo/`（标准 Maven 布局）+ zip，**免认证**即可消费
- 子类接管口子：`GoodHubPluginService` 新增 `protected` 成员 `handleJsonRpc` / `onHostCallback` /
  `onHostAnswerReverse` / `emitEvent`——业务逻辑在 Dart（Flutter 插件 App）的宿主可重写路由接管，
  不再被迫手搓 AIDL（默认行为不变，不重写仍走反射派发器）

### 契约
- `CONTRACT_VERSION = v1`
- `MCP_PROTOCOL_VERSION = 2025-06-18`

### 已知缺口
- `mcp-blob://` 大报文降级（≥128KB）尚未实现：当前仅支持纯内存通道，报文须 < 128KB
- Flutter 插件桥（`goodhub_plugin_bridge`）尚未实现：Flutter 侧插件 App 暂请直接用 Kotlin 原生 Service 接入
