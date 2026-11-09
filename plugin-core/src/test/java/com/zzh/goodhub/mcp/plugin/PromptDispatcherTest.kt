package com.zzh.goodhub.mcp.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 声明式 Prompt 分派器验收钉（0.6.0 prompts 能力）：
 * capabilities 按有无声明及线级形状 / prompts/list 契约 / prompts/get 字面替换 /
 * 未知 prompt -32602 / 缺参数 -32602 / 无声明回落 -32601 /
 * 上下文非首位注入 / 模板单次扫描不递归 / 异常帧回显 id。
 */
class PromptDispatcherTest {

    private class ServiceWithPrompts : GoodHubPluginService(
        serverId = "p1",
        serverTitle = "带 prompt 的插件",
        mcpConsumable = false,
    ) {
        @McpPrompt(name = "data-guide", description = "数据面工具口径")
        fun dataGuide(@PromptArg("scope", description = "适用范围") scope: String): String =
            "数据面口径（{{scope}} 已替换）：list_items 检索、get_item 读全文"

        @McpPrompt(description = "无参模板")
        fun plain(): String = "plain-template"
    }

    private class ServiceWithoutPrompts : GoodHubPluginService(
        serverId = "p2",
        serverTitle = "纯工具插件",
    ) {
        @McpTool(description = "echo")
        fun echo(text: String): String = text
    }

    /** 进阶靶子：集中覆盖 ctx 非首位 / 双参数模板 / 运行期异常。 */
    private class ServiceWithAdvancedPrompts : GoodHubPluginService(
        serverId = "p4",
        serverTitle = "进阶 prompt 插件",
    ) {
        @McpPrompt(name = "two-args")
        @Suppress("UNUSED_PARAMETER")
        fun two(
            @PromptArg("a") a: String,
            @PromptArg("b") b: String,
        ): String = "{{a}}|{{b}}"

        @McpPrompt(name = "ctx-second")
        @Suppress("UNUSED_PARAMETER")
        fun ctxSecond(@PromptArg("scope") scope: String, ctx: McpToolContext): String =
            "ctx=${ctx.javaClass.simpleName}|{{scope}}"

        @McpPrompt(name = "boom")
        fun boom(): String = throw IllegalStateException("模板渲染炸了")
    }

    private val withSvc = ServiceWithPrompts()
    private val withoutSvc = ServiceWithoutPrompts()

    @Test
    fun `capabilities：有声明才声明 prompts`() {
        val with = JSONObject(ToolDispatcher(withSvc).handle(rpc("initialize")))
        assertTrue(
            "有 @McpPrompt → 声明 prompts 能力",
            with.getJSONObject("result").getJSONObject("capabilities").has("prompts"),
        )
        val without = JSONObject(ToolDispatcher(withoutSvc).handle(rpc("initialize")))
        assertFalse(
            "无 @McpPrompt → 不声明（宿主 hasPrompts=false 自动降级）",
            without.getJSONObject("result").getJSONObject("capabilities").has("prompts"),
        )
    }

    @Test
    fun `capabilities-prompts 为单层内容对象（禁双层嵌套）`() {
        val caps = JSONObject(ToolDispatcher(withSvc).handle(rpc("initialize")))
            .getJSONObject("result").getJSONObject("capabilities")
        val prompts = caps.getJSONObject("prompts")
        assertFalse("禁止 capabilities.prompts.prompts 双层嵌套", prompts.has("prompts"))
        assertEquals("prompts 应为 capability 内容对象", 0, prompts.length())
    }

    @Test
    fun `prompts-list：契约下发（name + description + arguments）`() {
        val out = JSONObject(ToolDispatcher(withSvc).handle(rpc("prompts/list")))
        val list = out.getJSONObject("result").getJSONArray("prompts")
        assertEquals(2, list.length())
        val guide = list.getJSONObject(0)
        assertEquals("data-guide", guide.getString("name"))
        assertEquals("数据面工具口径", guide.getString("description"))
        assertEquals("scope", guide.getJSONArray("arguments").getJSONObject(0).getString("name"))
    }

