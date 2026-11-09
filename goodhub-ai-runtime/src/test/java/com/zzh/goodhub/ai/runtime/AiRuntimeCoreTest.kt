package com.zzh.goodhub.ai.runtime

import com.zzh.goodhub.ai.AiEvent
import com.zzh.goodhub.ai.AiFailure
import com.zzh.goodhub.ai.GoodHubAi
import com.zzh.goodhub.ai.AiPhase
import com.zzh.goodhub.ai.AiRequest
import com.zzh.goodhub.mcp.McpContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反向请求帧路由核心验收钉（ai-capability-export M-C）：
 * 帧形状 / _async 多帧关联 / phase 翻译 / 取消映射 / 宿主死亡确定性失败 /
 * 宿主离线与发射失败的 HOST_OFFLINE 收口。
 */
class AiRuntimeCoreTest {

    private class Harness(connected: Boolean = true) {
        val frames = mutableListOf<String>()
        var failPush = false
        val core = AiRuntimeCore(
            pushFrame = { frame ->
                if (failPush) throw IllegalStateException("binder dead")
                frames.add(frame)
                Unit
            },
            isHostConnected = { connected },
        )

        fun request(
            asyncCapable: Boolean = true,
            config: com.zzh.goodhub.ai.AiConfig? = null,
        ) = core.submitRequest(
            request = AiRequest("提取关键词", "原文", config),
            asyncCapable = asyncCapable,
            onPhase = { /* 测试自接 */ },
            onResult = { _, _ -> },
            onFailure = { },
        )

        fun lastFrame(): JSONObject = JSONObject(frames.last())

        /** 模拟宿主 answerReverse 多帧（原样喂核心）。 */
        fun hostFrame(json: String) = core.onAnswerFrame(json)
    }

    @Test
    fun `请求帧形状：ai-complete method + async 带 progressToken + sync 不带`() {
        val h = Harness()
        val id = h.request(asyncCapable = true)
        val f = h.lastFrame()
        assertEquals("2.0", f.getString("jsonrpc"))
        assertEquals(id, f.getString("id"))
        assertEquals(McpContract.METHOD_AI_COMPLETE, f.getString("method"))
        val params = f.getJSONObject("params")
        assertEquals("提取关键词", params.getString("instruction"))
        assertEquals("原文", params.getString("input"))
        assertEquals(id, params.getJSONObject("_meta").getString("progressToken"))

        h.request(asyncCapable = false)
        val f2 = h.lastFrame()
        assertFalse(f2.getJSONObject("params").has("_meta"))
    }

    @Test
    fun `_async ack 不断开等待 → 同 id 终帧收口（多帧关联）`() {
        val h = Harness()
        val results = mutableListOf<Pair<String, String?>>()
        val id = core_submit(h, onResult = { text, model -> results.add(text to model) })
        // ① 宿主 _async ack。
        h.hostFrame("""{"jsonrpc":"2.0","id":"$id","result":{"_async":true}}""")
        assertEquals("ack 后仍应在途（等终帧）", 1, h.core.pendingCount)
        // ② progress 通知帧（无 id，progressToken 关联）。
        h.hostFrame(
            """{"jsonrpc":"2.0","method":"notifications/progress","params":""" +
                """{"progressToken":"$id","progress":1,"_meta":{"phase":"LOADING_MODEL"}}}"""
        )
        // ③ 同 id 终帧。
        h.hostFrame(
            """{"jsonrpc":"2.0","id":"$id","result":""" +
                """{"content":{"type":"text","text":"产出"},"model":"goodhub-on-device"}}"""
        )
        assertEquals(listOf("产出" to "goodhub-on-device"), results)
        assertEquals(0, h.core.pendingCount)
    }

    @Test
    fun `error 帧 → 结构化失败（status + retry_after_ms 透出）`() {
        val h = Harness()
        val failures = mutableListOf<AiFailure>()
        core_submit(h, onFailure = { failures.add(it) })
        h.hostFrame(
            """{"jsonrpc":"2.0","id":"${h.lastId()}","error":{"code":-32000,"message":"占满",""" +
                """"data":{"status":"RESOURCE_BUSY","retry_after_ms":2000}}}"""
        )
        assertEquals(1, failures.size)
        assertEquals(AiFailure.Codes.RESOURCE_BUSY, failures[0].code)
        assertEquals("占满", failures[0].message)
        assertEquals(2000L, failures[0].retryAfterMs)
    }

    @Test
    fun `progress 帧 phase 翻译四值单源`() {
        val h = Harness()
        val phases = mutableListOf<AiPhase>()
        val id = core_submit(h, onPhase = { phases.add(it) })
        for (name in listOf("STARTING_HOST", "LOADING_MODEL", "QUEUING", "GENERATING")) {
            h.hostFrame(
                """{"jsonrpc":"2.0","method":"notifications/progress","params":""" +
                    """{"progressToken":"$id","progress":0,"_meta":{"phase":"$name"}}}"""
            )
        }
        assertEquals(
            listOf(
                AiPhase.STARTING_HOST, AiPhase.LOADING_MODEL,
                AiPhase.QUEUING, AiPhase.GENERATING
            ),
            phases
        )
        // 未知 phase / 未知 token：静默忽略（不炸消费方）。
        h.hostFrame(
            """{"jsonrpc":"2.0","method":"notifications/progress","params":""" +
                """{"progressToken":"$id","progress":0,"_meta":{"phase":"UNKNOWN"}}}"""
        )
        h.hostFrame(
            """{"jsonrpc":"2.0","method":"notifications/progress","params":""" +
                """{"progressToken":"ai_ghost","progress":0,"_meta":{"phase":"GENERATING"}}}"""
        )
        assertEquals(4, phases.size)
    }

