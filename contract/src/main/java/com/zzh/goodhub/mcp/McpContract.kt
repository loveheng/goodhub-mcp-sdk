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

    // ===== AI 能力原语（v0.4.0 增量：JSON-RPC method 层扩展，AIDL 接口签名零变更）=====
    //
    // 契约正文（请求/响应 JSON 形状、_async 握手、progress 帧、十二码表语义）= 本仓 api.md §8；
    // 立法 SSOT = goodshare 仓 docs/design/ai-app-split.md §3.13。两端（宿主 Dart 侧 / SDK Kotlin 侧）
    // 消费本组常量，禁缝外重抄字面量。

    /** 对外推理原语 method：complete(instruction, input, config) -> 聚合文本。任务语义由调用方编排，宿主不内置任务模板。 */
    const val METHOD_AI_COMPLETE = "ai/complete"

    /** 异步取消原语 method：params.cancel_id = 被取消的 ai/complete 请求 id；对已完成/不存在任务幂等静默确认。 */
    const val METHOD_AI_CANCEL = "ai/cancel"

    /** 结构化失败的统一 JSON-RPC 数字错误码（error.code；机器可读细分看 error.data.status）。 */
    const val AI_FAILURE_RPC_CODE = -32000

    /** 建议退避字段 key（error.data.retry_after_ms，毫秒）：仅提示非承诺，SDK 不自动重试，退避决策归调用方业务层。 */
    const val AI_KEY_RETRY_AFTER_MS = "retry_after_ms"

    /** progress 帧阶段语义 key：notifications/progress 的 params._meta.phase（与 SDK AiPhase 枚举同值单源，替代裸 message 字符串）。 */
    const val AI_PROGRESS_META_PHASE = "phase"

    /** progress 帧 v2 流式预留字段位（params._meta.delta）：v1 禁发射文本（端侧分帧存在 UTF-8 半字符边界风险）。 */
    const val AI_PROGRESS_META_DELTA = "delta"

    /** 联合态回执键（v0.5.0 增，deferred 收编；result.content.text 内 JSON 对象字段）：
     *  冷态 + allow_deferred=true → 入池回执 {"execution_mode":"deferred","task_id":…}，
     *  其余 text 一律纯文本产出（禁 JSON 嗅探误判——含 execution_mode 键且值为 deferred 才判联合态）。 */
    const val AI_KEY_ALLOW_DEFERRED = "allow_deferred"
    const val AI_KEY_EXPIRY_MS = "expiry_ms"
    const val AI_KEY_EXECUTION_MODE = "execution_mode"
    const val AI_KEY_TASK_ID = "task_id"

    // —— 生成阶段四值（params._meta.phase 线级取值；顺序即推荐展示次序，非进度承诺）——
    const val AI_PHASE_STARTING_HOST = "STARTING_HOST" // 宿主进程拉起/通道建立
    const val AI_PHASE_LOADING_MODEL = "LOADING_MODEL" // 端侧模型装载（1.5~3GB，分钟级窗口）
    const val AI_PHASE_QUEUING = "QUEUING"             // 受理排队（并发槽/配额等待）
    const val AI_PHASE_GENERATING = "GENERATING"       // 推理生成中

    // —— 结构化失败码表（十二码，error.data.status 线级取值；一个码表两处消费：同步 FastFail 快照 + 异步 Failure 帧，禁两套枚举）——
    const val AI_FAIL_MODEL_COLD = "MODEL_COLD"                       // 引擎冷态未就绪（同步零等待 fast-fail 主码；异步形态可等冷启动）
    const val AI_FAIL_RESOURCE_BUSY = "RESOURCE_BUSY"                 // 受理槽占满（data.retry_after_ms 附建议退避）
    const val AI_FAIL_HOST_OFFLINE = "HOST_OFFLINE"                   // 发起时宿主不可达（未安装/未拉起）
    const val AI_FAIL_HOST_TERMINATED = "HOST_TERMINATED"             // _async 在途期间宿主进程被系统击杀（LMK）；禁与 HOST_OFFLINE 混用映射
    const val AI_FAIL_BUDGET_EXCEEDED = "BUDGET_EXCEEDED"             // 输入超安全预算（>128KB 线或超模型上下文；诚实降级，禁静默截断产废文）
    const val AI_FAIL_UNAUTHORIZED = "UNAUTHORIZED"                   // 调用方未获 AI 借用授权（SDK 引导件按此码出授权跳转）
    const val AI_FAIL_RATE_LIMIT = "RATE_LIMIT"                       // 频率超限（data.retry_after_ms 附建议退避）
    const val AI_FAIL_CALLER_NOT_IN_FOREGROUND = "CALLER_NOT_IN_FOREGROUND" // 已信任第三方后台调用被拒（lifecyclePolicy 分档）
    const val AI_FAIL_USER_REJECTED = "USER_REJECTED"                 // 用户在确认卡链明确拒绝本次调用
    const val AI_FAIL_ENGINE_FAILED = "ENGINE_FAILED"                 // 推理引擎执行失败（端侧 Runner / 云端 API，宿主不自动兜底另一侧）
    const val AI_FAIL_DEVICE_THROTTLED = "DEVICE_THROTTLED"           // 硬件自保熔断：温控 ≥SEVERE 或低电量未充电
    const val AI_FAIL_PREEMPTED_BY_HOST = "PREEMPTED_BY_HOST"         // 被宿主自用会话协作式打断（data.retry_after_ms 附建议退避；无 KV Cache 换出承诺）
}