    @Test
    fun `prompts-get：占位符字面替换 → 标准 messages 形状`() {
        val out = JSONObject(
            ToolDispatcher(withSvc).handle(rpc("prompts/get", """{"name":"data-guide","arguments":{"scope":"全体条目"}}""")),
        )
        val msg = out.getJSONObject("result").getJSONArray("messages").getJSONObject(0)
        assertEquals("user", msg.getString("role"))
        val text = msg.getJSONObject("content").getString("text")
        assertTrue("占位符须被替换: $text", text.contains("全体条目"))
        assertFalse("占位符残留: $text", text.contains("{{scope}}"))
    }

    @Test
    fun `prompts-get：未知 prompt 与缺参数 → -32602`() {
        val d = ToolDispatcher(withSvc)
        val unknown = JSONObject(d.handle(rpc("prompts/get", """{"name":"ghost"}""")))
        assertEquals(-32602, unknown.getJSONObject("error").getInt("code"))
        val missing = JSONObject(
            d.handle(rpc("prompts/get", """{"name":"data-guide","arguments":{}}""")),
        )
        assertEquals(-32602, missing.getJSONObject("error").getInt("code"))
    }

    @Test
    fun `无声明回落：prompts 分派 → -32601（能力未声明）`() {
        val out = JSONObject(ToolDispatcher(withoutSvc).handle(rpc("prompts/list")))
        assertEquals(-32601, out.getJSONObject("error").getInt("code"))
    }

    @Test
    fun `缺 PromptArg 注解与非法返回类型 = 装配期显式失败（禁静默）`() {
        // 急扫描（构造即校验）：坏方法声明须在 try 内——GoodHubPluginService
        // 构造即构 dispatcher（装配期 fail-fast，启动即崩优于运行期每帧报错）。
        try {
            val bad = object : GoodHubPluginService(serverId = "p3", serverTitle = "bad") {
                @McpPrompt(name = "bad1")
                @Suppress("UNUSED_PARAMETER")
                fun badPrompt(@PromptArg("scope") scope: Int): String = scope.toString()

                @McpPrompt(name = "bad2")
                fun badReturn(): Int = 1
            }
            ToolDispatcher(bad).handle(rpc("prompts/list"))
            fail("PromptArg 非 String / 返回非 String 须装配期显式失败（禁静默）")
        } catch (e: IllegalStateException) {
            // 两条路径其一命中即过
        }
    }

    @Test
    fun `McpToolContext 可声明在非首位（按真实形参位置注入）`() {
        val d = ToolDispatcher(ServiceWithAdvancedPrompts())
        val out = JSONObject(
            d.handle(rpc("prompts/get", """{"name":"ctx-second","arguments":{"scope":"S"}}""")),
        )
        assertFalse("ctx 非首位不得回落为内部错误：${out.optJSONObject("error")}", out.has("error"))
        val text = textOf(out)
        assertTrue("应注入 McpToolContext: $text", text.contains("McpToolContext"))
        assertTrue("占位符应替换: $text", text.endsWith("|S"))
    }

    @Test
    fun `模板单次扫描：参数值里的占位符不被二次展开（防模板注入）`() {
        val d = ToolDispatcher(ServiceWithAdvancedPrompts())
        val out = JSONObject(
            d.handle(rpc("prompts/get", """{"name":"two-args","arguments":{"a":"{{b}}","b":"X"}}""")),
        )
        // 旧逐参数 replace 会得到 "X|X"；单次扫描应保留参数值中的 {{b}} 字面量。
        assertEquals("{{b}}|X", textOf(out))
    }

    @Test
    fun `模板方法抛异常 → -32603 且回显原请求 id（宿主不悬挂）`() {
        val out = JSONObject(
            ToolDispatcher(ServiceWithAdvancedPrompts())
                .handle(rpc("prompts/get", """{"name":"boom"}""")),
        )
        assertEquals(-32603, out.getJSONObject("error").getInt("code"))
        assertEquals("error.id 须与 request.id 一致", 1, out.getInt("id"))
    }

    private fun textOf(out: JSONObject): String =
        out.getJSONObject("result").getJSONArray("messages")
            .getJSONObject(0).getJSONObject("content").getString("text")

    private fun rpc(method: String, params: String? = null): String =
        buildString {
            append("""{"jsonrpc":"2.0","id":1,"method":"$method"""")
            if (params != null) append(""","params":$params""")
            append("}")
        }
}
