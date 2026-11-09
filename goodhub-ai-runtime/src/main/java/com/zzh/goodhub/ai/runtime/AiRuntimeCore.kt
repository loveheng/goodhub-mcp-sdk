package com.zzh.goodhub.ai.runtime

import com.zzh.goodhub.ai.AiFailure
import com.zzh.goodhub.ai.AiPhase
import com.zzh.goodhub.ai.AiRequest
import com.zzh.goodhub.mcp.McpContract
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 反向请求帧路由核心（ai-capability-export M-C §3.5 净新增机械件；纯 Kotlin
 * 零 Android 依赖——JVM 单测直测）。
 *
 * 职责：`ai/complete` 请求帧构造与发射、`answerReverse` 多帧路由（_async ack
 * → progress 通知帧 → 同 id 终帧）、`ai/cancel` 发射、宿主死亡（DeathRecipient
 * 触发）确定性失败 `HOST_TERMINATED`。**机制全在 SDK**（§3.5 职责归属总账），
 * 业务层零 IPC 感知。
 *
 * 线级惯例（立法 = 契约仓 api.md §8.2）：宿主对同一请求 id 多次调用
 * `answerReverse`——无 id 通知帧（progress）与带 id 终帧由本核心按形状路由；
 * `_async: true` ack 不断开等待（保持 pending 直到终帧）。
 *
 * 发射失败纪律（0.6.0 修）：[isHostConnected] 为 false 或 [pushFrame] 抛异常时，
 * 一律**回滚 pending 并以 `HOST_OFFLINE` 结构化失败收口**——禁把裸异常抛给门面
 * （会让 Flow 抛异常、回调档静默悬挂），保证四档 API 都收到终态事件。
 */
