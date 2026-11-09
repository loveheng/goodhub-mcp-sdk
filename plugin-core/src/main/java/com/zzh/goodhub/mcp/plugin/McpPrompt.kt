package com.zzh.goodhub.mcp.plugin

/**
 * 声明式 Prompt（MCP 标准 prompts 能力，ai-capability-export 通用化轮 0.6.0）：
 * 标注在插件的 Kotlin 方法上，SDK 自动把方法暴露为 MCP prompt（`prompts/list`
 * 契约 + `prompts/get` 模板渲染）——宿主 CoW 拉取后注册为 appScoped skill
 * （**连接后才注入系统提示**，未挂载来源不被介绍——与 builtin 来源专属 skill
 * 的退役理由对偶）。
 *
 * **语义**：prompt = 可复用提示模板（含 `{{arg}}` 占位符字面替换，单次遍历
 * 不递归）。宿主按 `prompts/get` 传入 arguments，SDK 产出标准
 * `{messages:[{role:'user', content:{type:'text', text}}]}` 形状。
 *
 * 方法签名约定：
 * ```kotlin
 * @McpPrompt(name = "data-guide", description = "拾贝数据面：检索/读取工具口径与 Vault 边界")
 * fun dataGuide(@PromptArg("scope") scope: String): String = "……模板文本（{{scope}} 已被字面替换）……"
 * ```
 * - `McpToolContext` 参数可注入（不进 arguments 契约）；其余参数须标 `@PromptArg`
 * - 返回 `String` = 单条 user 消息模板文本
 *
 * **与 @McpTool 分工**：工具 = 副作用动词（宿主调用执行）；prompt = 模板
 * 文本（宿主拉取注入模型）。capabilities 声明 = 有 @McpPrompt 才声明
 * `prompts`（无 → 宿主 hasPrompts=false 自动降级，Tier 2 零破坏）。
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class McpPrompt(
    /** prompt 名（prompts/list 契约键；缺省 = 方法名）。 */
    val name: String = "",

    /** 模板用途描述（prompts/list 下发，宿主 CoW 展示）。 */
    val description: String = "",
)

/** @McpPrompt 方法的模板参数声明（prompts/list 的 `arguments` 契约项）。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class PromptArg(
    /** 参数名（`{{name}}` 占位符对应）。 */
    val value: String = "",

    /** 参数用途描述。 */
    val description: String = "",
)
