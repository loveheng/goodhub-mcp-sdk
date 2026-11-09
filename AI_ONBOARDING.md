# AI Agent 接入手册（GoodHub 对外 AI 算力 · Onboarding）

> **读者**：要把 GoodHub 的 AI 算力接入自己项目的 AI Agent / 开发者。本手册是**端到端步骤型**入口：前置条件 → 三步接入 → 机械验收 → 常见坑。细节单源：API 形状 = [api.md](./api.md)（§8 契约 / §9 消费方门面）；接入后规范 = [AI_INTEGRATION_GUIDE.md](./AI_INTEGRATION_GUIDE.md)。禁复制正文的指针纪律对 AI 同样适用。
> 状态：v1（2026-11-09，随 SDK 0.5.1；真机全链验证 = goodhub `integration_test/ai_complete_e2e_test.dart` + goodshare 消费端真推理取证）。

## 0. 我能获得什么

GoodHub 宿主对外输出一个推理原语 **`ai/complete(instruction, input, config) → 聚合文本`**：

- **端侧**（设备本地小模型，零成本、离线可用）或 **云端**（BYOK，消耗宿主用户额度）——路由由**宿主用户**在设置里选择，调用方无路由参数；
- 任务语义由你的项目编排（摘要 / 提取 / 打标签 / 改写），宿主不内置模板；
- 结构化失败十二码 + 建议退避 + 幂等取消 + 进度阶段（`STARTING_HOST / LOADING_MODEL / QUEUING / GENERATING`）；
- 配额 / 隔离临时会话 / 内容围栏 / 违规冻结由宿主受理链统一承载。

**两条通道按角色选**：

| 你是 | 通道 | 走本手册哪节 |
|---|---|---|
| Android App（Kotlin，消费算力） | `:goodhub-ai-client` 消费端工件 | §1–§4 |
| PC / 非 Android（HTTP 客户端） | 网关投影 `host__ai__complete`（Streamable HTTP :8766） | §5 |

## 1. 前置条件 checklist（宿主侧，一次性）

- [ ] 手机已装 **GoodHub**（宿主 App，≥ 收编版 2026-11-09 之后的构建）
- [ ] GoodHub 内已配置推理来源：端侧（资源中心导入端侧模型 + 设置页装「LiteRT-LM 引擎 NPU 档」运行库）或云端（BYOK Key）
- [ ] 你的 App 与 GoodHub 同机安装；首次调用时宿主会弹**信任卡**（同签名生态免卡）——用户确认一次即入白名单
- [ ] 消费侧 `minSdk = 34`

> **运行库坑（真实事故）**：宿主端侧模型的 NPU 档运行库是**数据目录内的下载件**——若宿主数据被清（重装/清数据），模型文件在但引擎装载会以 `Input tensor not found` 失败（GPU fallback 不兼容）。表现像模型损坏，实为运行库缺位：设置页「运行库 · LiteRT-LM 引擎（NPU 档）」重装即愈。

## 2. 三步接入（Android · Kotlin）

### 步骤 1：依赖（约 0.5.1 起）

```kotlin
dependencies {
    implementation("com.zzh.goodhub.mcp:goodhub-ai-client:0.5.1")  // API 面
    runtimeOnly("com.zzh.goodhub.mcp:goodhub-ai-runtime:0.5.1")    // 实现件（零显式初始化）
}
```

来源：GitHub Packages（需凭证）或 `scripts/dist.sh` 离线分发件（见 README §6）。

### 步骤 2：最小代码（接入代码单源 = 本节；GUIDE §0 指向此处）

```kotlin
// 条件渲染（零 IPC、主线程安全）：宿主未装时隐藏 AI 入口
if (GoodHubAi.isAvailable(context)) { showAiButton() }

// 默认推荐：进度/阶段全量档（绑定 viewModelScope——页面退出自动取消并停宿主算力）
viewModelScope.launch {
    GoodHubAi.get().completeStream(AiRequest(
        instruction = "从下文提取地点列表，仅输出 JSON 数组，无解释无包裹",
        input = "昨天去了杭州西湖，今天到上海外滩开会，明天回北京。",
    )).collect { event -> when (event) {
        is AiEvent.Phase   -> renderWaiting(event.phase)   // Preparing 阶梯
        is AiEvent.Result  -> showResult(event.text)       // 聚合产出
        is AiEvent.Failure -> mapFailure(event.failure)    // 只做 UI 映射（十二码）
    } }
}

// 一键挂起档（失败抛 AiFailureException——catch 后按码分流）
val text = GoodHubAi.get().complete(AiRequest(instruction, input)).text

// Java 兜底：completeSync（冷态直接 FastFail，主线程调用抛 IllegalStateException）
```

要点（细则 = GUIDE）：`instruction` 用三段式（角色任务 + 负向硬约束 + 分隔符定界）；禁 GlobalScope；`retryAfterMs` 仅提示非承诺、SDK 不自动重试。

### 步骤 3：真机验证（机械验收清单）

按序断言，全过 = 接通：

