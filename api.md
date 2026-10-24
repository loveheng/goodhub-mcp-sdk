# goodhub-mcp-sdk · API 参考（面向 AI Agent）

> **用途**：本文件是 goodhub-mcp-sdk 接口的**权威机器可读版**，供 AI Agent 在以下场景作为事实来源：
> 1. 生成第三方 Android 插件接入代码（server 端）；
> 2. 对接宿主（GoodHub）聚合层、解析 `tools/list` 报文（client 端）。
>
> 人类可读的接入指南见 [`README.md`](./README.md)。
> SDK 当前版本：**0.2.0**（契约版本 `CONTRACT_VERSION = v1`，MCP 协议版本 `2025-06-18`）。
>
> 跨平台静态语义契约（Manifest 规范，与传输层解耦）见 [`MANIFEST_SPEC.md`](./MANIFEST_SPEC.md)。

---

## 1. 一句话定位

GoodHub 本地插件 SDK：第三方 Android App 继承 `GoodHubPluginService`、用 `@McpTool` 标注业务方法，本 SDK 自动把方法暴露成 MCP 工具（生成 `tools/list` Schema + 路由 `tools/call`）。开发者无需手写 AIDL / 理解 Binder。

---

## 2. 最小可运行接入（插件侧）

### 2.1 依赖

```kotlin
dependencies {
    implementation("com.zzh.goodhub.mcp:goodhub-mcp-plugin-core:0.2.0")
}
```

- `:contract` 随 `api` 依赖自动传递，不必单独声明。
- 来源：GitHub Packages（`https://maven.pkg.github.com/loveheng/goodhub-mcp-sdk`，需带凭证）；或 `./scripts/dist.sh` 产免认证离线分发件。

### 2.2 业务类

```kotlin
import com.zzh.goodhub.mcp.plugin.GoodHubPluginService
import com.zzh.goodhub.mcp.plugin.McpTool
import com.zzh.goodhub.mcp.plugin.ToolVisibility
import com.zzh.goodhub.mcp.plugin.Param

class MyPlugin : GoodHubPluginService(
    serverId = "myapp",     // 全局唯一 ID（展示用，SDK 不做命名仲裁）
    serverTitle = "我的工具", // 展示名
    mcpConsumable = true    // 声明本服务允许被宿主动态嵌入（默认 false，default-deny）
) {
    @McpTool(description = "原样回显", readOnlyHint = true, visibility = ToolVisibility.SHARED)
    fun echo(@Param("text", description = "要回显的文本") text: String): String = text

    @McpTool(description = "两个整数相加")
    fun add(@Param("a") a: Int, @Param("b") b: Int): Int = a + b
}
```

### 2.3 AndroidManifest（组件声明权在插件 App；SDK 的 library Manifest 为空）

```xml
<service
    android:name=".MyPlugin"
    android:exported="true">
    <intent-filter>
        <action android:name="com.zzh.goodhub.action.MCP_SERVER" />
    </intent-filter>
    <meta-data android:name="com.zzh.goodhub.SERVER_ID" android:value="myapp" />
    <meta-data android:name="com.zzh.goodhub.SERVER_TITLE" android:value="我的工具" />
</service>
```

---

## 3. `@McpTool` 注解字段字典（全部字段）

| 字段 | 类型 | 默认值 | 语义 | 下发位置 / 行为 |
|---|---|---|---|---|
| `description` | `String` | `""` | 工具描述 | 进入工具对象的 `description` 与 `inputSchema.description` |
| `timeoutMs` | `Long` | `5000` | 同步调用预算红线（**仅元数据记录，当前不强制**） | — |
| `async` | `Boolean` | `false` | `true` 时 `tools/call` 立即回 `{"_async": true}`，真实结果经 `McpToolContext` 异步投递 | §4 |
| `readOnlyHint` | `Boolean` | `false` | MCP 标准 hint：本工具无副作用 | 仅 `true` 时出现在 `annotations.readOnlyHint` |
| `destructiveHint` | `Boolean` | `false` | MCP 标准 hint：本工具可能造成破坏性变更 | 仅 `true` 时出现在 `annotations.destructiveHint` |
| `visibility` | `ToolVisibility` | `PRIVATE` | **工具可见性范围标记（纯声明，见 §6）** | 写入 `_meta.goodhub.visibility` |

