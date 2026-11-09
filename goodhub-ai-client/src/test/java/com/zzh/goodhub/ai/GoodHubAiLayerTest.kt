package com.zzh.goodhub.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 消费方门面破层红线 + 静态探测零 IPC 断言（ai-capability-export M-C §3.5/§5）。
 *
 * 破层红线：client 工件**源级**禁引 contract / AIDL / transport 类型——
 * `AiResult`/`AiEvent`/`AiFailure` 全部自有类型；依赖树禁含 AIDL 生成代码
 * 由构建层保证（client 无 :contract 依赖），本测试钉源级 import 面。
 */
class GoodHubAiLayerTest {

    private fun clientSources(): List<File> {
        val root = File("src/main/java/com/zzh/goodhub/ai")
        assertTrue("client 源目录不存在（工作目录应为模块根）: ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `破层断言：client 源零 contract 与 AIDL 引用（import 与全限定名双查）`() {
        val violations = mutableListOf<String>()
        for (f in clientSources()) {
            val text = f.readText()
            // 判据 = 包名级引用（import / 全限定名 / AIDL 类型符号）；文档注释
            // 里的 SSOT 指针（裸 `McpContract.AI_*` 字样）不属代码引用，放行。
            if (Regex("""^\s*import\s+com\.zzh\.goodhub\.mcp""", RegexOption.MULTILINE).containsMatchIn(text)) {
                violations.add("${f.name}: import com.zzh.goodhub.mcp.*")
            }
            if (text.contains("com.zzh.goodhub.mcp.")) {
                violations.add("${f.name}: contract 全限定名引用")
            }
            if (text.contains("IMcpChannel") || text.contains("IMcpCallback")) {
                violations.add("${f.name}: AIDL 类型引用")
            }
        }
        assertTrue("破层红线命中：\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun `门面 API 面冻结：四入口 + 五类型（§3-6 双面清单）`() {
        // 反射核对公开 API 形态（编译期之外的防漂移钉）。
        val iface = GoodHubAi::class.java
        val methods = iface.methods.map { it.name }.toSet()
        assertTrue(methods.containsAll(listOf("complete", "completeStream", "completeSync")))
        // 五类型存在性。
        assertNotNull(AiRequest::class.java)
        assertNotNull(AiEvent::class.java)
        assertNotNull(AiPhase::class.java)
        assertNotNull(AiFailure::class.java)
        assertNotNull(AiSyncResult::class.java)
        // 阶段四值冻结（与线级 _meta.phase 同值）。
        assertEquals(
            listOf("STARTING_HOST", "LOADING_MODEL", "QUEUING", "GENERATING"),
            AiPhase.values().map { it.name }
        )
        // 十二码冻结（与线级 error.data.status 同值）。
        assertEquals(
            setOf(
                "MODEL_COLD", "RESOURCE_BUSY", "HOST_OFFLINE", "HOST_TERMINATED",
                "BUDGET_EXCEEDED", "UNAUTHORIZED", "RATE_LIMIT", "CALLER_NOT_IN_FOREGROUND",
                "USER_REJECTED", "ENGINE_FAILED", "DEVICE_THROTTLED", "PREEMPTED_BY_HOST"
            ),
            AiFailure.Codes::class.java.declaredFields
                .filter { it.type == String::class.java }
                .map { it.get(null) }
                .toSet()
        )
    }

    @Test
    fun `静态探测零 IPC：JVM 无宿主环境返回 false 不崩（不触发 bind）`() {
        // JVM 单测：PackageManager 桩返回 null/空 → isAvailable 必须 false 且
        // 零异常；实现路径无 bindService/startService/bindService 调用（源级断言）。
        val sources = clientSources().joinToString("\n") { it.readText() }
        assertFalse("isAvailable 路径禁 bindService（零 IPC 红线）", sources.contains("bindService"))
        assertFalse(sources.contains("startService"))
    }

    @Test
    fun `升阶分流判定：冷态与资源类失败应转异步；授权失败走引导件`() {
        val cold = AiFailure(AiFailure.Codes.MODEL_COLD)
        val busy = AiFailure(AiFailure.Codes.RESOURCE_BUSY, retryAfterMs = 2000L)
        val offline = AiFailure(AiFailure.Codes.HOST_OFFLINE)
        val unauthorized = AiFailure(AiFailure.Codes.UNAUTHORIZED)
        assertTrue(cold.shouldEscalateToAsync)
        assertTrue(busy.shouldEscalateToAsync)
        assertTrue(busy.retryAfterMs == 2000L)
        assertFalse(offline.shouldEscalateToAsync)
        assertTrue(unauthorized.isUnauthorized)
        assertFalse(cold.isUnauthorized)
    }

    private fun assertNotNull(x: Any?) {
        if (x == null) throw AssertionError("unexpected null")
    }
}
