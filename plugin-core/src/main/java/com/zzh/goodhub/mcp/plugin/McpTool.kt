package com.zzh.goodhub.mcp.plugin

import java.util.Locale

/**
 * 标记插件业务函数为 MCP 工具。SDK 运行时反射扫描此注解生成 tools/list Schema
 * 并把 tools/call 路由到对应方法。
 *
 * @param description 工具描述（进入 inputSchema.description）
 * @param timeoutMs   同步调用预算红线（§1.2.3）；T-sdk-1 仅作为元数据记录，
 *                   真正超时降级由 T-sdk-2 的 _async 投递落地
 * @param async       true 时该工具在 call 内立即返回 {"_async": true} 轻量分支骨架
 *                   （§1.2.2 长任务异步约定；真实结果投递留 T-sdk-2）
 * @param readOnlyHint    MCP 标准 annotation：声明该工具无副作用（仅 true 时下发，未声明即不下发）
 * @param destructiveHint MCP 标准 annotation：声明该工具可能造成破坏性变更（同上）
 * @param visibility      工具可见性范围标记（纯声明，详见 [ToolVisibility]）
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class McpTool(
    val description: String = "",
    val timeoutMs: Long = 5000,
    val async: Boolean = false,
    val readOnlyHint: Boolean = false,
    val destructiveHint: Boolean = false,
    val visibility: ToolVisibility = ToolVisibility.PRIVATE
)

/**
 * 工具可见性范围标记 —— **纯声明，非访问控制**。
 *
 * 只回答「该工具属于哪个曝光分桶」。SDK 把它原样写进 `tools/list` 的
 * `_meta.goodhub.visibility`，此外**不做任何剔除、过滤或调用拦截**：
 *  - `tools/list` 始终反映 Server 当前声明的能力全集（含 [PRIVATE] 工具），
 *    以保证工具发现的确定性——不制造「明明写了方法却查不到」的调试死角；
 *  - 工具的可调用性不受本标记影响（`tools/call` 一律正常路由）；
 *  - 按标记如何聚合、是否收进共享目录、要不要按用户身份裁剪视图，
 *    全属宿主（client 端）的解析职责。
 *
 * 默认 [PRIVATE]（default-deny）：未显式标注的工具一律私有，漏标即最严。
 */
enum class ToolVisibility {
    /** 仅本机 / 本地消费，宿主不得收进共享目录。 */
    PRIVATE,

    /** 允许宿主聚合进共享面。 */
    SHARED;

    /** 下发到 `_meta.goodhub.visibility` 的线级取值（小写短名）。 */
    val wireName: String get() = name.lowercase(Locale.ROOT)
}