1. `GoodHubAi.isAvailable(context)` → **true**（宿主未装 = false，先装宿主）
2. 发起一次调用 → 收到 `AiEvent.Result`（端侧热态）**或** 结构化 `AiEvent.Failure`——**失败也是接通的证据**（十二码透出说明全链已到宿主受理链）：
   - `MODEL_COLD`（冷态）→ 转异步形态等 `LOADING_MODEL`（本就是设计语义）
   - `UNAUTHORIZED` → 宿主信任卡未确认：`AiFailure.createResolutionIntent(context)` 引导授权页
   - 其余码 → [AI_INTEGRATION_GUIDE.md](./AI_INTEGRATION_GUIDE.md) 附表升阶分流
3. 宿主侧日志锚（`adb logcat | grep AI-CAP`）：`[AI-CAP] caller=<你的包名> skill=ai/complete ... ok`——调用方身份、耗时、路由去向全量审计（恒开）
4. 取消语义：协程 scope 取消 → 宿主侧 `[AI-CAP]` 出现中断收口、算力即时让槽

## 3. 后台分析形态（deferred 联合态，可选）

适合「发起即返回、宿主空闲时消化」的产品形态（如条目后台摘要）：

```kotlin
// params 增 allow_deferred（经 completeSync/complete 请求体；契约 api.md §8.7）
// 冷态 + allow_deferred=true → 联合态回执：
//   {"execution_mode":"deferred","task_id":"…","status":"queued","expiry_ms":…}
// 结果拉取（Pull = 交付真理源，Push 不可依赖）：宿主导出工具 on_device_task_status
// 以 task_id 轮询；pendingDelivery 态查询即交付。
```

解析纪律：`content.text` 仅当为含 `execution_mode` 键且值为 `"deferred"` 的 JSON 才判联合态，其余一律纯文本产出（禁 JSON 嗅探误判）。真实参考实现 = goodshare 仓 `lib/service/on_device_service.dart`（登记 / 30s 前台轮询 / bodyMd5 时效 / 事件收敛）。

## 4. 非 Android / PC 路径（网关投影）

宿主开启 PC 远程网关（设置页，:8766，Bearer 配对 Token）后，HTTP 客户端直接调投影工具 `host__ai__complete`（Streamable HTTP MCP）：

```bash
curl -s http://<手机IP>:8766/mcp -X POST \
  -H 'Authorization: Bearer <token>' -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"pc-agent","version":"0"}}}'
# 用响应头 Mcp-Session-Id 续发 tools/list / tools/call（name=host__ai__complete）
```

语义与 Android 通道完全同源（同一受理链；网关同步通道为零等待语义——冷态直接 `MODEL_COLD` 结构化失败，请暖机后再调或按码重试）。

## 5. 常见坑（全部真机踩过）

| 现象 | 真因 | 处置 |
|---|---|---|
| `isAvailable` = false | 宿主未装 / 未启动 | 装 GoodHub；`createResolutionIntent` 引导 |
| 首次调用弹信任卡 | 第三方 default-deny | 用户确认一次入白名单；同签名免卡 |
| `UNAUTHORIZED` | 未获授权 / 违规冻结 | 授权页引导；冻结在宿主设置「AI 能力输出」面板解冻 |
| 引擎装载 `Input tensor not found` | **运行库缺位**（GPU fallback 不兼容），非模型损坏 | 宿主设置页重装「LiteRT-LM 引擎（NPU 档）」 |
| `MODEL_COLD` | 引擎冷态（同步零等待语义） | 转异步形态等冷启动；或宿主侧先暖机 |
| `RESOURCE_BUSY` / `RATE_LIMIT` / `PREEMPTED_BY_HOST` | 并发槽 / 频次 / 宿主自用抢占 | 按 `retryAfterMs` 退避；禁自旋重试 |
| `completeSync` 抛 IllegalStateException | 主线程调用 | 改协程档（设计如此） |
| `flutter test integration_test` 后宿主数据全清 | flutter 装机 = uninstall+install | e2e 后重建正式入口；模型/运行库需重导（宿主侧运维） |

## 6. 文档地图（禁双源，各读各的）

| 文档 | 装什么 |
|---|---|
| 本手册 | 接入步骤 / 机械验收 / 常见坑 |
| [api.md](./api.md) §8 / §9 | 线级契约形状（请求/响应/`_async`/progress/十二码）与门面 API 字典——**权威机器可读版** |
| [AI_INTEGRATION_GUIDE.md](./AI_INTEGRATION_GUIDE.md) | 接入后规范（分层 / Prompt 三段式 / 清洗 / 预算升阶 / 交互状态机）+ 失败码速查 |
| [api.md](./api.md) §2.4 | **供工具的插件侧**：`@McpPrompt` 声明式模板（插件自带工具面 skill，宿主 CoW 拉取连接才注入） |
| goodhub 仓 `docs/design/ai-capability-export.md` | 设计 SSOT（架构 / 处理模型 / 里程碑对账） |
| goodshare 仓 `docs/design/ai-app-split.md` §3.13 | 接缝裁决（与 sampling / 退役私有 method 的关系） |

版本要求：SDK **0.5.1+**（`ai/complete` 收编后形态）｜宿主 ≥ 收编版构建｜AIDL 契约 `v1`（发现四常量冻结）｜MCP 协议 `2025-06-18`。
