# goodhub-mcp-sdk · API 参考（面向 AI Agent）

> **用途**：本文件是 goodhub-mcp-sdk 接口的**权威机器可读版**，供 AI Agent 在以下场景作为事实来源：
> 1. 生成第三方 Android 插件接入代码（server 端）；
> 2. 对接宿主（GoodHub）聚合层、解析 `tools/list` 报文（client 端）。
>
> 人类可读的接入指南见 [`README.md`](./README.md)。
> SDK 当前版本：**0.6.0**（契约版本 `CONTRACT_VERSION = v1`，MCP 协议版本 `2025-06-18`）。
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
    implementation("com.zzh.goodhub.mcp:goodhub-mcp-plugin-core:0.6.0")
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

## 2.4 声明式 Prompt（`@McpPrompt` · 0.6.0）

MCP 标准 prompts 能力：Kotlin 插件自带「模板文本」注入宿主系统提示（宿主 CoW 拉取 → 注册为 appScoped skill，**连接后才注入**——未挂载来源不被介绍）。

```kotlin
@McpPrompt(name = "data-guide", description = "数据面工具口径")
fun dataGuide(@PromptArg("scope", description = "适用范围") scope: String): String =
    "数据面口径（{{scope}}）：list_items 检索、get_item 读全文"
```

- `{{arg}}` 占位符 **SDK 字面替换**（单次遍历不递归）；返回 `String` = 单条 user 消息模板。
- capabilities 有 @McpPrompt 才声明 `prompts`（无 → 宿主 hasPrompts=false 自动降级，Tier 2 零破坏）；
  **`initialize.capabilities.prompts` 是单层内容对象**（有声明时 `{}`），不得再内嵌一层 `prompts`。
- **装配期急扫描 fail-fast**：PromptArg 非 String / 返回非 String = 插件启动即崩（禁静默）。
- 与 @McpTool 分工：工具 = 副作用动词；prompt = 模板文本。

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
- **终帧可观测**：异步工具除 `ctx.complete/fail` 外，可用 `ctx.tryComplete(text)` / `ctx.tryCompleteRaw(result)` / `ctx.tryFail(code, message)` 取投递成败；返回 `false` = 宿主回调未注册或投递抛异常（终帧推不出 = 宿主悬挂），须自行记降级日志 / 落待补拉，禁静默丢结果。
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

## 7. 端侧算力转发（收编迁移至 `ai/complete` · W6-a 联合态）

> **0.5.1 起推理入口收口标准原语 `ai/complete`（§8）**：`McpToolContext.onDeviceComplete(...)` 签名不变、method 常量已切 `ai/complete`（params/result 与 §8.7 联合态同形，插件代码零改动即迁移）；私有 method `host/onDeviceComplete` 退役（宿主 ≥ 收编版，旧宿主回 -32601）。联合态解析纪律与拉取面（`host/onDeviceTaskStatus`，保留）不变。

插件（server 端）可借宿主 GoodHub 的**端侧**推理算力（与 `sampling/createMessage` 云端反向请求并存不互替），经 `McpToolContext.onDeviceComplete(...)` 调用（历史：私有 method `host/onDeviceComplete`，W6-a 契约）。

### 7.1 联合态回执（`OnDeviceOutcome`）

回程帧 `result.content.text` 经 `OnDeviceOutcome.parse` 解析为二态之一：

| 形态 | 判据 | 承载 |
|---|---|---|
| **同步 immediate** | `content.text` 非「含 `execution_mode` 键的 JSON 对象」 | `Immediate(text)`——端侧直出纯文本 |
| **异步 deferred** | `content.text` = `{"execution_mode":"deferred","task_id":"...","status":"queued","expiry_ms":86400000}` | `Deferred(taskId, status, expiryMs)`——冷态入池回执 |

