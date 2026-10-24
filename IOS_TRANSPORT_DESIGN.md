# iOS Transport 设计：形态 A/B 选型推演

> 状态：设计草案 v1.0 ｜ 范围：**传输层（Transport）—— iOS 后端专项** ｜ 依赖 [`MANIFEST_SPEC.md`](./MANIFEST_SPEC.md)（语义层）
> 日期：2026-10-22

本文是 [`MANIFEST_SPEC.md`](./MANIFEST_SPEC.md) 第 7.3 节 Open Issue 的专项推演。
语义层（Manifest 字段、本地 SQLite 路由、LLM Context 装配）在两端 100% 同构，**本文件只讨论 iOS 的"派发执行"这一步该用什么传输后端**，
回答一个核心问题：**iOS 上，宿主如何可靠、安全地把 `tools/call` 派发到一个插件并拿回结果。**

---

## 0. 为什么必须专项设计（不重述也须锚定）

[`MANIFEST_SPEC.md`](./MANIFEST_SPEC.md) 已裁定：Android 保留原生 Binder/AIDL，iOS 另起对称后端，**绝不用单向 URL Scheme 冒充双向 RPC**。
原因在评估中已固化，此处仅列硬约束，作为选型底线：

1. **iOS 无可靠反向回调**：`UIApplication.openURL` 单向；被唤起 App 在前台，宿主在后台，反向 `openURL` 宿主不可靠（后台唤起受限、Scene 多窗口、用户可能被切走）。
2. **iOS 后台严苛**：独立 App 被唤起后仅前台存活数秒，无法长连 Socket / 本地 HTTP server。
3. **URL 明文泄露**：参数塞 Scheme query 会进系统/日志/Safari 链路，敏感数据（金额、报销）暴露。
4. **Scheme 抢夺**：任何 App 可注册同 Scheme，恶意仿冒拦截/伪造调用。

因此 iOS transport 只有两条务实路线：**形态 A（In-Process，无跨进程）** 与 **形态 B（App Group 共享容器总线，受控跨进程）**。

---

## 1. iOS 跨进程通信手段 Inventory（决策素材）

| 手段 | 方向 | 是否带数据 | 是否需同 team | 备注 |
|---|---|---|---|---|
| URL Scheme (`openURL`) | 单向（→） | query 限长、明文 | 否 | 不可靠反向，排除 |
| Universal Link | 单向（→） | 同 Scheme | 否 | 仅发现/拉起，排除执行 |
| **App Group 共享容器** | 双向（文件） | 任意（落盘） | **是** | 形态 B 核心载体 |
| **Darwin Notification** | 触发（无 payload） | 否（仅事件） | 是（同 team） | 形态 B 唤醒信号 |
| 本地网络 (WebSocket/HTTP) | 双向 | 任意 | 否 | 需 Local Network 权限 + 后台受限，排除 |
| 共享 Keychain | 双向（小数据） | 小 | 是 | 鉴权凭据可用，非执行通道 |
| XPC | 双向 | 任意 | 系统/同 App 扩展 | 第三方 App 不可用，排除 |
| 内嵌脚本引擎 (JS/WASM) | 进程内 | 任意 | 否 | 形态 A 动态性合规路径 |

**结论**：第三方独立 App 间的受控跨进程通信，在 iOS 上**唯一现实组合是 App Group 共享容器 + Darwin Notification**（形态 B）；
若不愿跨进程，则只能把插件代码拉进宿主进程内执行（形态 A）。

---

## 2. 形态 A：In-Process 插件 / Framework

### 2.1 架构
插件代码以**动态 framework（或脚本包）** 形式存在，由宿主在进程内加载并直接内存调用。无独立 App、无跨进程 IPC、无沙盒边界。

