package com.zzh.goodhub.ai.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.zzh.goodhub.mcp.IMcpCallback
import com.zzh.goodhub.mcp.IMcpChannel
import com.zzh.goodhub.mcp.McpContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 消费端传输服务（与供工具插件同管道：宿主按发现约定 bind 本服务并注册
 * 回调——零新增传输；消费方本体无工具面，`tools/list` 恒空集）。
 *
 * 职责（§3.5 机制归属）：连接生命周期（宿主 bind / DeathRecipient）、
 * `answerReverse` 多帧喂 [AiRuntimeCore]、请求帧经宿主回调 `onEvent` 发射。
 */
class AiTransportService : Service() {

    // 死亡代理生命周期：每次注册先解绑旧的，避免重复 registerCallback 累积。
    private var linkedBinder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    private fun unlinkDeath() {
        val b = linkedBinder
        val r = deathRecipient
        if (b != null && r != null) runCatching { b.unlinkToDeath(r, 0) }
        linkedBinder = null
        deathRecipient = null
    }

    private val binder = object : IMcpChannel.Stub() {
        override fun call(jsonRpcRequest: String): String = route(jsonRpcRequest)

        override fun registerCallback(cb: IMcpCallback?) {
            unlinkDeath()
            val push: ((String) -> Unit)? = cb?.let { callback ->
                { frame: String ->
                    runCatching { callback.onEvent(frame) } // oneway；断连由死亡代理收敛
                }
            }
            GoodHubAiRuntime.onHostCallback(push)
            if (cb != null) {
                // §3.7 吸水机制 2：宿主在途死亡 → 确定性失败 HOST_TERMINATED。
                val recipient = IBinder.DeathRecipient { GoodHubAiRuntime.core.onHostDied() }
                if (runCatching { cb.asBinder().linkToDeath(recipient, 0) }.isSuccess) {
                    linkedBinder = cb.asBinder()
                    deathRecipient = recipient
                }
            }
        }

        override fun answerReverse(jsonRpcResponse: String) {
            // 多帧回程（契约 api.md §8.2）：ack/progress/终帧全走本通道同 id
            // 多次透传——核心按形状路由。
            GoodHubAiRuntime.core.onAnswerFrame(jsonRpcResponse)
        }
    }

    /** 面向宿主的最小 JSON-RPC 面：initialize / tools/list（空工具集）；其余 -32601。 */
    private fun route(request: String): String {
        val req = runCatching { JSONObject(request) }.getOrNull()
            ?: return errorFrame(JSONObject.NULL, -32600, "请求非合法 JSON")
        val id = req.opt("id") ?: JSONObject.NULL
        val method = req.optString("method", "")
        return when (method) {
            "initialize" -> JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put(
                    "result",
                    JSONObject()
                        .put("protocolVersion", McpContract.MCP_PROTOCOL_VERSION)
                        .put("capabilities", JSONObject().put("tools", JSONObject()))
                        .put(
                            "serverInfo",
                            JSONObject()
                                .put("name", "goodhub-ai-consumer")
                                .put("version", projectVersion())
                        )
                )
                .toString()
            "tools/list" -> JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", JSONObject().put("tools", JSONArray()))
                .toString()
            else -> errorFrame(id, -32601, "AI 消费端不提供工具面: $method")
        }
    }

    /** id 原样回显（禁强转 String——数字 id 会变成字符串，违反 JSON-RPC）。 */
    private fun errorFrame(id: Any, code: Int, message: String): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()

    private fun projectVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
    }.getOrDefault("0")

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        unlinkDeath()
        GoodHubAiRuntime.onHostCallback(null)
        GoodHubAiRuntime.core.onHostDied() // 服务解绑 = 在途全收敛（宿主重 bind 后重发）
        super.onDestroy()
    }
}