- **联合态装进现有 JSON 信封**：`:contract` AIDL 不动（`String call(String)` / oneway 事件面均不变），`contract_version` 仍为 `v1`。
- **deferred 触发条件（三者同时满足）**：调用方显式 `allow_deferred = true` + 宿主任务池已装配 + 调用方身份可绑（内核 UID 推导，M5——无内核身份的 deferred 请求不受理）。
- **缺省 `allow_deferred: false` = 存量语义零变化**：冷态（模型未装载/降级窗口）维持同步 failFast error 帧，宿主不兜底云端。
- **`deferred` 回执缺 `task_id` = 宿主契约违约**：`parse` 抛 `IllegalArgumentException` 显式暴露，禁静默降级。
- `expiry_ms` 为距过期的毫秒数（缺省 24h，>24h 未消化作废清账）；未携带时为 `-1`，不可作过期判据，以 status 查询结果为准。

### 7.2 结果拉取（Pull = 交付真理源）

拿到 `Deferred` 后：UI 呈现「后台分析中」占位，随后经 `McpToolContext.onDeviceTaskStatus(taskId)`（私有 method `host/onDeviceTaskStatus`）拉取任务投影（`status` ∈ `queued` / `running` / `done`（含 `result`）/ `expired` / `FAILED_EXHAUSTED` / `not_found`）。

- **Pull 即交付**：宿主 `pendingDelivery` 态查询即置 `delivered` 并回结果；**严禁依赖 Push**——Android 12+ cgroup freezer 会吞发往冻结进程的 oneway 事件且发送方只见成功，Push（`IMcpCallback.onEvent`）仅是在途温热触达的 best-effort 加速。
- `FAILED_EXHAUSTED`（回池重试 ≥3 毒丸死信）/ `expired` 对调用方**可见不可恢复**，UI 应收敛占位并提示。
- 交付寻址绑定内核 UID 推导身份（M5）：A 插件不可见 B 的任务；status 查询按 callerKey 过滤。
- **配额与计费**：per-caller 8 条 queued / 全局 64 条；**计费点 = 执行时**（产出才计，受理入池不计）。
- ⚠️ **宿主接线现状**：`on_device_task_status` 目前仅实现于宿主导出工具面（网关 Allowlist）；Binder 反向通路 `host/onDeviceTaskStatus` 的接线属 goodhub 侧后续工作包——未接线版本调用会异常完成（宿主回落 sampling 错误帧），届时请回退 Push 事件面并保留 task_id 待宿主升级后补拉。

---

## 8. AI 能力原语（`ai/complete` / `ai/cancel` · 对外推理标准）

外部 App 消费 GoodHub 的 AI 算力（推理能力反向输出）：宿主只供 **`complete(instruction, input, config) -> 聚合文本`** 一个原语，任务语义（摘要 / 翻译 / 打标签 / 文本优化）一律由调用方 App 自己编排，宿主不内置任务模板。设计 SSOT = goodhub 仓 `docs/design/ai-capability-export.md`；接缝立法 = goodshare 仓 `docs/design/ai-app-split.md` §3.13。

**承载通道**：本地插件经 `IMcpCallback.onEvent` 推反向请求帧（有 `id` + `method`，与通知帧靠「有无 id」路由）→ 宿主处理后经 `IMcpChannel.answerReverse` 回帧。AIDL 接口签名零变更。远程（PC / 跨设备）调用方走网关投影工具 `host__ai__complete`（Allowlist 显式登记，语义同本节）。

**端云路由不在协议内**：`config` 不含路由字段——端侧 / 云端由设备主在 goodhub 设置里选择，调用方不得强制；算力消耗与成本归属始终是设备主的决定。

### 8.1 `ai/complete` 请求（params）

```json
{
  "instruction": "任务指令（单一明确，含负向硬约束与分隔符定界）",
  "input": "待处理文本（合法 UTF-8 纯文本）",
  "config": {
    "maxTokens": 1024,
    "temperature": 0.7,
    "responseFormat": { "type": "json", "value": "..." }
  },
  "_meta": { "progressToken": "tok_<n>" }
}
```

