---
name: goodhub-mcp-sdk-standards
description: goodhub-mcp-sdk 写码规范入口：分层依赖方向、新功能落点决策、契约冻结项、并发与降级红线、CI 门禁映射。新增模块/改 AIDL/写异步工具/改失败码前先读；条款正文在 docs/project/coding-standards.md，命令查 workflow，落点查 index。
---

# goodhub-mcp-sdk · 写码规范（规约入口）

> **条款正文（SSOT）= `docs/project/coding-standards.md`**。本 skill 只放触发场景与定位边界，不复制条款（防双源）。机制口径见全局 `dev-loop` 与 `ai-sideeffect-guard`。

## 何时加载

- 新增模块、改动模块间依赖方向或 `build.gradle.kts` 的 `dependencies`。
- 改 AIDL / `McpContract` 常量 / 发现 Action / 失败码 / 阶段值（线级冻结面）。
- 新增 `@McpTool` 异步工具、反向请求、回调或跨线程状态（并发与写安全）。
- 改异常收口、取消、宿主死亡收敛（容错与降级）。
- 新增或调整 CI 检查项、单测护栏（门禁映射）。

## 定位边界（三件套分工）

| 需要什么 | 查哪里 |
|---|---|
| 写码规则 / 分层矩阵 / 落点决策 / 门禁映射 | `docs/project/coding-standards.md` |
| 构建、测试、发布命令 / 环境硬约束 | `.agents/skills/goodhub-mcp-sdk-workflow/SKILL.md` |
| 功能 → 代码落点 / 文档落点 / 架构债 | `docs/project/index.md`（规约 = `goodhub-mcp-sdk-index` skill） |
| 文档落点与域路由 / docs 写后自检 | `.agents/skills/goodhub-mcp-sdk-docs/SKILL.md` |

## 硬红线速查（三条，正文与例外见条款文档）

1. `goodhub-ai-client` 源零 `com.zzh.goodhub.mcp.*` 与 AIDL 类型引用（R1 源级断言）。
2. AIDL 包名 / 发现常量 / 权限名 / 契约版本永久冻结（R2 契约冻结自检）。
3. 业务异常转 `-32603`、发射失败回滚 pending 并结构化失败收口，绝不穿透 Binder 或静默悬挂。

## 维护协议

- 条款随代码提炼增量生长，禁预写空泛条目；正文变更同轮刷新 `updated` 并核对机械镜像（单测 / `.github/workflows/ci.yml`）。
- 本 skill 不随条款条目增删回改；只在新触发场景出现时补一行。
