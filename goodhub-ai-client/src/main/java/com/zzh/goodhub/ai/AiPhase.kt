package com.zzh.goodhub.ai

/**
 * 生成阶段四值（与线级 `params._meta.phase` 同值单源 = 契约仓
 * `McpContract.AI_PHASE_*`；goodhub 宿主 Dart 侧 [AiWire] 冻结）。
 *
 * 顺序即推荐展示次序，非进度承诺；业务层据此渲染等待态（Preparing 阶梯）。
 */
enum class AiPhase {
    /** 宿主进程拉起 / 通道建立。 */
    STARTING_HOST,

    /** 端侧模型装载（1.5~3GB，分钟级窗口；异步形态可等冷启动）。 */
    LOADING_MODEL,

    /** 受理排队（并发槽 / 配额等待）。 */
    QUEUING,

    /** 推理生成中。 */
    GENERATING,
}
