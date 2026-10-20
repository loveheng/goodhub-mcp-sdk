package com.zzh.goodhub.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 契约冻结自检（CI 跑 `:contract:test`）。
 *
 * 本仓存在的核心价值之一：让 AIDL 与发现约定**只有一份物理副本**，且这份副本不被误改。
 * 以下断言即「线级契约」的守门员——任一条红灯都意味着**破坏性变更**：
 *  - AIDL 包名变更 → DESCRIPTOR 变更 → 存量插件全部 bind 失败；
 *  - 方法增删 → 线协议不兼容；
 *  - 发现约定常量变更 → 存量插件不再被宿主扫描到。
 */
class McpContractTest {

    private val aidlDir = File("src/main/aidl/com/zzh/goodhub/mcp")

    @Test
    fun `AIDL 包名冻结为 com zzh goodhub mcp`() {
        for (name in listOf("IMcpChannel.aidl", "IMcpCallback.aidl")) {
            val pkg = Regex("""package\s+([\w.]+)\s*;""").find(readAidl(name))?.groupValues?.get(1)
            assertEquals(
                "AIDL 包名是线级常量（DESCRIPTOR 随每次 Binder 事务上线），改动即打断存量插件：$name",
                McpContract.AIDL_PACKAGE,
                pkg
            )
        }
    }

    @Test
    fun `AIDL 方法集冻结`() {
        assertEquals(
            "IMcpChannel 方法集变更属线级破坏性变更",
            setOf("call", "registerCallback", "answerReverse"),
            methodNames(readAidl("IMcpChannel.aidl"))
        )
        assertEquals(
            "IMcpCallback 方法集变更属线级破坏性变更",
            setOf("onEvent"),
            methodNames(readAidl("IMcpCallback.aidl"))
        )
    }

    @Test
    fun `oneway 语义冻结`() {
        // oneway 决定宿主侧是否阻塞等待：改掉会直接改变两端线程模型。
        val channel = readAidl("IMcpChannel.aidl")
        val callback = readAidl("IMcpCallback.aidl")
        assertTrue("answerReverse 必须保持 oneway（否则宿主应答阻塞插件线程）", isOneway(channel, "answerReverse"))
        assertTrue("onEvent 必须保持 oneway（否则插件事件阻塞插件线程）", isOneway(callback, "onEvent"))
    }

    @Test
    fun `发现约定常量值冻结`() {
        assertEquals("com.zzh.goodhub.action.MCP_SERVER", McpContract.DISCOVERY_ACTION)
        assertEquals("com.zzh.goodhub.SERVER_ID", McpContract.META_SERVER_ID)
        assertEquals("com.zzh.goodhub.SERVER_TITLE", McpContract.META_SERVER_TITLE)
        assertEquals("com.zzh.goodhub.permission.MCP_SERVER", McpContract.PERMISSION_MCP_SERVER)
    }

    @Test
    fun `VERSION 文件与契约常量一致`() {
        val props = java.util.Properties().apply {
            val f = File("../VERSION")
            assertTrue("找不到 VERSION 文件（应位于仓根）：${f.absolutePath}", f.exists())
            f.inputStream().use { load(it) }
        }
        assertEquals(
            "VERSION 的 contract_version 与 McpContract.CONTRACT_VERSION 不一致",
            McpContract.CONTRACT_VERSION,
            props.getProperty("contract_version")
        )
        assertEquals(
            "VERSION 的 mcp_protocol_version 与 McpContract.MCP_PROTOCOL_VERSION 不一致",
            McpContract.MCP_PROTOCOL_VERSION,
            props.getProperty("mcp_protocol_version")
        )
    }

    private fun readAidl(name: String): String {
        val f = File(aidlDir, name)
        assertTrue("缺少 AIDL 契约文件：${f.absolutePath}", f.exists())
        return f.readText()
    }

    private fun methodNames(aidl: String): Set<String> =
        Regex("""(?m)^\s*(?:oneway\s+)?[\w.<>\[\]]+\s+(\w+)\s*\(""")
            .findAll(aidl)
            .map { it.groupValues[1] }
            .toSet()

    private fun isOneway(aidl: String, method: String): Boolean =
        Regex("""(?m)^\s*oneway\s+[\w.<>\[\]]+\s+$method\s*\(""").containsMatchIn(aidl)
}