| 字段 | 必填 | 契约 |
|---|---|---|
| `instruction` | 是 | 任务指令；禁空串 |
| `input` | 是 | **合法 UTF-8 纯文本，禁 Base64 / 媒体数据**（纯文本 2B 模型喂图必乱码）；超 128KB 线直接 `BUDGET_EXCEEDED`（诚实降级） |
| `config.maxTokens` | 否 | 输出 token 预算；缺省取宿主默认；宿主按安全上限 clamp |
| `config.temperature` | 否 | 采样温度；缺省取宿主默认 |
| `config.responseFormat` | 否 | 约束解码规格（`{type: 'json'\|'regex', value}`，S17b 既有键集）；端侧 LiteRtLmRunner 自治、GenieSession 后端显式归因拒服务、云端透传 `response_format` |
| `_meta.progressToken` | 否 | opt-in 进度帧：携带后宿主按阶段发 `notifications/progress` 帧；不携带 = 无进度帧 |
| `allow_deferred` | 否 | **冷态联合态收编（0.5.0）**：缺省 `false` = 冷态同步 fast-fail（`MODEL_COLD`，存量零变化）；`true` = 端侧冷态转异步任务池入池（见 §8.7 联合态回执） |
| `expiry_ms` | 否 | deferred 任务保质期毫秒（缺省 24h，过期作废清账） |

- **config 未声明字段一律取宿主默认**；调用方禁感知宿主内部模型规格（模型适配数据不外泄）。
- **文本模态边界**：多模态（Vision）留 v2（输入形态演进为 `inputParts`），v1 禁混入字符串。

### 8.2 响应（同步直出 / `_async` 异步）

| 形态 | 触发 | 帧序 |
|---|---|---|
| **同步直出** | 引擎就绪（`WARM_STANDBY`）且短任务 | `answerReverse` 直接回 result |
| **异步受理** | 预计超 Binder 事务预算（冷装载 / 长生成） | ① `answerReverse` 回 `{"_async": true}` → ② （携带 progressToken 时）`onEvent` 推 progress 帧 N 条 → ③ `onEvent` 推同 `id` 终帧（result 或 error） |

- **同步 result 形状**：`{"content": {"type": "text", "text": "<产出>"}, "model": "goodhub-on-device"|"goodhub-cloud"}`——`model` 仅粗粒度档位标记（不泄漏宿主内部模型规格）。
- **`_async` 约定沿用 thirdconnect §1.2.2**：宿主对 `_async` 应答保持请求开启直到同 `id` 终帧；调用方不得假设受理即执行。
- **调用方可等冷启动（异步形态）**：`MODEL_COLD` 只在零等待预算的同步语义下 fast-fail；异步受理进入后冷装载属正常过程（发 `LOADING_MODEL` 阶段帧）。

### 8.3 progress 帧（阶段语义 `_meta.phase`）

```json
{ "jsonrpc": "2.0", "method": "notifications/progress",
  "params": { "progressToken": "tok_<n>", "progress": 0,
              "_meta": { "phase": "LOADING_MODEL" } } }
```

- `params._meta.phase` 线级取值四值单源：`STARTING_HOST` → `LOADING_MODEL` → `QUEUING` → `GENERATING`（常量 = `McpContract.AI_PHASE_*`；顺序即推荐展示次序，非进度承诺）。替代裸 `message` 字符串，业务层据此渲染等待态。
- 每次阶段跃迁至少一帧；`message` 字段可省略（人类可读补充，不作机器判据）。
- **`params._meta.delta` 为 v2 流式预留字段位，v1 禁发射文本**（端侧分帧存在 UTF-8 半字符边界风险，半字符外溢给外部生态不可接受）。

### 8.4 `ai/cancel`（异步取消原语）

```json
{ "jsonrpc": "2.0", "id": "srv_<n+1>", "method": "ai/cancel",
  "params": { "cancel_id": "<被取消的 ai/complete 请求 id>" } }
```

