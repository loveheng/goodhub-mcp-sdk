package com.zzh.goodhub.mcp.plugin

import org.json.JSONArray
import org.json.JSONObject

/**
 * 声明式 Prompt 分派器（MCP 标准 prompts 能力，0.6.0）：
 * `prompts/list`（契约下发）+ `prompts/get`（`{{arg}}` 字面替换模板渲染）。
 *
 * 与 [ToolDispatcher] 同构分工：tools = 副作用动词（宿主调用执行），
 * prompts = 模板文本（宿主拉取注入模型）。capabilities 声明 = 有
 * @McpPrompt 才声明 `prompts`（[hasPrompts]——无 → 宿主 hasPrompts=false
 * 自动降级，Tier 2 零破坏）。
 *
 * 渲染纪律（MCP prompts 语义 + goodshare M2.5 同款）：`{{arg}}` 占位符
 * **单次扫描字面替换、不递归**——参数值不参与后续匹配，防参数值里的
 * `{{另一个参数}}` 被二次展开（模板注入）；未知占位符保留原文。
 */
internal class PromptDispatcher(private val service: GoodHubPluginService) {

    private data class PromptArgMeta(
        val name: String,
        val description: String,
        val type: String,
        val paramIndex: Int,
    )

    private data class PromptMeta(
        val name: String,
        val description: String,
        val method: java.lang.reflect.Method,
        val args: List<PromptArgMeta>,
        val contextIndex: Int?,
    )

    // 装配期急扫描（fail-fast）：@McpPrompt 契约校验（PromptArg 须 String /
    // 返回须 String）在**构造时**抛出——插件启动即崩优于运行期每帧报错；
    // 既有 ToolDispatcher.scan 为 lazy（无校验抛出）不在此列。
    private val prompts: List<PromptMeta> = scan()

    /** 有声明式 prompt 才声明 capabilities（宿主 hasPrompts 消费）。 */
    val hasPrompts: Boolean = prompts.isNotEmpty()

    private fun scan(): List<PromptMeta> {
        val result = mutableListOf<PromptMeta>()
        for (m in service.javaClass.declaredMethods) {
            val ann = m.getAnnotation(McpPrompt::class.java) ?: continue
            m.isAccessible = true
            val args = mutableListOf<PromptArgMeta>()
            var contextIndex: Int? = null
            m.parameters.forEachIndexed { idx, p ->
                if (p.type == McpToolContext::class.java) {
                    require(contextIndex == null) {
                        "@McpPrompt 方法 ${m.name} 声明了多个 McpToolContext 参数"
                    }
                    contextIndex = idx
                    return@forEachIndexed
                }
                val pa = p.getAnnotation(PromptArg::class.java)
                    ?: throw IllegalStateException(
                        "@McpPrompt 方法 ${m.name} 的参数 ${p.name} 缺 @PromptArg（契约键必须显式）"
                    )
                if (p.type != String::class.java) {
                    throw IllegalStateException(
                        "@PromptArg 参数 ${p.name}（${m.name}）须为 String（prompts arguments 契约恒 string）"
                    )
                }
                args.add(
                    PromptArgMeta(
                        name = pa.value.takeIf { it.isNotEmpty() } ?: (p.name ?: "arg$idx"),
                        description = pa.description,
                        type = "string", // prompts arguments 契约恒 string（MCP 标准）
                        paramIndex = idx,
                    )
                )
            }
            if (m.returnType != String::class.java) {
                throw IllegalStateException(
                    "@McpPrompt 方法 ${m.name} 须返回 String（单条 user 消息模板文本）"
                )
            }
            result.add(
                PromptMeta(
                    ann.name.takeIf { it.isNotEmpty() } ?: m.name,
                    ann.description,
                    m,
                    args,
                    contextIndex,
                )
            )
        }
        // prompts/list 契约确定性：按 name 排序下发（反射扫描顺序不定）。
        result.sortBy { it.name }
        return result
    }

    /**
     * prompts capability 的内容对象（MCP 标准：`capabilities.prompts = {}` 或含
     * `listChanged` 的对象）。由 [ToolDispatcher] 以 `put("prompts", it)` 挂到
     * capabilities 上——本方法**不得**再自带 `prompts` 键，否则线级变成双层嵌套。
     */
    fun capabilities(): JSONObject? = if (hasPrompts) JSONObject() else null

    /** prompts/list + prompts/get 分派；非本面 method 或能力未声明返回 null（调用方回落 -32601）。 */
    fun handle(method: String, id: Any?, req: JSONObject): String? {
        if (!hasPrompts) return null
        return when (method) {
            "prompts/list" -> promptsList(id)
            "prompts/get" -> promptsGet(id, req)
            else -> null
        }
    }

    private fun promptsList(id: Any?): String {
        val list = JSONArray()
        for (p in prompts) {
            val args = JSONArray()
            for (a in p.args) {
                args.put(JSONObject().apply {
                    put("name", a.name)
                    if (a.description.isNotEmpty()) put("description", a.description)
                })
            }
            list.put(JSONObject().apply {
                put("name", p.name)
                put("description", p.description)
                if (args.length() > 0) put("arguments", args)
            })
        }
        return successFrame(id, JSONObject().put("prompts", list))
    }

    private fun promptsGet(id: Any?, req: JSONObject): String {
        val name = req.optJSONObject("params")?.optString("name", "").orEmpty()
        if (name.isEmpty()) return errorFrame(id, McpErrors.INVALID_PARAMS, "prompts/get 缺 name")
        val meta = prompts.firstOrNull { it.name == name }
            ?: return errorFrame(id, McpErrors.INVALID_PARAMS, "unknown prompt: $name")
        val given = req.optJSONObject("params")?.optJSONObject("arguments") ?: JSONObject()

        // 缺 required 参数 → -32602（ai-app-split §3.7 契约：显式传参但 required 缺失拒绝）
        for (a in meta.args) {
            if (given.optString(a.name, "").isEmpty()) {
                return errorFrame(id, McpErrors.INVALID_PARAMS, "缺 arguments.${a.name}（prompt: $name）")
            }
        }

        // 按方法真实形参顺序组装：@PromptArg 用原始 paramIndex 落位，
        // McpToolContext 注入到声明位置（可非首位），禁硬编码 add(0, ctx)。
        val invokerArgs = arrayOfNulls<Any?>(meta.method.parameterTypes.size)
        for (a in meta.args) invokerArgs[a.paramIndex] = given.optString(a.name, "")
        meta.contextIndex?.let { invokerArgs[it] = McpToolContext(service, id, null) }

        // 单次扫描替换：替换值不参与后续匹配（不递归）；未声明占位符保留原文。
        val declared = meta.args.map { it.name }.toSet()
        val text = PLACEHOLDER.replace(invoke(meta, invokerArgs)) { match ->
            val key = match.groupValues[1]
            if (key in declared) given.optString(key, match.value) else match.value
        }
        return successFrame(
            id,
            JSONObject().put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", JSONObject().apply { put("type", "text").put("text", text) })
                    }
                )
            )
        )
    }

    private fun invoke(meta: PromptMeta, args: Array<Any?>): String = try {
        meta.method.invoke(service, *args) as String
    } catch (e: java.lang.reflect.InvocationTargetException) {
        throw (e.cause ?: e)
    }

    private fun successFrame(id: Any?, result: JSONObject): String =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("result", result)
        }.toString()

    private fun errorFrame(id: Any?, code: Int, message: String): String =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("error", JSONObject().apply { put("code", code); put("message", message) })
        }.toString()

    private companion object {
        val PLACEHOLDER = Regex("""\{\{([^{}]+)\}\}""")
    }
}