```text
┌──────────────────────────────────────────┐
│ 宿主 App 进程                             │
│  ┌────────────┐   内存调用   ┌──────────┐ │
│  │ 路由/LLM 层 │ ──────────▶ │ 插件 Fmwk │ │
│  │ (读 SQLite) │ ◀────────── │ (coreTools)│ │
│  └────────────┘   返回值     └──────────┘ │
└──────────────────────────────────────────┘
```

### 2.2 加载与发现
- 宿主预置 framework（随宿主发版），或经**合规脚本通道**加载：JS 包经 `JavaScriptCore`、WASM 经内嵌运行时执行——**规避 App Store 对"下载并执行原生代码"的审核红线**。
- 插件向宿主注册 `coreTools` 实现（函数指针 / 闭包 / JS 导出），注册表常驻内存。

### 2.3 执行与回调
- 纯内存调用：请求→返回值同步或 `async/await`，**天然双向、零序列化、零延迟**。
- 无"进程唤醒""后台存活"问题——始终在宿主进程内。

### 2.4 安全模型
- **攻击面最小**：无跨进程信道，无 Scheme 抢夺、无明文外泄。
- 信任根在宿主：**必须校验插件 framework 的代码签名 / 来源**（仅加载宿主自签或审核过的包）；脚本包需校验哈希/签名防篡改。
- 沙盒内隔离：若需限制插件权限（文件/网络），须用脚本引擎的 capability 沙盒（如 JSCore 不暴露敏感 API），原生 framework 则享有宿主同等权限（风险高，需强审核）。

### 2.5 与 Manifest 语义层对接
- 路由阶段完全一致（读 SQLite + LLM 装配）。
- 派发阶段：宿主按 `coreTools` 名查内存注册表直接调用，**不触发任何传输**。
- Manifest 的 `transport` 扩展可省略（进程内无需唤醒标识）。

### 2.6 优点
- 性能最佳（内存调用）、最可靠（无异步跨进程竞态）、最安全（无 IPC 攻击面）。
- 无后台/沙盒/Scheme 任何痛点，**工程成本最低**。

### 2.7 限制与适用场景
- **动态性差 + 生态封闭**：插件须随宿主发版或经合规脚本通道下发，**无法像 Android 第三方 APK 那样"独立上架、独立更新、宿主无感发现"**。
- 原生动态 framework 受 App Store 审核约束（禁止下载原生可执行）；脚本包（JS/WASM）合规但表达能力/性能受限。
- **适用**：宿主**自研或强管控**的能力扩展（如宿主内置的笔记/日历/记账模块），不面向外部第三方开发者。

---

## 3. 形态 B：App Group 共享容器总线 + Darwin Notification

### 3.1 架构
插件为**独立 App**（与 Android 第三方 APK 模型对称），经 App Group 共享容器做异步双向通信，Darwin Notification 仅作"有新消息"的唤醒信号（**不带 payload**）。

```text
┌──────────────┐   写指令    ┌─────────────────────────────┐   读指令    ┌──────────────┐
│  宿主 App     │ ─────────▶ │ App Group 共享容器           │ ◀───────── │  插件 App    │
│ (路由/LLM 层) │            │  · dispatch_queue.sqlite     │            │ (coreTools)  │
│              │ ◀───────── │  · result_queue.sqlite       │ ─────────▶ │              │
└──────┬───────┘   读结果    └─────────────────────────────┘   写结果    └──────┬───────┘
       │                                                                         │
       │  Darwin Notification (wake: "goodhub.dispatch")                          │
       └─────────────────────────────────────────────────────────────────────────┘
```

### 3.2 共享容器与队列
- 共享位置：`FileManager.containerURL(forSecurityApplicationGroupIdentifier: "group.com.goodhub")`。
- 双向队列用**两张 SQLite 表**（或文件）隔离：
  - `dispatch_queue`：宿主写、插件读（字段：`call_id, tool_name, arguments_json, created_at, ttl`）。
  - `result_queue`：插件写、宿主读（字段：`call_id, result_json, error_json, finished_at`）。