- **语义**：宿主中断生成并把在途请求从受理链摘除；result 为空对象 `{}`。
- **幂等**：对已完成 / 不存在的任务静默确认（不报错）——防取消与终帧竞态误报。
- **死亡清理为宿主侧对账**：调用方进程死亡（含被 LMK 强杀、未及发 cancel）即自动中断并释放其全部在途推理任务；调用方重启后不得假设上一笔调用仍在宿主侧执行。
- 协程 / 生命周期取消应自动映射本原语（SDK runtime 职责），业务层零取消代码。

### 8.5 结构化失败码表（十二码，`error.data.status`）

统一 error 帧：`{"error": {"code": -32000, "message": "<人类可读>", "data": {"status": "<十二码之一>", "retry_after_ms": 1234}}}`。一个码表两处消费（同步 FastFail 快照 + 异步 Failure 帧），禁两套枚举；线级常量 = `McpContract.AI_FAIL_*`。

| 码 | 语义 | 附加 data | 调用方升阶建议 |
|---|---|---|---|
| `MODEL_COLD` | 引擎冷态未就绪 | — | 转 `ai/complete` 异步形态等冷启动 |
| `RESOURCE_BUSY` | 受理槽占满（并发超限） | `retry_after_ms` | 按建议退避后重试或排队 |
| `HOST_OFFLINE` | 发起时宿主不可达（未装 / 未拉起） | — | 本地规则兜底；禁自旋重试 |
| `HOST_TERMINATED` | `_async` 在途期间宿主被系统击杀（LMK） | — | 稍后重试；禁与 `HOST_OFFLINE` 混用映射 |
| `BUDGET_EXCEEDED` | 输入超安全预算（>128KB 线 / 超模型上下文） | — | 分段处理；宿主禁静默截断产废文 |
| `UNAUTHORIZED` | 调用方未获 AI 借用授权 | — | 引导用户走授权页（SDK `createResolutionIntent`） |
| `RATE_LIMIT` | 频率超限 | `retry_after_ms` | 按建议退避；禁自旋 |
| `CALLER_NOT_IN_FOREGROUND` | 已信任第三方后台调用被拒 | — | 前台再调或申请放宽授权 |
| `USER_REJECTED` | 用户在确认卡链明确拒绝 | — | 尊重裁决，不自动重试 |
| `ENGINE_FAILED` | 推理引擎执行失败（端侧 / 云端） | — | 稍后重试；宿主不自动兜底另一侧 |
| `DEVICE_THROTTLED` | 硬件自保熔断（温控 ≥SEVERE / 低电量未充电） | — | 稍后重试；禁静默转云端 |
| `PREEMPTED_BY_HOST` | 被宿主自用会话协作式打断 | `retry_after_ms` | 按建议退避；无 KV Cache 换出承诺 |

- **`retry_after_ms` 语义**：宿主按在途任务预估的建议退避值，**仅提示非承诺**；SDK 不做自动重试（防重试风暴从调用方搬进 SDK），delay 决策归业务层。
- **标准 JSON-RPC 错误不在本表**：宿主过旧不识 method → `-32601`；参数违规 → `-32602`。
- 端侧引擎不可用即结构化失败（`isError: false` + 失败 status 形态承载，防熔断误计数）；是否重试 / 改异步由调用方决定。

### 8.7 联合态回执（deferred 收编 · 0.5.0）

冷态 + `allow_deferred: true` 时，宿主受理器**原子入池**（W6 任务池：顺风车/充电窗口消费，计费点 = 执行时产出才计）并回联合态回执——`result.content.text` 为含 `execution_mode` 键的 JSON 对象：

```json
{ "content": { "type": "text",
    "text": "{\"execution_mode\":\"deferred\",\"task_id\":\"…\",\"status\":\"queued\",\"expiry_ms\":86400000}" },
  "model": "goodhub-on-device" }
```

