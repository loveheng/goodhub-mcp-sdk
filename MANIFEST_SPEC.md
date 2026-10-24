# GoodHub MCP Manifest 跨平台语义规范 v1.0

> 状态：提案 v1.0 ｜ 范围：**语义层（Platform-Agnostic Core）** ｜ 不与任何传输协议绑定

本规范定义 GoodHub 生态中，跨平台（Android / iOS）共享的**静态语义契约**——一份随 App 包分发的 `mcp-manifest.json`。
它的唯一职责是：**为宿主提供本地路由索引与 LLM Context 装配所需的声明性元数据**。

本规范**刻意剥离**以下关切，交由其他层负责：

- **传输机制**（Android Binder/AIDL、iOS In-Process / App Group）—— 由各平台 transport 后端独立实现；
- **执行语义**（`tools/call` 的派发与回调）—— 由运行时 SDK 或 transport 后端定义；
- **安全鉴权**（来源签名、调用方身份校验）—— 由 transport 层与宿主安全模块负责。

核心设计原则（沿用现有 Android SDK 哲学）：

1. **default-deny**：未显式声明的服务/工具，默认不可被动态嵌入。
2. **声明即建议，非强制**：Manifest 是宿主路由的"加速索引"与"建议清单"，**运行时 `tools/list` 才是可执行能力的权威全集**。
3. **语义与传输解耦**：本规范的字段集不应包含任何平台专有传输细节（如 URL Scheme、端口、Binder action）。

---

## 1. 分层定位

```text
┌────────────────────────────────────────────────────────┐
│  跨平台统一语义层 (本规范)                              │
│  · Manifest 字段语义 (mcpConsumable / routing / ...)    │
│  · 本地 SQLite 缓存与索引表结构                         │
│  · 关键词 / 正则 / 意图过滤与 LLM Tools 拼装算法        │
└──────────────────────────┬─────────────────────────────┘
                           │ 调度派发 (Transport Interface)
         ┌─────────────────┴─────────────────┐
         ▼                                   ▼
┌────────────────────────┐      ┌────────────────────────┐
│ Android 传输实现        │      │ iOS 传输实现            │
│ (Native Binder/AIDL)   │      │ (In-Process / AppGroup)│
│ · 双向回调、getCallingUid 鉴权 │  │ · 规避后台被杀          │
│ · 保持现有 SDK 零破坏   │      │ · 共享容器总线 + 闭环回调│
└────────────────────────┘      └────────────────────────┘
```

语义层在两端 100% 同构；传输层平台分治，**绝不**为"统一"而退化为公开、单向、不可靠的传输通道。

---

## 2. 文件约定

- **文件名**：`mcp-manifest.json`（建议置于 App 资源根，随包分发）。
- **编码**：UTF-8，标准 JSON。
- **根对象**：必须含 `manifestSpecVersion`（本规范版本），便于宿主按版本解析演进。
- **发现机制**：由 transport 后端负责（Android 由宿主扫描、iOS 由 App Group 导入等），**本规范不约束发现方式**。
- **导入后**：宿主将 Manifest 写入本地 SQLite，路由匹配只查本地库，**不激活插件进程**。

---

## 3. 字段字典（核心语义字段）

