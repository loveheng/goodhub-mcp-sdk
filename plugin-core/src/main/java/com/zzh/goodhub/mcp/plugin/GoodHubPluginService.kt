package com.zzh.goodhub.mcp.plugin

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.zzh.goodhub.mcp.IMcpChannel
import com.zzh.goodhub.mcp.IMcpCallback
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 插件侧 SDK 服务基类（server 端）：自动实现 [IMcpChannel.Stub]。
 * 子类用 [McpTool] 标注业务方法即可，零手写 AIDL / ServiceConnection。
 *
 * 角色边界（§11.3 五处纠正·1）：仅实现 Stub；不 bindService、不持 DeathRecipient
 * （client 端连接生命周期 / 挂起队列状态机完全归 GoodHub 宿主 BinderTransport）。
 * 红线（§1.2.7 / §9.3）：仅上报 SERVER_ID（构造入参），不做命名主权仲裁、不做包名拼接。
 *
 * T-sdk-2：补齐事件发射端——[pushEvent] 投 progress / 异步终帧 / 反向请求；
 * [reverseRequest] + [IMcpChannel.answerReverse] 实现反向请求闭环（§5）。
 */
abstract class GoodHubPluginService(
    val serverId: String,
    val serverTitle: String
) : Service() {

    private val dispatcher = ToolDispatcher(this)

    // 宿主回注的回调；volatile：异步终帧/反向应答可能从 worker 线程读取。
    @Volatile
    private var callback: IMcpCallback? = null

    // 反向请求在途表：key = 反向请求 id，value = 待 answerReverse 回程的 future（§5）。
    private val pendingReverse = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
    private val reverseCounter = AtomicLong(1)

    // ---------------------------------------------------------------------
    // 子类接管口子（Dart / Flutter / 其它非 Kotlin 业务层桥接用）
    //
    // 背景：本 SDK 的默认路由是「运行时反射扫描本类 @McpTool 方法」，适合业务逻辑写在
    // Kotlin 的插件。但业务逻辑在 Dart（Flutter 插件 App）的宿主无法用反射派发器——
    // 工具会全量丢失。这类宿主应重写 [handleJsonRpc] 接管全部请求路由，其余
    // AIDL 语义（回调注册 / 反向请求回程 / 事件推送）由基类统一承载。
    // ---------------------------------------------------------------------

    /**
     * JSON-RPC 请求路由入口。默认走运行时反射派发器（`@McpTool` 工具）。
     *
     * 重写即接管全部请求路由（`initialize` / `tools/list` / `tools/call` 全由子类负责）。
     * 重写者须自行保证：返回合法 JSON-RPC 帧字符串、业务异常转 `-32603`（§1.2.7 异常隔离）、
     * 同步调用在 Binder 事务预算内返回（§1.2.3，长任务改 `_async`）。
     */
    protected open fun handleJsonRpc(jsonRpcRequest: String): String = dispatcher.handle(jsonRpcRequest)

    /**
     * 宿主注册 / 注销回调时触发（`cb` 非 null = 宿主已连入并完成 `registerCallback`）。
     * 子类可据此把「宿主在线」状态转给自己的业务层（如通知 Dart）。
     */
    protected open fun onHostCallback(cb: IMcpCallback?) {}

    /**
     * 宿主对插件反向请求的应答（server→client 反向请求的回程）。
     * 默认由基类解析并唤醒在途 future（Kotlin `@McpTool` 工具走这条）；
     * 重写可把应答转给自己的业务层（如 Dart 侧的反向请求在途表）。
     */
    protected open fun onHostAnswerReverse(jsonRpcResponse: String) {}

    /**
     * 子类向宿主推事件帧（`notifications/progress` / `_async` 终帧 / 反向请求帧）。
     * 与 [McpToolContext] 内部用的是同一条通道（`IMcpCallback.onEvent`，oneway）。
     *
     * 注意：投递异常在本方法内被静默吞掉（回调断连由宿主 DeathRecipient 感知）。
     * 需要「推送失败」可观测（打 [DEGRADE] 日志、防终帧悬挂无从察觉）时，
     * 请用 [tryEmitEvent] 取返回值。
     */
    protected fun emitEvent(jsonRpcMessage: String) = pushEvent(jsonRpcMessage)

    /**
     * 推事件帧并回报结果（供子类做可观测性兜底）。
     *
     * @return true = 已投递成功；false = 宿主回调未注册，或投递抛异常（宿主回调断连）。
     *
     * 何时用：子类自己持一份宿主回调引用（为了做「回调是否在线」判定）时，
     * 直接调 `onEvent` 会被 SDK 吞掉异常，丢失「推帧失败」信号；
     * 用本方法可拿到成败，用于打 [DEGRADE] 日志（终帧推不出去 = 宿主侧悬挂，
     * 只能靠 goodhub 的 idle 超时兜底，属必须可观测的降级路径）。
     */
    protected fun tryEmitEvent(jsonRpcMessage: String): Boolean {
        val cb = callback ?: return false
        return try {
            cb.onEvent(jsonRpcMessage)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private val binder = object : IMcpChannel.Stub() {
        override fun call(jsonRpcRequest: String): String {
            // 离主线程由宿主侧保证（§1.2.3）；此处同步处理并在派发层隔离异常。
            return handleJsonRpc(jsonRpcRequest)
        }

        override fun registerCallback(cb: IMcpCallback?) {
            callback = cb
            onHostCallback(cb)
        }

        override fun answerReverse(jsonRpcResponse: String) {
            // 宿主回答插件经 onEvent 发出的反向请求（§1.2.2 / §5）：按 id 关联并唤醒 future。
            onHostAnswerReverse(jsonRpcResponse)
            try {
                val r = JSONObject(jsonRpcResponse)
                val id = r.optLong("id", -1L)
                val future = pendingReverse.remove(id) ?: return
                if (r.has("error")) {
                    val err = r.optJSONObject("error")
                    future.completeExceptionally(
                        IllegalStateException("reverse request $id failed: ${err?.optString("message")}")
                    )
                } else {
                    future.complete(r.optJSONObject("result") ?: JSONObject())
                }
            } catch (_: Throwable) {
                // 解析失败：静默（宿主侧会超时兜底，不悬挂）。
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** 供 [McpToolContext] / 子类经 onEvent 推事件（progress / 异步终帧 / 反向请求）。 */
    internal fun pushEvent(jsonRpcMessage: String) {
        try {
            callback?.onEvent(jsonRpcMessage)
        } catch (_: Throwable) {
            // 回调断连由宿主 DeathRecipient 感知，此处静默。
        }
    }

    /**
     * 发起反向请求（server→client），返回 [CompletableFuture] 待 [answerReverse] 回程（§5）。
     * 经 [IMcpCallback.onEvent] 推 request 帧；宿主处理后经 answerReverse 应答。
     * 宿主未注册回调 → 立即异常完成（绝不悬挂）。
     */
    internal fun reverseRequest(
        method: String,
        params: JSONObject?
    ): CompletableFuture<JSONObject> {
        val cb = callback
        val future = CompletableFuture<JSONObject>()
        if (cb == null) {
            future.completeExceptionally(IllegalStateException("no host callback registered for reverse request"))
            return future
        }
        val rid = reverseCounter.getAndIncrement()
        pendingReverse[rid] = future
        val frame = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            put("id", rid)
            if (params != null) put("params", params)
        }
        try {
            cb.onEvent(frame.toString())
        } catch (e: Throwable) {
            pendingReverse.remove(rid)
            future.completeExceptionally(e)
        }
        return future
    }
}
