package com.zzh.goodhub.mcp.plugin

import com.zzh.goodhub.mcp.IMcpCallback
import com.zzh.goodhub.mcp.IMcpChannel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-a 联合态解析面单测（CI 跑 `:plugin-core:test`）。
 *
 * 覆盖三条契约红线（SSOT = goodshare `ai-app-split.md` §3.11.2）：
 *  1. 同步 failFast 语义零变化——非联合态文本一律 Immediate；
 *  2. `execution_mode: "deferred"` 联合态回执 → Deferred（task_id/status/expiry_ms）；
 *  3. deferred 回执缺 task_id = 宿主契约违约 → 显式抛错，禁静默降级；
 *  4. onDeviceComplete / onDeviceTaskStatus 反向帧形态与 answerReverse 回程闭环。
 */
class OnDeviceOutcomeTest {

    // ---------- parse：联合态判定 ----------

    @Test
    fun parse_immediatePlainText() {
        val out = OnDeviceOutcome.parse("今天天气不错，适合散步。")
        assertTrue(out is OnDeviceOutcome.Immediate)
        assertEquals("今天天气不错，适合散步。", (out as OnDeviceOutcome.Immediate).text)
    }

    @Test
    fun parse_immediateWhenExecutionModeMissing() {
        // 含 JSON 形态但无 execution_mode 键（如模型恰好产出 JSON 文本）→ 不得误判 deferred
        val out = OnDeviceOutcome.parse("""{"foo":"bar"}""")
        assertTrue(out is OnDeviceOutcome.Immediate)
    }

    @Test
    fun parse_deferredReceipt() {
        val out = OnDeviceOutcome.parse(
            """{"execution_mode":"deferred","task_id":"t-1","status":"queued","expiry_ms":86400000}""")
        assertTrue(out is OnDeviceOutcome.Deferred)
        out as OnDeviceOutcome.Deferred
        assertEquals("t-1", out.taskId)
        assertEquals("queued", out.status)
        assertEquals(86400000L, out.expiryMs)
    }

    @Test
    fun parse_deferredMissingTaskIdThrows() {
        try {
            OnDeviceOutcome.parse("""{"execution_mode":"deferred","status":"queued"}""")
            throw AssertionError("缺 task_id 必须抛错")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("task_id"))
        }
    }

    // ---------- McpToolContext 反向闭环 ----------

    /** 捕获插件经 onEvent 发出的反向请求帧，并模拟宿主经 answerReverse 回程。 */
    private class HostStub(private val service: GoodHubPluginService) : IMcpCallback.Stub() {
        var lastFrame: String? = null

        override fun onEvent(jsonRpcMessage: String?) {
            lastFrame = jsonRpcMessage
            // 回程帧形如 {"jsonrpc":"2.0","id":<rid>,"result":{...}}
            val req = JSONObject(jsonRpcMessage!!)
            val reply = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", req.getLong("id"))
                put("result", req.optString("method").let { method ->
                    if (method == OnDeviceOutcome.REVERSE_METHOD) JSONObject().apply {
                        put("role", "assistant")
                        put("model", "goodhub-on-device")
                        put("content", JSONObject().apply {
                            put("type", "text")
                            put("text", DEFERRED_TEXT)
                        })
                    } else JSONObject().apply {
                        put("role", "assistant")
                        put("content", JSONObject().apply {
                            put("type", "text")
                            put("text", STATUS_TEXT)
                        })
                    }
                })
            }
            // 基类 binder 私有——经公开 onBind 取回 Stub 再回程。
            answerReverse(reply.toString())
        }

        private fun answerReverse(json: String) {
            binderProxy().answerReverse(json)
        }

        private fun binderProxy(): IMcpChannel = service.onBind(null) as IMcpChannel
    }

    class EmptyPlugin : GoodHubPluginService("fake", "Fake") {
        @McpTool(description = "占位")
        fun ping(): String = "pong"
    }

    @Test
    fun onDeviceComplete_roundTrip_deferred() {
        val service = EmptyPlugin()
        val host = HostStub(service)
        (service.onBind(null) as IMcpChannel).registerCallback(host)
        val ctx = McpToolContext(service, 1, null)

        val out = ctx.onDeviceComplete("总结", "原文", allowDeferred = true).get()
        val frame = JSONObject(host.lastFrame!!)
        assertEquals(OnDeviceOutcome.REVERSE_METHOD, frame.getString("method"))
        assertEquals("总结", frame.getJSONObject("params").getString("instruction"))
        assertTrue(frame.getJSONObject("params").getBoolean("allow_deferred"))
        assertTrue(out is OnDeviceOutcome.Deferred)
        assertEquals("t-1", (out as OnDeviceOutcome.Deferred).taskId)
    }

    @Test
    fun onDeviceTaskStatus_roundTrip_projection() {
        val service = EmptyPlugin()
        val host = HostStub(service)
        (service.onBind(null) as IMcpChannel).registerCallback(host)
        val ctx = McpToolContext(service, 1, null)

        val projection = ctx.onDeviceTaskStatus("t-1").get()
        val frame = JSONObject(host.lastFrame!!)
        assertEquals(OnDeviceOutcome.STATUS_REVERSE_METHOD, frame.getString("method"))
        assertEquals("t-1", frame.getJSONObject("params").getString("task_id"))
        assertEquals("done", projection.getString("status"))
    }

    companion object {
        private const val DEFERRED_TEXT =
            """{"execution_mode":"deferred","task_id":"t-1","status":"queued","expiry_ms":86400000}"""
        private const val STATUS_TEXT =
            """{"task_id":"t-1","status":"done","result":"摘要文本"}"""
    }
}