- `call_id` 关联请求/响应（异步配对）。

### 3.3 跨进程触发（Darwin Notification）
- 宿主写完指令 → 发 `CFNotificationCenterGetDarwinNotifyCenter()` 通知（如 `"group.com.goodhub.dispatch"`）。
- **通知本身不带数据**——插件被唤醒后去 `dispatch_queue` 读具体指令。
- 插件写完结果 → 发 `"group.com.goodhub.result"` 通知唤醒宿主读取。
- 通知须同 team（App Group 授权），且**任何人可发**——故通知仅作触发，真实数据须经签名/nonce 校验（见 3.6）。

### 3.4 指令 / 结果报文协议（建议）
```jsonc
// dispatch_queue 一条
{ "call_id": "uuid", "tool_name": "expense_add_record",
  "arguments_json": "{ \"amount\": 5000 }", "ttl_sec": 120 }
// result_queue 一条
{ "call_id": "uuid", "result_json": "{...}", "error_json": null,
  "sig": "<hmac_of_payload_with_shared_secret>" }
```
- 敏感参数落共享容器（**非 URL**），规避明文泄露。
- `ttl` 防队列堆积（超时未消费则宿主标记失败，不悬挂）。

### 3.5 生命周期与可靠性
- 插件前台执行：被通知唤醒后在前台跑工具，结果写回再通知宿主；宿主在后台时 iOS 会限期唤醒插件（有限秒数），长任务需分段/异步（同 Android `async` 约定）。
- **竞态与幂等**：`call_id` 去重；插件崩溃/超时不写结果 → 宿主 `ttl` 兜底转失败。
- 比 Scheme 可靠：数据落盘 + 显式通知，不依赖单向 URL 回调链。

### 3.6 安全模型
- **同 team 隔离**：App Group 仅同开发者团队可见，天然隔离其他团队 App。
- **来源校验**：共享容器同 team 内仍多 App 可写，须在报文带 `sig`（基于团队共享密钥的 HMAC）防伪造/投毒；宿主只信任带有效签名的指令与结果。
- **敏感数据**：落盘于共享容器，依赖同 team 沙盒；极高敏数据建议容器内再加密（密钥走共享 Keychain）。
- **Scheme 抢夺免疫**：完全不依赖 URL Scheme，无抢夺面。

### 3.7 与 Manifest 语义层对接
- 路由/装配阶段完全一致。
- 派发阶段：宿主写 `dispatch_queue` + 发 Darwin Notification；与 Manifest `coreTools` 名对应。
- Manifest `transport` 扩展可承载 App Group 标识（`group_id`）与通知名，供 transport 后端消费。

### 3.8 优点
- **与 Android 第三方接入模型对称**：独立 App、独立上架、宿主发现即接入，动态性最佳。
- 规避 Scheme 三大痛点（无单向回调、无后台长连、无 URL 泄露）。
- 数据落盘、显式唤醒，可靠且可审计。

### 3.9 限制与适用场景
- 需**同开发者团队**（App Group 限制）——若 GoodHub 是开放市场（多团队第三方），需引入"团队中介/代理 App"或放宽到共享 Keychain 方案，复杂度上升。
- 异步文件 IO + 通知，比内存调用慢（毫秒~十毫秒级），有竞态需 `call_id`/ttl 兜底。
- 插件前台执行期间用户被切到插件 App（体验有切换感）。
- **适用**：面向**外部第三方独立 App** 的开放接入（与当前 Android `intent-filter + AIDL` 生态对齐）。

---

## 4. 选型矩阵