### 3.1 `GoodHubPluginService` 构造参数

| 参数 | 类型 | 默认值 | 语义 | 下发位置 / 行为 |
|---|---|---|---|---|
| `serverId` | `String` | —（必填） | 全局唯一 ID（展示用，SDK 不做命名仲裁） | `serverInfo.name` |
| `serverTitle` | `String` | —（必填） | 展示名 | `serverInfo.title` |
| `mcpConsumable` | `Boolean` | `false` | **Server 级自描述标记（纯声明，见 §6）**：声明本服务是否可被宿主动态嵌入（注入到外部 LLM 会话 / workspace）；**default-deny**，与工具级 `visibility` 哲学一致 | 随 `initialize` 应答**恒下发**于 `_meta.goodhub.mcpConsumable`（见 §5.3） |

`@Param(name, description = "", required = true)`：`name` 即 JSON-RPC 入参 key（缺省回退到参数名）；`required` 缺省 `true`，可选参数设 `false`。

支持返回类型（同步）：`String` / `Int` / `Boolean` / `Long` / `Float` / `Double`（反射 coerce）。

---

## 4. 运行时行为要点

- **自动 Schema**：扫描 `@McpTool` 方法生成 `tools/list` 的 `inputSchema`（运行时反射，非 KSP）。
- **自动路由**：`tools/call` 按 `name` 路由；`McpToolContext` 类型参数不进 Schema、由 SDK 注入。
- **异常隔离**：业务方法抛出的任何异常转标准 JSON-RPC `-32603`，**绝不穿透 Binder 致宿主崩溃**。
- **异步长任务**：`@McpTool(async = true)` 立即回 `_async` 占位，结果经 `McpToolContext.complete/fail` 异步投递（宿主必被唤醒，不悬挂）；首参注入 `McpToolContext`。
- **进度推送**：`McpToolContext.progress(progress, message)` 推 `notifications/progress`（带 `progressToken`）。
- **反向请求**：`McpToolContext.reverseRequest(method, params)` 发 server→client 请求（如 `sampling/createMessage`），经 `answerReverse` 回程闭环。
- **API 结构红线**：派发器 `ToolDispatcher` 为 `internal`、上下文构造器 `internal`、`pushEvent`/`reverseRequest` 为 `internal`；子类扩展点仅 `protected open` 的 `handleJsonRpc` / `onHostCallback` / `onHostAnswerReverse` / `emitEvent`。

---

## 5. 对外报文契约（`tools/list` 响应）

### 5.1 单工具对象样例

```json
{
  "name": "echo",
  "description": "原样回显",
  "inputSchema": { "type": "object", "properties": { "text": { "type": "string" } } },
  "annotations": { "readOnlyHint": true },
  "_meta": { "goodhub": { "visibility": "shared" } }
}
```

### 5.2 字段约定（宿主侧解析以本表为准）

| 路径 | 取值 | 规则 |
|---|---|---|
| `name` | string | 工具唯一名，来自方法名 |
| `description` | string | 来自 `@McpTool.description` |
| `inputSchema` | object | JSON Schema；`McpToolContext` 参数不出现 |
| `annotations.readOnlyHint` | `true` | **仅当插件显式声明 `readOnlyHint = true` 才出现** |
| `annotations.destructiveHint` | `true` | **仅当插件显式声明 `destructiveHint = true` 才出现** |
| `_meta.goodhub.visibility` | `"private"` / `"shared"` | GoodHub 私有标记；**字段缺失按 `"private"` 兜底** |

- `annotations` 节点**整体可能缺席**（无任何 hint 声明时）。**不要**用「有没有 annotations」作判据；缺失 ≠ 有副作用。
- GoodHub 私有扩展统一收在 `_meta.goodhub` 下；未来 `hidden` 之类**正交**维度会加在 `goodhub` 同级。

### 5.3 `initialize` 应答报文（Server 级自描述）

```json
{
  "protocolVersion": "2025-06-18",
  "capabilities": { "tools": {} },
  "serverInfo": { "name": "myapp", "title": "我的工具", "version": "<sdk-version>" },
  "_meta": { "goodhub": { "mcpConsumable": true } }
}
```

