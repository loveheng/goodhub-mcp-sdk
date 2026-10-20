package com.zzh.goodhub.mcp.plugin

import com.zzh.goodhub.mcp.IMcpCallback
import com.zzh.goodhub.mcp.IMcpChannel
import com.zzh.goodhub.mcp.McpContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 派发器单测（CI 跑 `:plugin-core:test`）。
 *
 * 覆盖宿主最依赖的四条 SDK 红线：
 *  1. `tools/list` 自动生成合规 Schema；
 *  2. `tools/call` 正确路由 + 入参 coerce；
 *  3. 业务异常隔离——业务方法抛出的异常必须转 -32603，绝不穿透 Binder 致宿主崩溃；
 *  4. 异步长任务立即回 `_async` 占位（不阻塞 Binder 事务）；
 *  5. 可见性标记纯声明：写进 `_meta.goodhub.visibility`，不剔除条目、不影响路由。
 */
class ToolDispatcherTest {

    /** 测试替身：模拟第三方插件的写法（继承基类 + 注解）。 */
    class FakePlugin : GoodHubPluginService("fake", "Fake 插件") {

        @McpTool(description = "回显文本")
        fun echo(@Param("text", description = "待回显文本") text: String): String = "echo:$text"

        @McpTool(description = "两数相加")
        fun add(@Param("a") a: Int, @Param("b") b: Int): Int = a + b

        @McpTool(description = "必炸的业务异常")
        fun boom(): String = throw IllegalStateException("boom")

        @McpTool(description = "慢任务", async = true)
        fun slow(ctx: McpToolContext) {
            ctx.progress(0.5, message = "一半了")
            ctx.complete("slow done")
        }

        @McpTool(description = "可共享的只读查询", readOnlyHint = true, visibility = ToolVisibility.SHARED)
        fun sharedRead(): String = "pub:read"

        @McpTool(description = "可共享的破坏性操作", destructiveHint = true, visibility = ToolVisibility.SHARED)
        fun sharedWipe(): String = "pub:wiped"
    }

    private fun dispatcher(): ToolDispatcher = ToolDispatcher(FakePlugin())

    private fun handle(d: ToolDispatcher, json: String): JSONObject = JSONObject(d.handle(json))

