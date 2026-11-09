---
status: active
updated: 2026-11-09
---

# goodhub-mcp-sdk · 功能落点索引（数据）

> 索引数据文件（唯一数据源）；登记规约 = `goodhub-mcp-sdk-index` skill，机制正文 = 全局 `project-index`。增量登记，禁止一次性铺满。

## 代码落点（归属表）

| 领域 | 覆盖功能 | 代码落点（锚点 · 展开命令） | 文档落点 |
|---|---|---|---|
| contract | Binder 契约唯一物理副本：AIDL 接口、发现常量、AI 线级键名与失败码 | `contract/src/main/aidl/`、`contract/src/main/java/com/zzh/goodhub/mcp/McpContract.kt`（`find contract/src -type f`） | `api.md` §8 / `docs/architecture/overview.md` |
| plugin-core | 插件侧运行时：Service 基类、`@McpTool`/`@McpPrompt`、反射派发、异步上下文与异常隔离 | `plugin-core/src/main/java/com/zzh/goodhub/mcp/plugin/`（`find plugin-core/src/main -type f`） | `api.md` §2–§6 |
| ai-client | AI 消费方门面：`GoodHubAi` 四入口与自有类型，公开 API 零 contract/transport 类型 | `goodhub-ai-client/src/main/java/com/zzh/goodhub/ai/`（`find goodhub-ai-client/src/main -type f`） | `api.md` §9 / `AI_ONBOARDING.md` |
| ai-runtime | AI 实现件：连接生命周期、`_async` 多帧关联、phase 翻译、取消与死亡收敛 | `goodhub-ai-runtime/src/main/java/com/zzh/goodhub/ai/runtime/`（`find goodhub-ai-runtime/src/main -type f`） | `api.md` §8 / `AI_INTEGRATION_GUIDE.md` |
| sample | 可安装靶子 / 最小示例，宿主真机联调 e2e 端点 | `sample-dummy-plugin/src/main/`（`find sample-dummy-plugin/src/main -type f`） | `README.md` §2 |
| docs | 工程内文档体系（域路由、写码规范、索引三表） | `docs/`（`find docs -type f`） | `docs/README.md` |

跨域隐式契约：`ai-runtime` 联动 `ai-client`（编译依赖 runtime → client，client 零模块内依赖；破层由 `GoodHubAiLayerTest` 源级断言钉死）；`plugin-core` 与 `ai-runtime` 联动 `contract`（AIDL 生成类同名同包，改包名即存量插件全 bind 失败）。

## 文档落点

| 主题 | 文档路径 | 定位 |
|---|---|---|
| 功能落点索引（本文件） | `docs/project/index.md` | 归属表 / 文档落点 / 架构债三表 |
| 写码规范 | `docs/project/coding-standards.md` | 分层矩阵、落点决策、门禁映射 |
| 架构拓扑与依赖方向 | `docs/architecture/overview.md` | 五模块拓扑、分层依赖与职责边界 |
| 接缝 SDK 定位与能力评估 | `docs/architecture/seam-sdk-assessment.md` | 定位定性、评价维度、能力短板与处置优先级 |
| 线级契约冻结面 | `docs/reference/contract-freeze.md` | 冻结项索引、唯一代码出处与变更流程 |
| 文档结构索引 | `docs/README.md` | 域 → 文档 → 一句话定位 |
| 对外 API 参考（存量根平铺） | `api.md` | 线级契约权威机器可读版，不进 docs/ 域 |

## 架构债与依赖违例登记

| # | 债 | 落点 | 状态 |
|---|---|---|---|

> 活跃表只留未结项；结项后压缩一行移入归档区，过程细节归 Git log / epic devlog。

## 用法

先按归属表（或关键词映射）定位 1~2 个领域 → 用行内展开命令或该路径作 include_pattern 限定搜索 → 定位不到才全量 grep。机制与两级展开协议见全局 `project-index` skill；命令与环境见 `goodhub-mcp-sdk-workflow`。
