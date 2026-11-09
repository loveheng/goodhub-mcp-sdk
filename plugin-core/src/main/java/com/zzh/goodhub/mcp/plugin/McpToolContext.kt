package com.zzh.goodhub.mcp.plugin

import com.zzh.goodhub.mcp.McpContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture

/**
 * 异步工具执行上下文（T-sdk-2）：仅注入给 `@McpTool(async = true)` 工具的首参。
 *
 * 通过宿主回注的 [IMcpCallback.onEvent]（oneway）投递全部事件，闭环见
 * 上游设计稿（GoodHub 仓 docs/design/thirdconnect.md）§1.2.2（_async 异步终帧）/ §5（进度 + 反向请求）：
 *  - [progress]：推送 `notifications/progress`（带 request 中 `_meta.progressToken`，
 *    供宿主 BinderTransport 按 token 归属到在途 callWithProgress）；
 *  - [complete] / [completeRaw] / [fail]：推送同 `id` 异步终帧（success / error），
 *    唤醒宿主 `_async` completer（宿主对 `_async` 应答保持 completer 开启直到同 id 终帧）；
 *  - [tryComplete] / [tryCompleteRaw] / [tryFail]：同上但回报投递成败，供
 *    注解式异步工具保住「终帧推不出去」这一必须可观测的降级信号；
 *  - [reverseRequest]：向宿主发 server→client 反向请求（如 `sampling/createMessage`），
 *    经 `answerReverse` 回程（§5 闭环）。
 *
 * 类型对外可见、构造 [internal]——第三方只能「接收」该上下文，不能自行构造。
 */
