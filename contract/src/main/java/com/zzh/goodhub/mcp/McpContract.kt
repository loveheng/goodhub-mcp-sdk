package com.zzh.goodhub.mcp

/**
 * GoodHub 本地插件接入契约常量（AIDL 线级契约 + 运行时发现约定）。
 *
 * ## 冻结声明（改动即破坏兼容）
 *
 * 1. **AIDL 包名 `com.zzh.goodhub.mcp` 永久冻结**：
 *    AIDL 生成的 `DESCRIPTOR` = 接口全限定名，随每次 Binder 事务作为 interface token 上线。
 *    改包名 = 宿主与插件的接口 token 不匹配 = 存量插件全部 bind 失败，且**无法静默降级**。
 * 2. **发现约定四常量永久冻结**：
 *    `DISCOVERY_ACTION` / `META_SERVER_ID` / `META_SERVER_TITLE` / `PERMISSION_MCP_SERVER`
 *    硬编码在两端 Manifest 与宿主扫描逻辑里，改名即打断存量插件的自动发现。
 * 3. **契约演进走版本号，不走原地改写**：
 *    任何破坏性变更须升 [CONTRACT_VERSION] 并保持向下兼容（宿主与插件可同时存在新旧版本）。
 *
 * ## 职责边界
 *
 * 本对象只声明「线级常量」，不含任何行为：
 *  - 连接生命周期（`bindService` / `DeathRecipient` / 挂起队列状态机）属 **GoodHub 宿主**；
 *  - 工具路由 / Schema 生成 / 异常隔离属 **插件侧 SDK**（`plugin-core`）。
 */
object McpContract {

    /** 契约版本号（v1 = 初始线级契约）。破坏性变更须升版。 */
    const val CONTRACT_VERSION = "v1"

    /** MCP 协议版本（`initialize` 应答的 `protocolVersion`）。 */
    const val MCP_PROTOCOL_VERSION = "2025-06-18"

    /** AIDL 包名，永久冻结（见上方冻结声明·1）。 */
    const val AIDL_PACKAGE = "com.zzh.goodhub.mcp"

    /** 插件 Service 声明的发现用 Action（宿主按此扫描已安装插件）。 */
    const val DISCOVERY_ACTION = "com.zzh.goodhub.action.MCP_SERVER"

    /** 插件 Service 声明的 meta-data key：服务端点唯一 ID。 */
    const val META_SERVER_ID = "com.zzh.goodhub.SERVER_ID"

    /** 插件 Service 声明的 meta-data key：端点展示标题。 */
    const val META_SERVER_TITLE = "com.zzh.goodhub.SERVER_TITLE"

    /** 同签名生态的 signature 级权限名（宿主 `uses-permission`，插件可作 `android:permission`）。 */
    const val PERMISSION_MCP_SERVER = "com.zzh.goodhub.permission.MCP_SERVER"
}
