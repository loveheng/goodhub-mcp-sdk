package com.zzh.goodhub.ai

/**
 * 一次对外推理请求（业务层唯一要「写」的东西；ai-capability-export §3.6）。
 *
 * 任务语义由调用方编排：[instruction] 写清任务与输出要求（2B prompt 三段式
 * ——角色任务 + 负向硬约束 + 分隔符定界，见《业务层 AI 接入规范指南》），
 * [input] 放待处理原文（合法 UTF-8 纯文本，禁 Base64 媒体）。
 */
data class AiRequest(
    val instruction: String,
    val input: String,
    val config: AiConfig? = null,
)

/**
 * 声明式 config（契约 api.md §8.1；未声明字段一律取宿主默认，禁感知宿主内部
 * 模型规格）。**config 不含路由字段**——端侧 / 云端由设备主在 GoodHub 设置里
 * 选择，调用方不得强制（D1 拍板）。
 */
data class AiConfig(
    val maxTokens: Int? = null,
    val temperature: Double? = null,

    /**
     * 约束解码规格（S17b 键集：`{type: 'json'|'regex', value}`）。
     * 端侧腿承载约束解码；云腿 v1 未承载——声明即结构化拒服务（显式可知）。
     */
    val responseFormat: AiResponseFormat? = null,
)

/** 约束解码规格（json = JSON Schema 文本 / regex = 正则模式）。 */
data class AiResponseFormat(
    val type: String,
    val value: String,
) {
    companion object {
        fun json(schema: String) = AiResponseFormat("json", schema)
        fun regex(pattern: String) = AiResponseFormat("regex", pattern)
    }
}