    @Test
    fun `cancel：在途 → ai-cancel 帧 + 本地收口；未知 id 幂等不白发帧`() {
        val h = Harness()
        val failures = mutableListOf<AiFailure>()
        val id = core_submit(h, onFailure = { failures.add(it) })
        val before = h.frames.size
        h.core.cancel("ai_req_ghost")
        assertEquals("未知 id 不白发帧", before, h.frames.size)
        h.core.cancel(id)
        val f = h.lastFrame()
        assertEquals(McpContract.METHOD_AI_CANCEL, f.getString("method"))
        assertEquals(id, f.getJSONObject("params").getString("cancel_id"))
        assertEquals(1, failures.size)
        assertEquals(AiFailure.Codes.USER_REJECTED, failures[0].code)
    }

    @Test
    fun `宿主死亡 → 全部在途确定性失败 HOST_TERMINATED（§3-7 吸水机制 2）`() {
        val h = Harness()
        val failures = mutableListOf<AiFailure>()
        core_submit(h, onFailure = { failures.add(it) })
        core_submit(h, onFailure = { failures.add(it) })
        h.core.onHostDied()
        assertEquals(2, failures.size)
        assertTrue(failures.all { it.code == AiFailure.Codes.HOST_TERMINATED })
        assertEquals(0, h.core.pendingCount)
    }

    @Test
    fun `GoodHubAiRuntime 自注册：client get 反射触达返回实现（依赖方向 client→runtime 环禁止后仍可达）`() {
        // client 的 GoodHubAiProvider.get() 经 Class.forName 触达本 object 静态
        // 初始化——本测试在 runtime 模块内直接验证注册语义。
        val impl = com.zzh.goodhub.ai.GoodHubAiProvider.get()
        assertTrue("自注册应返回 runtime 实现", impl is GoodHubAiImpl)
    }

    @Test
    fun `协程取消自动映射 ai-cancel（业务层零取消代码的机制钉）`() = runTest {
        val h = Harness()
        val collected = mutableListOf<AiEvent>()
        val impl = GoodHubAiImpl(h.core, CoroutineScope(Dispatchers.Unconfined))
        val job = launch {
            withTimeout(5000) {
                impl.completeStream(AiRequest("i", "x")).take(1).toList(collected)
            }
        }
        // 流挂起等宿主帧 → 取消协程（页面退出等价物）→ ai/cancel 自动发射。
        kotlinx.coroutines.delay(100)
        job.cancel()
        kotlinx.coroutines.delay(100)
        val cancelFrames = h.frames.filter { JSONObject(it).optString("method") == McpContract.METHOD_AI_CANCEL }
        assertEquals("协程取消 → ai/cancel 帧自动发射", 1, cancelFrames.size)
    }

    @Test
    fun `宿主未连接 → HOST_OFFLINE 结构化失败且 pending 回滚（禁裸异常与泄漏）`() {
        val h = Harness(connected = false)
        val failures = mutableListOf<AiFailure>()
        val id = h.core.submitRequest(
            request = AiRequest("i", "x"),
            asyncCapable = true,
            onPhase = null,
            onResult = { _, _ -> },
            onFailure = { failures.add(it) },
        )
        assertTrue(id.isNotEmpty()) // 禁抛裸异常穿透门面
        assertEquals("未连接不应发射请求帧", 0, h.frames.size)
        assertEquals(0, h.core.pendingCount)
        assertEquals(1, failures.size)
        assertEquals(AiFailure.Codes.HOST_OFFLINE, failures[0].code)
    }

    @Test
    fun `发射帧抛异常 → 同等收敛 HOST_OFFLINE（Binder 断连等价）`() {
        val h = Harness()
        h.failPush = true
        val failures = mutableListOf<AiFailure>()
        h.core.submitRequest(
            request = AiRequest("i", "x"),
            asyncCapable = false,
            onPhase = null,
            onResult = { _, _ -> },
            onFailure = { failures.add(it) },
        )
        assertEquals(0, h.core.pendingCount)
        assertEquals(AiFailure.Codes.HOST_OFFLINE, failures.single().code)
    }

    @Test
    fun `cancel 帧发射失败仍本地收口（禁调用方永久悬挂）`() {
        val h = Harness()
        val failures = mutableListOf<AiFailure>()
        val id = core_submit(h, onFailure = { failures.add(it) })
        h.failPush = true
        h.core.cancel(id)
        assertEquals(1, failures.size)
        assertEquals(AiFailure.Codes.USER_REJECTED, failures[0].code)
        assertEquals(0, h.core.pendingCount)
    }

    // ---- helpers ----

    private var lastIdCache = ""

    private fun core_submit(
        h: Harness,
        onPhase: (AiPhase) -> Unit = {},
        onResult: (String, String?) -> Unit = { _, _ -> },
        onFailure: (AiFailure) -> Unit = {},
    ): String {
        val id = h.core.submitRequest(
            request = AiRequest("i", "x"),
            asyncCapable = true,
            onPhase = onPhase,
            onResult = onResult,
            onFailure = onFailure,
        )
        lastIdCache = id
        return id
    }

    private fun Harness.lastId(): String = lastIdCache
}