| 维度 | 形态 A（In-Process） | 形态 B（App Group） |
|---|---|---|
| 性能 | ⭐⭐⭐ 内存调用，零 IPC | ⭐⭐ 文件 IO + 通知 |
| 可靠性 | ⭐⭐⭐ 无异步竞态 | ⭐⭐ 需 call_id/ttl 兜底 |
| 安全（抗抢夺/泄露） | ⭐⭐⭐ 无 IPC 攻击面 | ⭐⭐ 需 HMAC 签名 + 同 team |
| 动态性（独立上架/更新） | ❌ 随宿主发版/脚本通道 | ✅ 独立 App 自更新 |
| 生态开放度（外部第三方） | ❌ 强管控 | ✅ 开放对称于 Android |
| 后台/沙盒痛点 | 无 | 无（落盘+唤醒，非长连） |
| 开发复杂度 | 低（内存调用） | 中（队列/通知/签名） |
| 对宿主侵入 | 高（需加载/管控插件代码） | 低（仅读写共享容器） |
| App Store 合规 | 脚本包合规；原生动态库受限 | 完全合规（App Group 标准能力） |

---

## 5. 决策推演与推荐

### 5.1 与 Android 接入模型对齐的约束（关键）
当前 Android `goodhub-mcp-sdk` 的模式是：**第三方独立 App** 经 `intent-filter` 被发现、经 AIDL `tools/call` 被调用。
若 GoodHub 希望**跨平台对第三方开发者保持一致的接入心智**（独立 App、独立上架、宿主无感发现、对称 capability），
则 **iOS 必须选形态 B（App Group）**——形态 A 的"宿主内嵌"模型与 Android 现有生态**不对齐**，会制造两套开发者体验。

### 5.2 推荐结论
- **默认 / 开放生态路径 → 形态 B（App Group）**：当 GoodHub 面向外部第三方独立 App 接入时（与 Android 对称），
  即便有同 team 约束与异步复杂度，也是**唯一能对齐 Android 接入模型且合规**的路线。
- **宿主自研 / 强管控路径 → 形态 A（In-Process）**：当插件是宿主内置能力（笔记/日历/记账等自研模块），
  追求极致性能/安全/简单、可接受发版耦合时，形态 A 工程成本最低、最稳。
- **不应选**：任何基于 URL Scheme 的"伪 RPC"路线（已在 [`MANIFEST_SPEC.md`](./MANIFEST_SPEC.md) 第 7.3 节否决）。

### 5.3 渐进路径建议
1. **先落地形态 A 作 MVP**：宿主自研若干核心插件（记账/笔记/日历）跑通语义层 + 路由 + 内存派发，
   快速验证 `MANIFEST_SPEC` 全链路，不引入跨进程复杂度。
2. **再抽象 Transport Interface**：把"派发执行"抽成接口（语义层已解耦），形态 A 作为首个实现。
3. **后补形态 B 实现**：面向第三方开放时，新增 App Group transport 后端，复用同一语义层与同一 Transport Interface，
   宿主侧零改造即可切换/并存两类插件。

---

## 6. Open Issues（留待专项）

- **B 的同 team 约束**：开放市场（多开发者团队）下 App Group 不可用，需设计"GoodHub 代理 App / 团队中介"或共享 Keychain 替代方案——单独推演。
- **B 的大报文**：图片/长文本经共享容器传递，需定义 blob 落盘约定（呼应 [`MANIFEST_SPEC.md`](./MANIFEST_SPEC.md) 第 7.4 节）。
- **A 的脚本引擎沙盒**：JS/WASM 插件的 capability 限制（文件/网络/系统 API）如何最小权限授予。
- **统一 Transport Interface 契约**：定义派发接口的跨形态签名（call、cancel、progress、reverseRequest），供 Android Binder 与 iOS A/B 三后端共用。
- **来源鉴权标准化**：B 的 HMAC 共享密钥分发与轮换机制。

---

## 7. 参考

- [`MANIFEST_SPEC.md`](./MANIFEST_SPEC.md) —— 跨平台语义层规范（本设计的上游依赖）
- `api.md` —— Android 运行时 SDK 接口（AIDL/Binder 传输，形态 B 在 iOS 的对等物）
- `CHANGELOG.md` —— SDK 版本演进