    @Test
    fun `initialize 上报协议版本与服务身份`() {
        val res = handle(dispatcher(), """{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
        val result = res.getJSONObject("result")
        assertEquals(McpContract.MCP_PROTOCOL_VERSION, result.getString("protocolVersion"))
        assertEquals("fake", result.getJSONObject("serverInfo").getString("name"))
        assertEquals("Fake 插件", result.getJSONObject("serverInfo").getString("title"))
    }

    @Test
    fun `tools list 生成合规 schema`() {
        val res = handle(dispatcher(), """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        val tools = res.getJSONObject("result").getJSONArray("tools")
        val byName = (0 until tools.length()).associate { i ->
            val t = tools.getJSONObject(i)
            t.getString("name") to t
        }
        assertEquals(setOf("echo", "add", "boom", "slow", "sharedRead", "sharedWipe"), byName.keys)

        val echoSchema = byName.getValue("echo").getJSONObject("inputSchema")
        assertEquals("object", echoSchema.getString("type"))
        val props = echoSchema.getJSONObject("properties")
        assertEquals("string", props.getJSONObject("text").getString("type"))
        assertEquals("待回显文本", props.getJSONObject("text").getString("description"))
        assertEquals("回显文本", byName.getValue("echo").getString("description"))

        val addSchema = byName.getValue("add").getJSONObject("inputSchema")
        assertEquals("integer", addSchema.getJSONObject("properties").getJSONObject("a").getString("type"))
        assertEquals(2, addSchema.getJSONArray("required").length())

        // McpToolContext 首参不进 Schema（不入 properties、不计入 required）
        val slowSchema = byName.getValue("slow").getJSONObject("inputSchema")
        assertEquals(0, slowSchema.getJSONObject("properties").length())
    }

    @Test
    fun `visibility 默认 PRIVATE 并按原样下发到 _meta goodhub`() {
        val byName = toolsByName()
        // 未标 visibility 的工具默认 private（default-deny），且照常出现在 tools/list 里
        assertEquals("private", visibilityOf(byName.getValue("echo")))
        assertEquals("private", visibilityOf(byName.getValue("add")))
        // 显式标 SHARED 的工具下发 shared
        assertEquals("shared", visibilityOf(byName.getValue("sharedRead")))
        assertEquals("shared", visibilityOf(byName.getValue("sharedWipe")))
    }

    @Test
    fun `visibility 是纯声明 不剔除 list 条目也不拦截 call`() {
        val byName = toolsByName()
        assertTrue("私有工具仍须出现在 tools/list（能力全集）", byName.containsKey("echo"))

        val d = dispatcher()
        val res = handle(d, """{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"echo","arguments":{"text":"x"}}}""")
        val text = res.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
        assertEquals("echo:x", text)
    }

    @Test
    fun `annotations 仅下发显式声明的 hint`() {
        val byName = toolsByName()
        assertTrue(byName.getValue("sharedRead").getJSONObject("annotations").getBoolean("readOnlyHint"))
        assertTrue(byName.getValue("sharedWipe").getJSONObject("annotations").getBoolean("destructiveHint"))
        assertFalse("未声明任何 hint 时不带 annotations 节点", byName.getValue("echo").has("annotations"))
    }

    /** 拉取 tools/list 并按 name 索引，供逐条断言（同一份视图书写屡次断言）。 */
    private fun toolsByName(): Map<String, JSONObject> {
        val res = handle(dispatcher(), """{"jsonrpc":"2.0","id":10,"method":"tools/list"}""")
        val arr = res.getJSONObject("result").getJSONArray("tools")
        return (0 until arr.length()).associate { i ->
            val t = arr.getJSONObject(i)
            t.getString("name") to t
        }
    }

    private fun visibilityOf(tool: JSONObject): String =
        tool.getJSONObject("_meta").getJSONObject("goodhub").getString("visibility")

    @Test
    fun `tools call 路由并 coerce 入参`() {
        val d = dispatcher()
        val echo = handle(d, """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}""")
        val echoText = echo.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
        assertEquals("echo:hi", echoText)

        val add = handle(d, """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"add","arguments":{"a":2,"b":40}}}""")
        val sumText = add.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
        assertEquals("42", sumText)
    }

    @Test
    fun `业务异常隔离为 -32603 错误帧`() {
        val res = handle(dispatcher(), """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"boom","arguments":{}}}""")
        assertTrue("异常必须被拦截为错误帧，不得返回 result", !res.has("result"))
        val err = res.getJSONObject("error")
        assertEquals(McpErrors.INTERNAL_ERROR, err.getInt("code"))
        assertEquals(-32603, err.getInt("code"))
        assertEquals("boom", err.getString("message"))
    }

    @Test
    fun `未知方法与未知工具为 -32601`() {
        val m = handle(dispatcher(), """{"jsonrpc":"2.0","id":6,"method":"nope"}""")
        assertEquals(McpErrors.METHOD_NOT_FOUND, m.getJSONObject("error").getInt("code"))
        val t = handle(dispatcher(), """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"ghost","arguments":{}}}""")
        assertEquals(McpErrors.METHOD_NOT_FOUND, t.getJSONObject("error").getInt("code"))
    }

    @Test
    fun `缺必填入参为 -32602`() {
        val res = handle(dispatcher(), """{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"add","arguments":{"a":1}}}""")
        assertEquals(McpErrors.INVALID_PARAMS, res.getJSONObject("error").getInt("code"))
    }

    @Test
    fun `异步工具立即回 _async 占位`() {
        val res = handle(dispatcher(), """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"slow","arguments":{}}}""")
        assertTrue("异步工具须立即回 _async 占位，不阻塞 Binder 事务", res.getJSONObject("result").getBoolean("_async"))
    }

    /** 桥接式插件替身：业务逻辑不在 Kotlin（如 Dart 桥接），故重写 [GoodHubPluginService.handleJsonRpc] 接管路由。 */
    class BridgePlugin : GoodHubPluginService("bridge", "Bridge 插件") {
        var lastRequest: String? = null
            private set

        override fun handleJsonRpc(jsonRpcRequest: String): String {
            lastRequest = jsonRpcRequest
            return """{"jsonrpc":"2.0","id":1,"result":{"bridged":true}}"""
        }
    }

    @Test
    fun `子类重写 handleJsonRpc 即接管全部路由`() {
        val p = BridgePlugin()
        val stub = p.onBind(null) as IMcpChannel.Stub
        val res = JSONObject(stub.call("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""))
        assertTrue("重写 handleJsonRpc 后应由子类路由（业务层在 Dart 时的路径）", res.getJSONObject("result").getBoolean("bridged"))
        assertTrue("子类应收到原始请求", p.lastRequest!!.contains("tools/list"))
    }

    @Test
    fun `未重写时基类默认路由仍走反射派发器`() {
        val p = FakePlugin()
        val stub = p.onBind(null) as IMcpChannel.Stub
        val res = JSONObject(stub.call("""{"jsonrpc":"2.0","id":1,"method":"initialize"}"""))
        assertEquals(McpContract.MCP_PROTOCOL_VERSION, res.getJSONObject("result").getString("protocolVersion"))
    }

    /** 可观测性替身：子类自己持宿主回调引用时用 tryEmitEvent 取成败。 */
    class EmitPlugin : GoodHubPluginService("emit", "Emit 插件") {
        fun tryPush(json: String): Boolean = tryEmitEvent(json)
    }

    @Test
    fun `tryEmitEvent 回报推送成败`() {
        val p = EmitPlugin()
        val stub = p.onBind(null) as IMcpChannel.Stub
        // 宿主回调未注册 → 推送失败（false），供子类打 [DEGRADE] 日志（终帧推不出=宿主侧悬挂）
        assertFalse("宿主回调未注册时必须回报失败，不得静默", p.tryPush("""{"n":1}"""))

        val cb = object : IMcpCallback.Stub() {
            override fun onEvent(jsonRpcMessage: String) = Unit
        }
        stub.registerCallback(cb)
        assertTrue("回调已注册且投递成功时必须回报成功", p.tryPush("""{"n":2}"""))
    }
}