- **解析纪律**：`content.text` 仅当为含 `execution_mode` 键且值为 `"deferred"` 的 JSON 对象才判联合态，其余一律纯文本产出（模型输出本身可能是 JSON——禁 JSON 嗅探误判）。
- **结果拉取（Pull = 交付真理源）**：以 `task_id` 经 `host/onDeviceTaskStatus`（Tier 1 私有 method）拉取任务投影——Push 不可依赖（Android 12+ freezer 吞 oneway）；`pendingDelivery` 态查询即置 delivered 并回结果；身份寻址绑定内核 UID（A 消费方不可见 B 的任务）。
- **deferred 回执缺 `task_id` = 宿主契约违约**：调用方解析须抛错显式暴露（禁静默降级）。
- **交付寻址绑定身份**：无内核身份（HTTP 无 Binder UID 面）的 deferred 请求不受理。

### 8.6 与既有反向推理接缝的关系

| 接缝 | 地位 |
|---|---|
| `sampling/createMessage`（§3.11，goodshare 仓） | **保留兼容（存量插件契约），实现收敛为 `ai/complete` 的适配壳**（D2 拍板）——封死双推理路由 |
| `host/onDeviceComplete`（§3.11.2，goodshare 仓） | 私有 method 收口：退役或映射为 `ai/complete` 别名，单 method 立法（W5 存量迁移期两法并存） |

宿主侧配额 / 隔离 / 审计单源收口后，两条旧路径共享同一受理链（路由 / 限流 / 审计只写一遍）。

---

## 9. AI 消费方门面（`:goodhub-ai-client` / `:goodhub-ai-runtime`）

> **AI Agent 接入入口 = [AI_ONBOARDING.md](./AI_ONBOARDING.md)**（端到端步骤 + 机械验收 + 常见坑）——本节是手册引用的 API 字典单源。

外部 App **消费** goodhub 的 AI 算力（推理能力反向输出）的业务层 API（ai-capability-export §3.5，消费方与供工具插件两条路径互不污染）：

```kotlin
dependencies {
    implementation("com.zzh.goodhub.mcp:goodhub-ai-client:0.6.0")   // API 面
    runtimeOnly("com.zzh.goodhub.mcp:goodhub-ai-runtime:0.6.0")     // 实现件
}
```

**分层与破层红线**：`client`（稳定 API 面，公开签名零 contract / transport 类型）→ `runtime`（连接生命周期 / `_async` 关联 / phase 翻译 / `ai/cancel` 映射）→ `:contract`。业务层看不到契约与传输——`AiResult` / `AiEvent` / `AiFailure` 全部 client 自有类型（源级断言钉死，`GoodHubAiLayerTest`）。

### 9.1 四入口心智对账（防把 `suspend complete` 误当同步接口）

| API 入口 | 语言 | 冷态容忍度 | 取消机制 |
| --- | --- | --- | --- |
| `GoodHubAi.completeStream(request): Flow<AiEvent>` | Kotlin | **可等**（发射 `Phase` 阶梯） | 协程取消自动发 `ai/cancel` |
| `GoodHubAi.complete(request): AiResult`（suspend） | Kotlin | **可等**（挂起直到终态；失败抛 `AiFailureException`） | 同上 |
| `GoodHubAi.completeSync(request): AiSyncResult` | Java 兜底 | **零容忍**（冷态直接 FastFail；主线程调用抛 `IllegalStateException`） | 不可主动取消（超预算自动熔断） |
| `GoodHubAi.complete(request, listener): AiCall` | Java | **可等**（回调 Phase） | `AiCall.cancel()` → `ai/cancel`（幂等） |

- **默认用异步形态**；`completeSync` 仅轻量一行拿结果场景，失败即升阶分流（`AiFailure.shouldEscalateToAsync`：`MODEL_COLD` / `RESOURCE_BUSY` / `BUDGET_EXCEEDED` 转 `completeStream`）。
- **`retryAfterMs` 仅提示非承诺**——SDK 不自动重试，delay 决策归业务层。
- `AiEvent.Phase` 四值（`STARTING_HOST` / `LOADING_MODEL` / `QUEUING` / `GENERATING`）渲染等待态；`AiEvent.Result(text, model)` 终产出。