class AiRuntimeCore(
    private val pushFrame: (String) -> Unit,
    private val isHostConnected: () -> Boolean = { true },
) {

    private class Pending(
        val asyncCapable: Boolean,
        val onPhase: ((AiPhase) -> Unit)?,
        val onResult: (text: String, model: String?) -> Unit,
        val onFailure: (AiFailure) -> Unit,
    )

    private val pending = ConcurrentHashMap<String, Pending>()
    private val seq = AtomicLong(0)

    /** 在途数（观测面/测试用）。 */
    val pendingCount: Int get() = pending.size

    /**
     * 发射 ai/complete 请求（asyncCapable → `_meta.progressToken` = 请求 id，
     * 宿主按「带 token = 可等冷启动」裁决阶段帧与冷装载）。
     * 返回请求 id（取消用）。
     */
    fun submitRequest(
        request: AiRequest,
        asyncCapable: Boolean,
        onPhase: ((AiPhase) -> Unit)?,
        onResult: (text: String, model: String?) -> Unit,
        onFailure: (AiFailure) -> Unit,
    ): String {
        val id = "ai_req_${seq.incrementAndGet()}"
        val params = JSONObject()
            .put("instruction", request.instruction)
            .put("input", request.input)
        request.config?.let { c ->
            val cfg = JSONObject()
            c.maxTokens?.let { cfg.put("maxTokens", it) }
            c.temperature?.let { cfg.put("temperature", it) }
            c.responseFormat?.let { rf ->
                cfg.put("responseFormat", JSONObject().put("type", rf.type).put("value", rf.value))
            }
            if (cfg.length() > 0) params.put("config", cfg)
        }
        if (asyncCapable) {
            params.put("_meta", JSONObject().put("progressToken", id))
        }
        val frame = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", McpContract.METHOD_AI_COMPLETE)
            .put("params", params)
            .toString()

        pending[id] = Pending(asyncCapable, onPhase, onResult, onFailure)
        val sent = try {
            if (isHostConnected()) {
                pushFrame(frame)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false // Binder 断连 / oneway 抛异常：与「未连接」同等收敛
        }
        if (!sent) {
            pending.remove(id) // 回滚在途表，禁残留
            runCatching {
                onFailure(
                    AiFailure(
                        AiFailure.Codes.HOST_OFFLINE,
                        "GoodHub 未连接（宿主未 bind 本消费端）"
                    )
                )
            }
        }
        return id
    }

    /**
     * 发射 ai/cancel（幂等：宿主对已完成/不存在任务静默确认）。已终态不白发帧；
     * 在途 = 摘除并以 [AiFailure] 本地收口（不依赖宿主终帧——cancel ack 即收口锚）。
     * **发射帧失败也必须本地收口**，否则调用方永久悬挂。
     */
    fun cancel(requestId: String) {
        val p = pending.remove(requestId) ?: return
        val frame = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", "ai_cancel_${seq.incrementAndGet()}")
            .put("method", McpContract.METHOD_AI_CANCEL)
            .put("params", JSONObject().put("cancel_id", requestId))
            .toString()
        if (isHostConnected()) runCatching { pushFrame(frame) }
        runCatching { p.onFailure(AiFailure(AiFailure.Codes.USER_REJECTED, "调用方已取消")) }
    }

    /**
     * answerReverse 多帧路由（宿主 → 消费方的唯一回程）：
     * - 无 id + `notifications/progress` → phase 翻译（按 progressToken 关联）；
     * - 带 id + `_async: true` → 保持等待（不断开）；
     * - 带 id + result/error → 终态（error.data.status / retry_after_ms 结构化）。
     * 形状不识的帧静默忽略（宿主侧同款纪律——未知帧不炸消费方）。
     */
    fun onAnswerFrame(frame: String) {
        val json = runCatching { JSONObject(frame) }.getOrNull() ?: return
        if (json.optString("method", "") == "notifications/progress") {
            val params = json.optJSONObject("params") ?: return
            val token = params.optString("progressToken", "")
            val phaseName = params.optJSONObject("_meta")
                ?.optString(McpContract.AI_PROGRESS_META_PHASE, "") ?: ""
            val p = pending[token] ?: return
            val phase = phaseOf(phaseName) ?: return
            runCatching { p.onPhase?.invoke(phase) }
            return
        }
        val id = json.optString("id", "")
        if (id.isEmpty()) return
        val p = pending[id] ?: return
        if (json.has("error")) {
            settle(id)
            val err = json.optJSONObject("error") ?: JSONObject()
            val data = err.optJSONObject("data")
            val status = data?.optString("status", "")?.takeIf { it.isNotEmpty() }
            val failure = AiFailure(
                code = status ?: AiFailure.Codes.ENGINE_FAILED,
                message = err.optString("message", "").takeIf { it.isNotEmpty() },
                retryAfterMs = data?.optLong(McpContract.AI_KEY_RETRY_AFTER_MS, -1L)
                    ?.takeIf { it > 0 },
            )
            runCatching { p.onFailure(failure) }
            return
        }
        val result = json.optJSONObject("result") ?: return
        if (result.optBoolean("_async", false)) return // ack：保持 pending 等终帧
        settle(id)
        val text = result.optJSONObject("content")?.optString("text", "") ?: ""
        val model = result.optString("model", "").takeIf { it.isNotEmpty() }
        runCatching { p.onResult(text, model) }
    }

    /** 宿主死亡（DeathRecipient 触发）：全部在途确定性失败 HOST_TERMINATED（§3.7 吸水机制 2）。 */
    fun onHostDied() {
        for (id in pending.keys.toList()) {
            val p = pending.remove(id) ?: continue
            runCatching {
                p.onFailure(
                    AiFailure(
                        AiFailure.Codes.HOST_TERMINATED,
                        "端侧资源不足，稍后重试（宿主进程在途被回收）"
                    )
                )
            }
        }
    }

    private fun settle(id: String) {
        pending.remove(id)
    }

    /** 线级四值单源 = [McpContract.AI_PHASE_*]；未知值静默忽略（向前兼容）。 */
    private fun phaseOf(name: String): AiPhase? = when (name) {
        McpContract.AI_PHASE_STARTING_HOST -> AiPhase.STARTING_HOST
        McpContract.AI_PHASE_LOADING_MODEL -> AiPhase.LOADING_MODEL
        McpContract.AI_PHASE_QUEUING -> AiPhase.QUEUING
        McpContract.AI_PHASE_GENERATING -> AiPhase.GENERATING
        else -> null
    }
}
