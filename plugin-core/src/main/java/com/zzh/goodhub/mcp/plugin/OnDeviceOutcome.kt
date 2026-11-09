package com.zzh.goodhub.mcp.plugin

import com.zzh.goodhub.mcp.McpContract
import org.json.JSONObject

/**
 * `ai/complete` 反向请求的联合态回执（收编后形态：0.5.1 起推理入口收口标准
 * 原语 `ai/complete`——契约 SSOT = goodhub-mcp-sdk api.md §8/§8.7；私有 method
 * `host/onDeviceComplete` 2026-11-09 退役。联合态解析纪律不变）。
 * `docs/design/ai-app-split.md` §3.11.2 / goodhub 仓 `docs/design/on-device-residency.md` 第七节）。
 *
 * 联合态装进现有 JSON 信封（`:contract` AIDL 不动）：
 *  - 同步 failFast（Fast-Path 成功 / 未 opt-in 冷态拒绝）→ [Immediate]，`content.text` 为纯文本；
 *  - 冷态入池（调用方 opt-in `allow_deferred = true` 且宿主池已装配且身份可绑）→ [Deferred]，
 *    `content.text` 为 JSON：`{"execution_mode":"deferred","task_id":"...","status":"queued","expiry_ms":86400000}`。
 *
 * 解析规则：`content.text` 能解析为含 `execution_mode` 键的 JSON 对象才算联合态回执；
 * 其余一律按 [Immediate] 纯文本承载（同步产出本身可能是任意文本，不做 JSON 嗅探误判）。
 * `deferred` 回执缺 `task_id` 属宿主契约违约——解析抛 [IllegalArgumentException] 显式暴露，禁静默降级。
 *
 * **Pull = 交付真理源**（Android 12+ cgroup freezer 吞 oneway，Push 尽力而为）：
 * 拿到 [Deferred] 后须以 [McpToolContext.onDeviceTaskStatus] 以 `task_id` 拉取结果，
 * 界面先呈现「后台分析中」占位；Push（`IMcpCallback.onEvent`）仅作温热触达加速，不可依赖。
 */
sealed class OnDeviceOutcome {

    /** 同步结果：端侧直出的纯文本（或未 opt-in 时的 failFast 前置会以异常帧抵达，不走本类）。 */
    class Immediate(val text: String) : OnDeviceOutcome()

    /**
     * 异步受理回执：任务已入宿主任务池，后续经 `host/onDeviceTaskStatus` 以 [taskId] 拉取。
     * [expiryMs] = 距过期的毫秒数（缺省 24h；宿主未携带时为 -1，不可作过期判据，以 status 为准）。
     */
    class Deferred(
        val taskId: String,
        val status: String,
        val expiryMs: Long
    ) : OnDeviceOutcome()

    companion object {
        /** 宿主私有反向 method（Tier 1 契约，宿主侧常量同源：`HostOnDeviceToolExecutor.reverseMethodName`）。 */
        const val REVERSE_METHOD = "ai/complete"

        /**
         * 宿主任务状态反向 method。**注意**：宿主当前仅在导出工具面（`on_device_task_status`，
         * 网关 Allowlist）实现了该操作，Binder 反向通路的接线属 goodhub 侧后续工作包——
         * 调用本 method 若收到宿主 sampling 错误帧，说明宿主版本尚未接线，请回退 Push 事件面。
         */
        const val STATUS_REVERSE_METHOD = "host/onDeviceTaskStatus"

        /** 从反向请求回程帧的 `content.text` 解析联合态回执（同步/异步统一入口）。 */
        fun parse(text: String): OnDeviceOutcome {
            val json = try {
                JSONObject(text)
            } catch (_: Throwable) {
                return Immediate(text)
            }
            // 键名一律取契约单源（McpContract.AI_KEY_*），禁在解析面重抄字面量。
            if (json.optString(McpContract.AI_KEY_EXECUTION_MODE) != "deferred") {
                return Immediate(text)
            }
            val taskId = json.optString(McpContract.AI_KEY_TASK_ID)
            if (taskId.isEmpty()) {
                throw IllegalArgumentException(
                    "on_device_complete deferred 回执缺 task_id（宿主契约违约）：$text")
            }
            return Deferred(
                taskId = taskId,
                status = json.optString("status", "queued"),
                expiryMs = json.optLong(McpContract.AI_KEY_EXPIRY_MS, -1L)
            )
        }
    }
}
