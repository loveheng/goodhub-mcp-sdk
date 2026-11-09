package com.zzh.goodhub.ai.runtime

import android.os.Looper
import com.zzh.goodhub.ai.AiEvent
import com.zzh.goodhub.ai.AiFailure
import com.zzh.goodhub.ai.AiResult
import com.zzh.goodhub.ai.AiCall
import com.zzh.goodhub.ai.AiListener
import com.zzh.goodhub.ai.AiRequest
import com.zzh.goodhub.ai.AiSyncResult
import com.zzh.goodhub.ai.GoodHubAi
import com.zzh.goodhub.ai.GoodHubAiProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * runtime 装配单例（ai-capability-export M-C §3.5）：client 门面的实现载体。
 *
 * **零显式初始化**：client [GoodHubAi.get] 经类名反射触达本 object 的静态
 * 初始化（[GoodHubAiProvider.register] 自注册）——业务层声明依赖即用，无
 * bind()/onServiceConnected 等连接 API 泄漏（consumer-rules.pro 保类名）。
 *
 * 连接生命周期由 [AiTransportService] 承载（宿主按发现约定绑定本服务并注册
 * 回调——消费方与供工具插件同管道，零新增传输）；宿主回调断连/死亡由
 * [AiRuntimeCore.onHostDied] 收敛为确定性失败（断线重连由宿主重新 bind 兜住）。
 */
object GoodHubAiRuntime {

    /** 事件发射作用域（回调档；协程档用调用方作用域——页面退出自动取消）。 */
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 帧发射：宿主注册的 IMcpCallback（oneway onEvent）；未连接 = HOST_OFFLINE fast-fail。 */
    @Volatile
    var pushToHost: ((String) -> Unit)? = null

    val core: AiRuntimeCore = AiRuntimeCore(
        pushFrame = { frame ->
            val push = pushToHost ?: throw HostNotConnectedException()
            push(frame)
        },
        // 未连接判定上移到核心：HOST_OFFLINE 在核心内以结构化失败收口，
        // 禁裸异常穿透门面（Flow 抛异常 / 回调档静默悬挂）。
        isHostConnected = { pushToHost != null },
    )

    init {
        GoodHubAiProvider.register { GoodHubAiImpl(core, callbackScope) }
    }

    /** 宿主回调注册（[AiTransportService.registerCallback] 委托；null = 注销）。 */
    fun onHostCallback(push: ((String) -> Unit)?) {
        pushToHost = push
    }

    class HostNotConnectedException : IllegalStateException("GoodHub 未连接（宿主未 bind 本消费端）")
}

/** client 门面实现（三档同源：Kotlin 档与 Java 档共用同一事件流）。 */
internal class GoodHubAiImpl(
    private val core: AiRuntimeCore,
    private val callbackScope: CoroutineScope,
) : GoodHubAi {

    override fun completeStream(request: AiRequest): Flow<AiEvent> = callbackFlow {
        var settled = false
        val id = core.submitRequest(
            request = request,
            asyncCapable = true, // 异步全量形态：可等冷启动（宿主按 token 裁决）
            onPhase = { phase -> trySend(AiEvent.Phase(phase)) },
            onResult = { text, model ->
                settled = true
                trySend(AiEvent.Result(text, model))
                close()
            },
            onFailure = { failure ->
                settled = true
                trySend(AiEvent.Failure(failure))
                close()
            },
        )
        awaitClose {
            // 取消即第一优先级（§3.7 吸水机制 4）：协程取消/页面退出自动映射
            // ai/cancel——宿主中断生成并摘除在途（幂等）；已终态不白发帧。
            if (!settled) core.cancel(id)
        }
    }

    override suspend fun complete(request: AiRequest): AiResult =
        when (val e = completeStream(request).first { it !is AiEvent.Phase }) {
            is AiEvent.Result -> AiResult(e.text, e.model)
            is AiEvent.Failure -> throw AiFailureException(e.failure)
            else -> error("unreachable")
        }

    override fun complete(request: AiRequest, listener: AiListener): AiCall {
        val job = callbackScope.launch {
            runCatching {
                completeStream(request).collect { listener.onEvent(it) }
            }.onFailure { e ->
                // 任何异常都必须转成终态事件：吞异常 = 回调档永久悬挂。
                val failure = if (e is AiFailureException) e.failure
                else AiFailure(AiFailure.Codes.ENGINE_FAILED, e.message)
                listener.onEvent(AiEvent.Failure(failure))
            }
        }
        return object : AiCall {
            override fun cancel() = job.cancel()
        }
    }

    override fun completeSync(request: AiRequest): AiSyncResult {
        // 主线程防护（§3.4）：主线程调用抛 IllegalStateException 引导走协程档。
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "completeSync 禁主线程调用——请改用 suspend complete() / completeStream()"
        }
        // 同步原子坍缩（§3.7 吸水机制 3）：零等待通道（无 progressToken）——
        // 宿主冷态直接结构化 FastFail（一 RTT），热态短任务直出。
        val latch = CountDownLatch(1)
        var outcome: AiSyncResult? = null
        val budget = 10L // 秒（同步通道专职 WARM_STANDBY 直出；超预算自动熔断）
        val reqId = core.submitRequest(
            request = request,
            asyncCapable = false,
            onPhase = null,
            onResult = { text, model ->
                outcome = AiSyncResult.Success(text, model)
                latch.countDown()
            },
            onFailure = { failure ->
                outcome = AiSyncResult.FastFail(failure)
                latch.countDown()
            },
        )
        if (!latch.await(budget, TimeUnit.SECONDS)) {
            core.cancel(reqId) // 超预算熔断：摘除在途 + ai/cancel（宿主幂等）
            return AiSyncResult.FastFail(
                AiFailure(AiFailure.Codes.ENGINE_FAILED, "同步等待预算耗尽（${budget}s）——请转异步形态")
            )
        }
        return outcome ?: AiSyncResult.FastFail(
            AiFailure(AiFailure.Codes.ENGINE_FAILED, "同步收口缺终态")
        )
    }
}

/** 结构化失败的挂起形态出口（`suspend complete` 失败语义；业务层 catch 后 UI 映射）。 */
class AiFailureException(val failure: AiFailure) :
    Exception(failure.message ?: "AI 调用失败: ${failure.code}")
