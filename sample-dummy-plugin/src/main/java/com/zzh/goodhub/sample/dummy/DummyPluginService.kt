package com.zzh.goodhub.sample.dummy

import com.zzh.goodhub.ai.AiRequest
import com.zzh.goodhub.ai.AiSyncResult
import com.zzh.goodhub.ai.GoodHubAi
import com.zzh.goodhub.mcp.plugin.GoodHubPluginService
import com.zzh.goodhub.mcp.plugin.McpTool
import com.zzh.goodhub.mcp.plugin.McpToolContext
import com.zzh.goodhub.mcp.plugin.Param
import com.zzh.goodhub.mcp.plugin.ToolVisibility
import org.json.JSONArray
import org.json.JSONObject

/**
 * 验证靶子（T-sdk-1 交付物 #4）：提供 echo / add 两个轻量同步工具，外加 T-sdk-2 的
 * 异步进度工具 slowEcho 与反向请求演示工具 reverseDemo，作为 T-trans-3 事件回传链路
 * 的真机联调靶子。安装即被 GoodHub（T-trans-4 后）经 action 扫描自动发现。
 */
class DummyPluginService : GoodHubPluginService(
    serverId = "dummy",
    serverTitle = "Dummy 测试插件",
    // Server 级自描述：声明本靶子可被宿主动态嵌入（供将来动态工具注入联调）
    mcpConsumable = true
) {
    // visibility = SHARED：声明本工具可被宿主收进共享面（纯标记，SDK 不据此剔除或拦截）
    @McpTool(description = "原样回显输入文本", readOnlyHint = true, visibility = ToolVisibility.SHARED)
    fun echo(@Param("text", description = "要回显的文本") text: String): String = text

    // 未标 visibility → 默认 PRIVATE（default-deny），宿主侧按此对照验证私有工具的分桶行为
    @McpTool(description = "两个整数相加", readOnlyHint = true)
    fun add(
        @Param("a", description = "加数") a: Int,
        @Param("b", description = "被加数") b: Int
    ): Int = a + b

    /**
     * 异步长任务演示（T-sdk-2 / T-trans-3）：call 立即回 `_async`，worker 线程分段推进度，
     * 终帧经 McpToolContext.complete 异步投递——宿主 BinderTransport 保持 completer 开启
     * 直到同 id 终帧（callWithProgress 收侧即达 progress + result）。
     */
    @McpTool(
        description = "异步长任务：分段推送进度后回显（T-trans-3 e2e 靶子）",
        async = true
    )
    fun slowEcho(ctx: McpToolContext, @Param("text", description = "要回显的文本") text: String) {
        val steps = 4
        for (i in 1..steps) {
            Thread.sleep(400)
            ctx.progress(progress = i.toDouble() / steps, message = "处理中 $i/$steps")
        }
        ctx.complete("async: $text")
    }

    /**
     * 反向请求演示（T-sdk-2 / §5）：向宿主发 sampling/createMessage，宿主经 answerReverse
     * 回程推理结果；宿主未接线（onServerRequest 未设）则回 -32601，本工具降级为失败文案，
     * 绝不悬挂（future 异常完成即触发 whenComplete 的 err 分支）。
     */
    @McpTool(
        description = "反向请求演示：请宿主做 sampling 推理后回传（T-trans-3 闭环靶子）",
        async = true
    )
    fun reverseDemo(ctx: McpToolContext, @Param("prompt", description = "交给宿主推理的提示") prompt: String) {
        val future = ctx.reverseRequest(
            "sampling/createMessage",
            JSONObject().apply {
                put("maxTokens", 256)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONObject().apply { put("text", prompt) })
                    })
                })
            }
        )
        future.whenComplete { result, err ->
            if (err != null) {
                ctx.complete("reverse failed: ${err.message}")
            } else {
                val text = result?.optJSONObject("content")?.optString("text", "")
                ctx.complete("sampling result: $text")
            }
        }
    }

    /**
     * AI 消费面靶子（ai-capability-export M-D）：经 `:goodhub-ai-client` 的
     * `GoodHubAi.completeSync` 消费宿主 `ai/complete` 原语——全链闭环：
     * 宿主调本工具 → 本工具反向发 `ai/complete` → 宿主统一受理链（路由/配额/
     * 隔离/审计）→ 多帧回程（本工具零 progressToken = 零等待通道）→ 结果回宿主。
     * 失败按结构化码透出（FastFail 快照话术原样，供 e2e 断言十二码）。
     */
    @McpTool(
        description = "AI 靶子：请宿主完成一次单发推理（ai/complete 消费链 e2e）",
        async = true,
        readOnlyHint = true
    )
    fun aiEcho(
        ctx: McpToolContext,
        @Param("instruction", description = "任务指令（做什么、输出什么形状）") instruction: String,
        @Param("input", description = "待处理原文") input: String
    ) {
        val outcome = try {
            GoodHubAi.get().completeSync(AiRequest(instruction, input))
        } catch (e: Exception) {
            ctx.complete("aiEcho error: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        when (outcome) {
            is AiSyncResult.Success ->
                ctx.complete("aiEcho(${(outcome as AiSyncResult.Success).model}): ${(outcome as AiSyncResult.Success).text}")
            is AiSyncResult.FastFail -> {
                val f = (outcome as AiSyncResult.FastFail).failure
                ctx.complete("aiEcho fast-fail[${f.code}]${f.retryAfterMs?.let { " retry_after_ms=$it" } ?: ""}: ${f.message ?: ""}")
            }
        }
    }
}
