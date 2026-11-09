# 业务层 AI 接入规范指南（GoodHub 对外 AI 能力 · 消费方）

> 定位：外部 App 消费 GoodHub 的 AI 算力（`ai/complete` 原语）时的**调用方侧**接入标准。宿主契约面零任务模板——任务语义全部由调用方编排，本指南防每个接入方重复踩坑（裸调 IPC / 模型寒暄废话 / 静默覆盖丢文本）。设计 SSOT = goodhub 仓 `docs/design/ai-capability-export.md` §3.8；API 参考 = [api.md](./api.md) §9。
> 状态：v1（2026-11-08 随 SDK 0.4.0 发布，当前适用于 **0.6.0**；toolkit 二期待首个真实接入方按本指南接入后提炼）。

## 0. 最小接入

> **接入代码单源 = [AI_ONBOARDING.md](./AI_ONBOARDING.md) §2**（依赖坐标 + 最小代码 + 机械验收清单）——本指南管「接入之后怎么用对」，接入步骤与验收在手册，禁双源复制。
>
> **供工具的插件侧**（自带模板/skill）：`@McpPrompt` 声明式模板见 [api.md](./api.md) §2.4——宿主 CoW 拉取后注册为 appScoped skill（连接才注入）。

## 1. 架构分层

- **禁 UI 组件直调 `GoodHubAi`**——下沉 UseCase / Repository 层。
- 调用必须绑定 `viewModelScope` / Lifecycle 作用域：页面退出 → 协程自动 Cancel → SDK 自动发 `ai/cancel`，宿主即停算力。**禁 GlobalScope 跑无界任务**（用户离开后仍烧宿主算力 = 客大欺主）。

## 2. 2B Prompt 三段式

端侧供给是小参数模型，prompt 质量即产出质量：

1. **角色与任务**：单一明确（一次只做一件事；要摘要就别让它顺带翻译）。
2. **负向硬约束**：显式禁止寒暄前缀 / 解释 / Markdown 包裹——例：「仅输出 JSON 数组，无解释、无代码块包裹」。
3. **分隔符定界**：待处理文本用明确边界包围，防与指令混淆——例：「<<<输入开始>>>…<<<输入结束>>>」。

参数推荐（经验参考，不进契约）：提取/分类类 `temperature≈0.2`；改写类 `≈0.5`；`maxTokens` 按预期产出留 1.5~2 倍余量。

## 3. 输出清洗与校验

- **废话前缀剥离**（启发式，可误杀）：常见寒暄语/前缀修剪；**清洗失败保留原文**。
- Markdown / 引号剥离（模型爱包裹——规范 2 的负向约束是第一道，本条是兜底）。
- **产物有效性校验**：空产物判失败；长度骤变（相对输入）预警。
- 真正的安全网是规范 5 的 Diff + Undo——清洗丢了内容可撤销。

## 4. 预算预检与升阶分流

- 输入长度三档处理范式：放行 / 提示等待或分段 / 前端拦截。**阈值数值从快照 `maxContextLength` 按比例推导（model-specs 单源），勿写死数字**；经验界：中文 >2 万字即接近 2B 模型 KV Cache 物理极限，>128KB 宿主直接 `BUDGET_EXCEEDED` 拒收。
- **`completeSync` FastFail 后禁自旋重试**——按码升阶：
  - `MODEL_COLD` / `RESOURCE_BUSY` / `BUDGET_EXCEEDED` → 转 `completeStream`（可等冷启动 / 建议退避）；
  - `HOST_OFFLINE` / `UNAUTHORIZED` → 本地规则兜底（引导安装 / `createResolutionIntent` 授权页）；
  - `RATE_LIMIT` / `PREEMPTED_BY_HOST` / `DEVICE_THROTTLED` → 按 `retryAfterMs` 退避后重试（仅提示非承诺）。
- `retryAfterMs` 仅提示非承诺：SDK 不自动重试，delay 决策归业务层（防重试风暴）。

## 5. 交互状态机（可撤销 / 可对比 / 防覆盖）

改写类场景（润色/续写/重写）必须满足：

- **Diff 预览优先**：新文本先与原文本对比呈现，用户手动采纳；
- **确认替换成对入 Undo**：原文本与新文本成对推入 App 自身撤销栈——清洗/覆盖出错可一键还原；
- **禁静默覆盖**：绝不在用户无感知下替换用户文本。

## 附：失败码速查（十二码，契约 api.md §8.5 单源）

| 码 | 升阶建议 |
| --- | --- |
| `MODEL_COLD` | 转 `completeStream`（异步可等冷启动） |
| `RESOURCE_BUSY` / `RATE_LIMIT` / `PREEMPTED_BY_HOST` | 按 `retryAfterMs` 退避 |
| `HOST_OFFLINE` | 引导安装/启动 GoodHub（`isAvailable` 条件渲染兜底） |
| `HOST_TERMINATED` | 稍后重试（宿主在途被系统回收） |
| `BUDGET_EXCEEDED` | 分段处理（勿重试原请求） |
| `UNAUTHORIZED` | `createResolutionIntent` 引导授权 |
| `CALLER_NOT_IN_FOREGROUND` | 前台再调 |
| `USER_REJECTED` | 尊重裁决，不自动重试 |
| `ENGINE_FAILED` / `DEVICE_THROTTLED` | 稍后重试；宿主不自动兜底另一侧 |
