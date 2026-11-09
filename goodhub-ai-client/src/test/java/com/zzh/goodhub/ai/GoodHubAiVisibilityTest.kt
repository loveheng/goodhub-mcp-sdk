package com.zzh.goodhub.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 入口可见性回归钉（P0）：静态探测的查询目标与包可见性声明。
 *
 * 取证结论：宿主包不声明任何 service，也不出现 `MCP_SERVER`；该 Action 由插件侧声明。
 * `isAvailable` 以宿主包启动 Activity 为目标；queries 由 runtime 库随包合并
 * （client 保持纯 Kotlin API 面、零 Android 资源），两处字面量由本测试钉死一致性。
 */
class GoodHubAiVisibilityTest {

    private val aiEventSource = File("src/main/java/com/zzh/goodhub/ai/AiEvent.kt")
    private val runtimeManifest = File("../goodhub-ai-runtime/src/main/AndroidManifest.xml")

    /**
     * 源级断言只扫代码/标记：注释里描述「禁用某写法」时必然包含该字面量，
     * 不剥离注释会把说明文字误判为实现命中。
     */
    private fun withoutComments(text: String): String =
        text.replace(Regex("""<!--[\s\S]*?-->"""), "")
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""^\s*//.*$""", RegexOption.MULTILINE), "")

    @Test
    fun `client 保持纯 API 面，不携带 AndroidManifest`() {
        assertFalse(
            "queries 须由 runtime 库随包声明，client 不得有 AndroidManifest",
            File("src/main/AndroidManifest.xml").exists(),
        )
    }

    @Test
    fun `静态探测以宿主包启动 Activity 为目标，禁查 MCP_SERVER service`() {
        val src = withoutComments(aiEventSource.readText())
        assertTrue(
            "探测目标须与 createResolutionIntent 同源",
            src.contains("getLaunchIntentForPackage(hostPackageName)"),
        )
        assertFalse(
            "宿主不声明 MCP_SERVER service，queryIntentServices 恒空",
            src.contains("queryIntentServices"),
        )
    }

    @Test
    fun `runtime 库随包声明宿主包可见性`() {
        assertTrue("runtime 库须有 AndroidManifest.xml", runtimeManifest.isFile)
        val xml = withoutComments(runtimeManifest.readText())
        assertTrue("须含 queries 节点", xml.contains("<queries>"))
        assertTrue("须以 package 形式声明", xml.contains("<package "))
        assertFalse(
            "禁以 intent 形式声明（宿主不声明 MCP_SERVER action）",
            xml.contains("<intent>"),
        )
    }

    @Test
    fun `queries 中的宿主包名与 hostPackageName 同源（防双源漂移）`() {
        val xml = withoutComments(runtimeManifest.readText())
        val declared = Regex("""<package\s+android:name="([^"]+)"\s*/>""")
            .find(xml)?.groupValues?.get(1)
        assertEquals(
            "Manifest 包名漂移：须与 GoodHubAi.hostPackageName 一致",
            GoodHubAi.hostPackageName,
            declared,
        )
    }

    @Test
    fun `零 IPC 红线：探测路径不触发 bindService 与 startService`() {
        val src = withoutComments(aiEventSource.readText())
        assertFalse(src.contains("bindService"))
        assertFalse(src.contains("startService"))
    }
}