| 字段 | 类型 | 必填 | 默认值 | 语义 | 约束 |
|---|---|---|---|---|---|
| `manifestSpecVersion` | `String` | 是 | — | 本规范版本（语义化，如 `"1.0"`） | 宿主按此选择解析策略 |
| `serviceId` | `String` | 是 | — | 服务全局唯一 ID（展示/索引用，不做命名仲裁） | 反向 DNS 风格，跨设备唯一 |
| `displayName` | `String` | 是 | — | 展示名 | 面向用户/宿主 UI |
| `version` | `String` | 是 | — | 服务语义版本（`semver`） | 用于 SQLite 缓存失效与升级同步 |
| `mcpConsumable` | `Boolean` | 否 | `false` | **Server 级自描述**：声明本服务是否可被宿主动态嵌入（注入外部 LLM 会话 / workspace） | default-deny，与 `GoodHubPluginService.mcpConsumable` 同义 |
| `priority` | `Integer` | 否 | `50` | 路由优先级（0–100） | 命中多个服务时，宿主据此排序候选工具 |
| `keywords` | `String[]` | 否 | `[]` | 意图关键词列表（中文/自然语言短语） | 用于 SQLite 模糊/全文匹配 |
| `regexPatterns` | `String[]` | 否 | `[]` | 触发正则模式列表 | 每条为合法正则；用于结构化意图命中（如金额、日期） |
| `coreTools` | `String[]` | 否 | `[]` | 建议注入 LLM 视野的**核心工具名白名单** | 元素须与运行时工具名一致；为空则无工具可注入 |
| `transport` | `Object` | 否 | `{}` | **传输层扩展位**（各平台自定义） | 本规范不解析、不约束其 schema；Android 可省略，iOS 后端自行定义 |

> 说明：原草案中的 `routing` / `endpoints.callUrlScheme` 等**传输相关结构不进入语义层核心**。
> 平台专有传输描述（如 iOS 的唤醒 Scheme、共享容器标识）应置于 `transport` 扩展对象内，由对应 transport 后端消费，语义层只读前 9 个字段。

---

## 4. 默认值与 default-deny 哲学

- `mcpConsumable` 默认 `false`：存量服务必须**显式声明 `true`** 才会进入 LLM 动态候选池，避免意料外的 Token 消耗与路由干扰。
- `coreTools` 为空：即便 `mcpConsumable == true`，宿主也无工具可注入——**显式 opt-in 工具**。
- `priority` 默认 `50`：未标注优先级的服务居中排序，不抢占。
- `keywords` / `regexPatterns` 为空：仅 `serviceId` 精确匹配可用，自然语言能力退化但不误触发。

---

## 5. 与现有 Android 运行时 SDK 的映射

本规范不替代 Android 运行时 SDK，二者互补、语义对齐：

| Manifest 字段 | 运行时 SDK 对应物 | 说明 |
|---|---|---|
| `mcpConsumable` | `GoodHubPluginService(mcpConsumable: Boolean = false)`（构造参数，见 `api.md` §3.1） | 同义；运行时 `initialize` 应答恒下发 `_meta.goodhub.mcpConsumable`（见 `api.md` §5.3） |
| `coreTools[i]` | `@McpTool(visibility = ToolVisibility.SHARED)`（见 `api.md` §6） | Manifest 列出的工具，其运行时注解应标 `SHARED`；`PRIVATE` 工具不应进入 `coreTools` |
| `serviceId` | `serverId`（基类构造入参） | 同一全局唯一 ID |
| `displayName` / `version` | `serverTitle` / `BuildConfig.SDK_VERSION` + 插件 App 版本 | 展示与版本信息 |
| （无） | `tools/list` 各工具 `_meta.goodhub.visibility` | **运行时 `tools/list` 是权威能力全集**；Manifest 仅路由加速 |

**选区逻辑（宿主侧 AND）**：

```
动态嵌入候选 = (mcpConsumable == true)
             AND (tool.name ∈ coreTools)
             AND (命中 keywords / regexPatterns / serviceId 精确匹配)
```

- `visibility = PRIVATE` 的工具即使被列入 `coreTools`，运行时仍可被宿主本地直接 `tools/call`（SDK 不拦截），但**路由层不应将其注入 LLM 视野**——以 Manifest 声明为准。
- 若 Manifest 与运行时不一致（见 §7 风险），以**运行时 `tools/list` 为最终可执行集合**，Manifest 仅作路由建议。

---

## 6. 宿主侧使用约束（职责边界）