class McpToolContext internal constructor(
    private val service: GoodHubPluginService,
    private val id: Any?,
    private val progressToken: Any?
) {
    /** 推送进度通知（MCP `notifications/progress`）。[progress] ∈ [0,1]，[total]/[message] 可选。 */
    fun progress(progress: Double, total: Double? = null, message: String? = null) {
        val params = JSONObject().apply {
            put("progressToken", progressToken ?: JSONObject.NULL)
            put("progress", progress)
            if (total != null) put("total", total)
            if (!message.isNullOrEmpty()) put("message", message)
        }
        service.pushEvent(
            JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "notifications/progress")
                put("params", params)
            }.toString()
        )
    }

    /** 异步终帧（success）：文本结果包成标准 `content` 块。 */
    fun complete(text: String) {
        service.pushEvent(textResultFrame(text).toString())
    }

    /** 异步终帧（success）：任意结构化 `result` 对象。 */
    fun completeRaw(result: JSONObject) {
        service.pushEvent(successFrame(result).toString())
    }

    /**
     * 与 [complete] 同形，但回报终帧投递成败。
     *
     * @return true = 已投递；false = 宿主回调未注册或投递抛异常。
     *
     * **终帧推不出去 = 宿主侧悬挂**（只能靠宿主 idle 超时兜底）。需要保住这个
     * 降级信号（打 `DEGRADE` 日志 / 落本地待补拉 / 自行重试）时用本方法，
     * 不要静默丢结果。`GoodHubPluginService.tryEmitEvent` 只服务子类接管路径，
     * 本组方法补齐注解式 `@McpTool(async = true)` 路径。
     */
    fun tryComplete(text: String): Boolean =
        service.tryPushEvent(textResultFrame(text).toString())

    /** [completeRaw] 的可观测版本，语义同 [tryComplete]。 */
    fun tryCompleteRaw(result: JSONObject): Boolean =
        service.tryPushEvent(successFrame(result).toString())

    /** 异步终帧（error）。 */
    fun fail(code: Int, message: String) {
        service.pushEvent(errorFrame(code, message).toString())
    }

    /** [fail] 的可观测版本，语义同 [tryComplete]。 */
    fun tryFail(code: Int, message: String): Boolean =
        service.tryPushEvent(errorFrame(code, message).toString())

    private fun textResultFrame(text: String): JSONObject = successFrame(
        JSONObject().apply {
            put(
                "content",
                JSONArray().apply {
                    put(JSONObject().apply { put("type", "text"); put("text", text) })
                }
            )
        }
    )

    private fun successFrame(result: JSONObject): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("result", result)
        }

    private fun errorFrame(code: Int, message: String): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("error", JSONObject().apply { put("code", code); put("message", message) })
        }

    /**
     * 反向请求（server→client，§5）：向宿主发 request 帧，返回 [CompletableFuture]
     * 待 `answerReverse` 回程。例：`sampling/createMessage`。
     * 宿主未注册回调 → 立即异常完成（绝不悬挂）。
     */
    fun reverseRequest(method: String, params: JSONObject? = null): CompletableFuture<JSONObject> =
        service.reverseRequest(method, params)

    /**
     * 借宿主端侧算力执行推理（0.5.1 起经标准原语 `ai/complete`——收编迁移，
     * 私有 method `host/onDeviceComplete` 退役；**宿主须 ≥ 收编版**，旧宿主回
     * -32601）。回程为联合态，统一经
     * [OnDeviceOutcome.parse] 解析：
     *  - [OnDeviceOutcome.Immediate]：同步产出文本；
     *  - [OnDeviceOutcome.Deferred]：冷态入池回执——`allowDeferred = true` 且宿主任务池
     *    已装配且调用方身份可绑时返回（`status = "queued"`）。调用方应呈现「后台分析中」
     *    占位，随后以 [onDeviceTaskStatus] 按 Pull 取结果（交付真理源，Push 不可依赖）。
     *
     * 失败（配额超限 / 冷态未 opt-in / 调用方身份不可辨 / 宿主不兜底云端）以
     * [CompletableFuture] 异常完成（`McpRpcException` 语义 error 帧，message 原文透传）。
     * [expiryMs] 可选：任务保质期毫秒数（缺省随宿主 24h）。
     */
    fun onDeviceComplete(
        instruction: String,
        input: String,
        allowDeferred: Boolean = false,
        expiryMs: Long? = null
    ): CompletableFuture<OnDeviceOutcome> {
        val params = JSONObject().apply {
            put("instruction", instruction)
            put("input", input)
            put(McpContract.AI_KEY_ALLOW_DEFERRED, allowDeferred)
            if (expiryMs != null) put(McpContract.AI_KEY_EXPIRY_MS, expiryMs)
        }
        return service.reverseRequest(OnDeviceOutcome.REVERSE_METHOD, params)
            .thenApply { result ->
                OnDeviceOutcome.parse(
                    result.optJSONObject("content")?.optString("text").orEmpty()
                )
            }
    }

    /**
     * 以 `task_id` 拉取入池任务的状态/结果（W6-a Pull 接缝，私有 method
     * `host/onDeviceTaskStatus`）。**Pull 即交付**：宿主 `pendingDelivery` 态查询后
     * 置 `delivered` 并回结果；`failedExhausted` / `expired` 不可恢复。
     *
     * 返回宿主任务投影 [JSONObject]（`status` ∈ queued / running / done（含 result）/
     * expired / FAILED_EXHAUSTED / not_found）。**注意**：宿主 Binder 反向通路对该
     * method 的接线属 goodhub 后续工作包，未接线版本会异常完成——详见
     * [OnDeviceOutcome.STATUS_REVERSE_METHOD] 注。
     */
    fun onDeviceTaskStatus(taskId: String): CompletableFuture<JSONObject> {
        val params = JSONObject().apply { put(McpContract.AI_KEY_TASK_ID, taskId) }
        return service.reverseRequest(OnDeviceOutcome.STATUS_REVERSE_METHOD, params)
            .thenApply { result ->
                result.optJSONObject("content")?.optString("text")?.let { JSONObject(it) }
                    ?: result
            }
    }
}
