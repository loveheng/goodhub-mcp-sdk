# Changelog

本文件记录 `goodhub-mcp-sdk` 的对外可见变更。契约破坏性变更必须在此标注并同步升 `CONTRACT_VERSION`。

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