1. **仅本地匹配**：路由阶段只读 SQLite 缓存，不拉起插件进程、不发跨 App 调用。
2. **LLM Context 装配**：按 §5 的 AND 选区拼装 `tools` 数组喂给模型；`coreTools` 是"建议白名单"，宿主可叠加自身策略（如按用户身份裁剪）。
3. **不保证强一致**：Manifest 是编译期静态声明，可能与运行时实现漂移（见 §7）。宿主**不得**因 Manifest 声明某工具就假定其一定存在/可用；最终可执行性以 `tools/list` 为准。
4. **不承载传输与安全**：Manifest 不出网、不携带调用凭据；来源鉴权与参数安全由 transport 层负责。

---

## 7. 已知风险与 Open Issues

### 7.1 Split-Brain（双源一致性）
Manifest 静态随包，若开发者改了业务 Tool 却忘改 Manifest（`coreTools` 漏增/改名），宿主按 Manifest 路由会调到不存在的工具。
**缓解（推荐实践，未强制）**：在插件编译期通过 **KSP 注解处理器**扫描 `@McpTool` 自动导出 `mcp-manifest.json`，从源头消灭手写双份配置。v1.0 不强制，留作构建工具链演进项。

### 7.2 来源鉴权（防伪造 / 抢夺）
Manifest 本身不含鉴权信息。若 transport 层依赖 Scheme / 共享容器，须由 transport 后端校验 Manifest **来源签名**（防恶意 App 抢注 `serviceId` / 伪造记账服务）。
**本规范不定义校验方式**，交由 transport 层与宿主安全模块。

### 7.3 iOS 传输实现（专项）
iOS 独立 App 不能可靠地用单向 Scheme 做双向 RPC（无反向回调、后台唤起受限、URL 参数泄露）。
- **形态 A（首选）**：In-Process 插件 / Framework，宿主进程内加载，内存调用。
- **形态 B（独立 App）**：App Group 共享容器总线 + Darwin Notification 触发，指令/结果走共享 SQLite 队列，规避 URL 明文与单向回调。
**留作下一阶段专项推导，不阻碍 Android 版本封板。**

### 7.4 大报文参数
复杂工具参数（图片 base64、长文本）不应经 URL / 短信道传递。传输层应使用共享容器（FileProvider / App Group）承载，Manifest 不约束此问题。

---

## 8. 版本与兼容性

- **规范版本**：`manifestSpecVersion` 采用语义化版本（`MAJOR.MINOR`）。
- **向后兼容**：新增**可选**字段不破坏旧宿主解析（旧宿主忽略未知字段）；**删除/重命名/改类型**既有字段须升 `MAJOR` 并走 Expand-Contract。
- **与 SDK 版本独立**：本规范版本与 `goodhub-mcp-sdk` 的 `SDK_VERSION` / `CONTRACT_VERSION`（AIDL）相互独立演进；Manifest 不涉及 AIDL 线级契约。

---

## 9. 合规示例

```json
{
  "manifestSpecVersion": "1.0",
  "serviceId": "com.local.expense",
  "displayName": "本地记账与预算",
  "version": "1.0.0",
  "mcpConsumable": true,
  "priority": 70,
  "keywords": ["记账", "预算", "花了", "支出", "报销", "门票费", "消费"],
  "regexPatterns": ["\\d+(\\.\\d+)?(元|块|刀|¥|\\$)"],
  "coreTools": ["expense_add_record", "expense_get_budget"],
  "transport": {
    "_comment": "传输相关字段由具体 transport 后端定义，语义层不消费。Android 可省略；iOS 后端自行扩展。"
  }
}
```

> 注意：示例刻意**未包含** `endpoints.callUrlScheme` 之类传输字段——那是 transport 层职责，不应污染语义契约。

---

*相关文档：`api.md`（Android 运行时 SDK 接口，含 `mcpConsumable` 构造参数与 `visibility` 工具级标记）、[`IOS_TRANSPORT_DESIGN.md`](./IOS_TRANSPORT_DESIGN.md)（iOS 传输层形态 A/B 选型推演）、`CHANGELOG.md`（SDK 版本演进）。*