### 9.2 静态探测与授权引导

| API | 职责 |
| --- | --- |
| `GoodHubAi.isAvailable(context)` | PackageManager 零 IPC 检查（宿主未装 = false，主线程安全）——UI 条件渲染入口；**禁掺运行时态**（装载态归运行时通道、授权态归 `UNAUTHORIZED` 码） |
| `AiFailure.createResolutionIntent(context)` | `UNAUTHORIZED` → 授权页跳转意图（按公开常量本地构造，宿主侧 `getCallingUid()` 实证）；未装宿主 = null |
| `GoodHubAi.get()` | 入口（runtime 类名反射自注册——零显式初始化；缺 runtime 依赖抛 `IllegalStateException` 显式暴露） |

### 9.3 长文本与流式边界

- 大文本荷载沿用 thirdconnect TC9 控制/数据面分离约定（128KB 线；超线宿主回 `BUDGET_EXCEEDED` 诚实降级）。
- 流式 v1 不提供（`complete` 为整段返回语义）；`_meta.delta` 为 v2 预留位，宿主 v1 不发射。

---

## 10. 契约常量（冻结，勿改；宿主与插件共用）

| 项 | 值 |
|---|---|
| AIDL 包名 | `com.zzh.goodhub.mcp`（永久冻结） |
| 发现 Action | `com.zzh.goodhub.action.MCP_SERVER` |
| meta-data key | `com.zzh.goodhub.SERVER_ID` / `com.zzh.goodhub.SERVER_TITLE` |
| 权限名 | `com.zzh.goodhub.permission.MCP_SERVER`（已定义，由宿主侧选用作 `android:permission`） |
| MCP 协议版本 | `2025-06-18` |
| 契约版本 | `v1`（破坏性变更须升版并保持向下兼容） |
| AI 能力原语 method | `ai/complete` / `ai/cancel`（§8；JSON-RPC method 层常量 + 十二失败码 + `_meta.phase` 四值，`McpContract.AI_*`） |

常量唯一出处：`com.zzh.goodhub.mcp.McpContract`（`:contract` 模块），由 `McpContractTest` 冻结自检。

---

## 11. 职责边界（SDK 做 / 不做）

**SDK 做（server 端）**：实现 `IMcpChannel.Stub` + `GoodHubPluginService` 基类、反射生成 Schema + 路由、异常隔离、异步长任务、进度推送、反向请求发起。

**SDK 不做（属 GoodHub 宿主 client 端）**：连接生命周期（`bindService`/状态机）、挂起请求队列与超时、命名主权仲裁（仅上报 `serverId`）、**安全闸门（同签名判定、信任白名单）**、大报文 `FileProvider` 解包侧、以及 §6 的可见性**聚合/暴露决策**。

---

## 12. 版本与消费

- 版本事实源：[`VERSION`](./VERSION)（`version` / `mcp_protocol_version` / `contract_version`）。
- 发布：GitHub Packages（`:goodhub-mcp-contract` / `:goodhub-mcp-plugin-core` / `:goodhub-ai-client` / `:goodhub-ai-runtime`，均 `0.6.0`）——由 `vX.Y.Z` tag 触发 CI，四个工件一次性发布，CI 校验 tag 与 `VERSION` 一致；或 `./scripts/dist.sh` 产免认证离线分发件（同样四件齐全并逐一校验 AAR 存在）。
- 契约未变：`CONTRACT_VERSION` 仍为 `v1`，AIDL 包名与方法集不变，向后兼容 `0.1.1`；0.4.0 增量为 JSON-RPC method 层（`ai/complete` / `ai/cancel` 常量与失败码表）+ AI 消费方工件（§9），存量插件零影响。