| 路径 | 取值 | 规则 |
|---|---|---|
| `_meta.goodhub.mcpConsumable` | `true` / `false` | **恒下发**（消除三态歧义），字段**不缺席**；宿主直接映射非空布尔。默认 `false`（default-deny） |

---

## 6. 可见性标记语义（极易误用，必读）

**两级过滤网（均为纯声明，SDK 不拦截、不剔除）**：
- **Server 级**：`GoodHubPluginService(mcpConsumable = true)` → 随 `initialize` 应答 `_meta.goodhub.mcpConsumable` 恒下发（§5.3）。声明本服务是否可被宿主动态嵌入（注入外部 LLM 会话 / workspace）。默认 `false`。
- **工具级**：`@McpTool(visibility = ...)` → 随 `tools/list` 各工具 `_meta.goodhub.visibility` 下发（§5.2）。声明单个工具的曝光分桶（`PRIVATE` / `SHARED`）。默认 `PRIVATE`。
- 两者**正交**：将来宿主侧动态嵌入的选区逻辑通常是 **AND**——`mcpConsumable == true` 且 `tool.visibility == SHARED` 才收进动态候选。具体聚合 / 视图裁剪规则完全由宿主（client 端）决定，本 SDK 只负责原样透传标记。

`@McpTool(visibility = ToolVisibility.PRIVATE | SHARED)` 是**纯声明标记，不是访问控制**：

- 默认 `PRIVATE` = default-deny：未显式标注即私有，漏标即最严。
- **SDK 不会**因 `visibility` 剔除任何 `tools/list` 条目——`tools/list` 始终反映 Server 能力全集（含私有工具），不制造「写了方法却查不到」的调试死角。
- **SDK 不会**拦截任何 `tools/call`——私有工具照常可被本机调用。
- **是否把工具收进共享目录 / 对外暴露给外部 Agent，完全由宿主（client 端）解析决定**。
- `annotations`（标准 hints）与 GoodHub 私有标记（`_meta.goodhub`）**分轨**：前者归 MCP 官方语义，后者归 GoodHub 扩展，互不污染命名空间。

> 历史方案曾把 `x-mcp-export-to-shared` 塞进 `annotations`——已废弃，以 §5 的 `_meta.goodhub.visibility` 为准。

---

## 7. 契约常量（冻结，勿改；宿主与插件共用）

| 项 | 值 |
|---|---|
| AIDL 包名 | `com.zzh.goodhub.mcp`（永久冻结） |
| 发现 Action | `com.zzh.goodhub.action.MCP_SERVER` |
| meta-data key | `com.zzh.goodhub.SERVER_ID` / `com.zzh.goodhub.SERVER_TITLE` |
| 权限名 | `com.zzh.goodhub.permission.MCP_SERVER`（已定义，由宿主侧选用作 `android:permission`） |
| MCP 协议版本 | `2025-06-18` |
| 契约版本 | `v1`（破坏性变更须升版并保持向下兼容） |

常量唯一出处：`com.zzh.goodhub.mcp.McpContract`（`:contract` 模块），由 `McpContractTest` 冻结自检。

---

## 8. 职责边界（SDK 做 / 不做）

**SDK 做（server 端）**：实现 `IMcpChannel.Stub` + `GoodHubPluginService` 基类、反射生成 Schema + 路由、异常隔离、异步长任务、进度推送、反向请求发起。

**SDK 不做（属 GoodHub 宿主 client 端）**：连接生命周期（`bindService`/状态机）、挂起请求队列与超时、命名主权仲裁（仅上报 `serverId`）、**安全闸门（同签名判定、信任白名单）**、大报文 `FileProvider` 解包侧、以及 §6 的可见性**聚合/暴露决策**。

---

## 9. 版本与消费

- 版本事实源：[`VERSION`](./VERSION)（`version` / `mcp_protocol_version` / `contract_version`）。
- 发布：GitHub Packages（`com.zzh.goodhub.mcp:goodhub-mcp-contract:0.2.0` / `:goodhub-mcp-plugin-core:0.2.0`）；或 `./scripts/dist.sh` 产免认证离线分发件。
- 契约未变：`CONTRACT_VERSION` 仍为 `v1`，AIDL 包名与方法集不变，向后兼容 `0.1.1`。
