---
status: active
updated: 2026-11-09
---

# 线级契约冻结面

SDK 与宿主之间的契约是线级（wire-level）的，改动即破坏存量插件的 bind 与事务路由。冻结项清单与语义正文的**唯一出处**是根 `README.md` §4 与 `api.md` §8；本文档只登记冻结面的代码落点、机械自检与变更流程，不复述契约正文。

## 冻结项索引

| 类别 | 唯一代码出处 | 机械自检 |
|---|---|---|
| AIDL 接口与生成类 | `contract/src/main/aidl/com/zzh/goodhub/mcp/IMcpChannel.aidl`、`IMcpCallback.aidl` | `contract/src/test/java/com/zzh/goodhub/mcp/McpContractTest.kt` |
| 发现 Action / meta-data key / 权限名 / MCP 协议版本 / 契约版本 | `contract/src/main/java/com/zzh/goodhub/mcp/McpContract.kt` | 同上（常量冻结断言） |
| AI 线级键名、阶段四值、失败十二码 | `McpContract.kt` 的 `AI_*` 常量 | `goodhub-ai-client/src/test/java/com/zzh/goodhub/ai/GoodHubAiLayerTest.kt`（阶段四值 / 十二码 / 门面形态同值冻结） |

AIDL 包名冻结的原因在 `README.md` §4：生成的 `DESCRIPTOR` 是接口全限定名，随每次 Binder 事务作为 interface token 上线；改包名即存量插件全部 bind 失败。

## 变更流程

1. 先确认变更是否属线级形状（字段名、枚举值、包名、Action、键名）——属则必须升契约版本，并保持向下兼容；Expand-Contract 流程见 `dev-loop §2`。
2. 在 `McpContract.kt` 改常量（一处改，禁在 client / runtime / 宿主各写一份）；同步刷新对应机械断言的期望值。
3. 跑 `./gradlew --console=plain test` 使 R1/R2 护栏生效；命令与环境查 `.agents/skills/goodhub-mcp-sdk-workflow/SKILL.md`。
4. 线级契约文档 `api.md` §8 与 `README.md` §4 同轮刷新，防止权威文档与代码漂移。

## 版本与发布

仓级版本唯一出处 = `VERSION`；四个工件的坐标与 `BuildConfig.SDK_VERSION` 同源。CI 在 `vX.Y.Z` tag 触发时校验 tag 版本与 `VERSION` 一致后才发布四工件（`.github/workflows/ci.yml` publish job）。
