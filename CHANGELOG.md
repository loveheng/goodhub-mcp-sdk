# Changelog

本文件记录 `goodhub-mcp-sdk` 的对外可见变更。契约破坏性变更必须在此标注并同步升 `CONTRACT_VERSION`。

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
