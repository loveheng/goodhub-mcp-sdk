package com.zzh.goodhub.ai

import kotlinx.coroutines.flow.Flow

/**
 * 异步事件流事件（§3.6 三态）：[Phase] 等待阶梯 / [Result] 终产出 /
 * [Failure] 结构化失败。`completeStream` 冷流 collect 到终态事件即完成。
 */
sealed interface AiEvent {
    /** 阶段帧（`params._meta.phase` 翻译；同一阶段可能多次发射——宿主跃迁即发）。 */
    data class Phase(val phase: AiPhase) : AiEvent

    /** 终产出（聚合文本；流式 v1 不提供——§3.1 UTF-8 半字符红线）。 */
    data class Result(val text: String, val model: String? = null) : AiEvent

    /** 结构化失败（十二码；业务层只做 UI 映射）。 */
    data class Failure(val failure: AiFailure) : AiEvent
}

/** 终产出（`suspend complete` 的成功形态）。 */
data class AiResult(val text: String, val model: String? = null)

/** 同步便捷壳结果（§3.6 四入口对账：completeSync 专用）。 */
sealed interface AiSyncResult {
    /** 热态短任务直出。 */
    data class Success(val text: String, val model: String? = null) : AiSyncResult

    /** 零等待预算下的结构化 FastFail（升阶分流：[AiFailure.shouldEscalateToAsync]）。 */
    data class FastFail(val failure: AiFailure) : AiSyncResult
}

/** 在途调用句柄（回调档取消入口；`cancel()` → `ai/cancel`，幂等）。 */
interface AiCall {
    fun cancel()
}

/** Java 兼容回调档（与 Kotlin 档同源——同一事件流的两档形态）。 */
interface AiListener {
    fun onEvent(event: AiEvent)
}

/**
 * 进度/阶段全量档 + 一键挂起档的入口（业务层看到的全部——破层红线：
 * 本接口签名零 contract / transport 类型）。
 *
 * - **默认用异步形态**（UI 场景天然要进度与取消）；
 * - `completeSync` 仅轻量一行拿结果场景：模型冷态直接 FastFail，失败即
 *   引导改走异步（升阶分流）；
 * - 协程取消自动映射 `ai/cancel`：页面退出 → scope 取消 → 宿主中断生成
 *   （业务层零取消代码）。
 */
interface GoodHubAi {
    /** 一键挂起调用：内部自动处理连接 / `_async` / 关联回调；冷态可等（挂起直到终态）。 */
    suspend fun complete(request: AiRequest): AiResult

    /** 需要进度/阶段的场景：冷流，collect 到 [AiEvent.Result] / [AiEvent.Failure] 终态。 */
    fun completeStream(request: AiRequest): Flow<AiEvent>

    /** Java 兼容回调档（与 Kotlin 档同源）。 */
    fun complete(request: AiRequest, listener: AiListener): AiCall

    /**
     * 同步便捷壳（Java 兜底档）：仅引擎热态（WARM_STANDBY）短任务直出；
     * 其余状态按结构化 FastFail 立即返回（绝不阻塞等待）。
     * **主线程调用抛 [IllegalStateException]**（引导走协程档）。
     */
    fun completeSync(request: AiRequest): AiSyncResult

    companion object {
        /** 宿主包名（跨仓常量族，与 Binder 发现同源；goodshare 接缝同款）。 */
        const val hostPackageName = "com.zzh.goodhub"

        /** 发现 Action（值冻结 = 契约仓 McpContract.DISCOVERY_ACTION；runtime 测试钉死）。 */
        const val discoveryAction = "com.zzh.goodhub.action.MCP_SERVER"

        /**
         * 静态探测（零 IPC、主线程安全）：PackageManager 查询宿主声明的
         * MCP 服务——宿主未装 = false。UI 条件渲染入口（无 GoodHub 隐藏
         * AI 按钮），禁以「调一次挨打碰壁」探测。
         *
         * 注意：只回答「宿主在不在」，不掺运行时态（模型装载态归运行时通道、
         * 授权态归 `UNAUTHORIZED` 码——静态探测掺运行时态 = 状态外泄）。
         */
        @JvmStatic
        fun isAvailable(context: android.content.Context): Boolean {
            val pm = context.packageManager
            val intent = android.content.Intent(discoveryAction)
                .setPackage(hostPackageName)
            val resolved = pm.queryIntentServices(intent, 0)
            return resolved.isNotEmpty()
        }

        /** 入口（零显式初始化：runtime 工件在类加载时自注册实现）。 */
        @JvmStatic
        fun get(): GoodHubAi = GoodHubAiProvider.get()
    }
}

/**
 * 实现注册槽（client ↔ runtime 解耦的唯一缝）：runtime 工件静态初始化时
 * [register]；业务层 [GoodHubAi.get] 消费。client 不反向依赖 runtime
 *（依赖方向红线 = runtime → client 单向），缺 runtime 依赖即显式报错。
 */
object GoodHubAiProvider {
    @Volatile
    private var factory: (() -> GoodHubAi)? = null

    /** runtime 工件注册实现（业务层禁调）。 */
    @JvmStatic
    fun register(factory: () -> GoodHubAi) {
        GoodHubAiProvider.factory = factory
    }

    /** @throws IllegalStateException 业务层未带 runtime 工件（依赖缺位，显式暴露）。 */
    @JvmStatic
    fun get(): GoodHubAi {
        factory?.invoke()?.let { return it }
        // 零显式初始化：类名反射触达 runtime 静态初始化（自注册 factory）；
        // R8 场景由 runtime 的 consumer-rules.pro 保类名。
        runCatching {
            Class.forName("com.zzh.goodhub.ai.runtime.GoodHubAiRuntime")
        }
        return factory?.invoke()
            ?: throw IllegalStateException(
                "GoodHubAi 缺实现：请添加 :goodhub-ai-runtime 依赖（goodhub-ai-runtime.aar）"
            )
    }
}
