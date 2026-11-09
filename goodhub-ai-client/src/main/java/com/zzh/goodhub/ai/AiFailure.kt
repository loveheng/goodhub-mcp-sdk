package com.zzh.goodhub.ai

import android.content.Context
import android.content.Intent

/**
 * 结构化失败（十二码表，契约 api.md §8.5；线级取值 = `error.data.status`，
 * 契约仓 `McpContract.AI_FAIL_*` / 宿主 `AiWire` 单源冻结）。
 *
 * 一个码表两处消费：同步侧 [AiSyncResult.FastFail] 快照 + 异步侧
 * [AiEvent.Failure]，禁两套枚举。
 */
data class AiFailure(
    /** 十二码之一（[AiFailureCodes]）；未知码透传原文（向前兼容新码）。 */
    val code: String,
    /** 宿主话术（人类可读；信息不外泄红线——不含宿主内部模型规格）。 */
    val message: String? = null,

    /** 建议退避毫秒（仅提示非承诺；SDK 不自动重试，delay 决策归业务层）。 */
    val retryAfterMs: Long? = null,
) {
    /** 码表常量（与契约十二码逐值对应；禁另造字面量）。 */
    object Codes {
        const val MODEL_COLD = "MODEL_COLD"
        const val RESOURCE_BUSY = "RESOURCE_BUSY"
        const val HOST_OFFLINE = "HOST_OFFLINE"
        const val HOST_TERMINATED = "HOST_TERMINATED"
        const val BUDGET_EXCEEDED = "BUDGET_EXCEEDED"
        const val UNAUTHORIZED = "UNAUTHORIZED"
        const val RATE_LIMIT = "RATE_LIMIT"
        const val CALLER_NOT_IN_FOREGROUND = "CALLER_NOT_IN_FOREGROUND"
        const val USER_REJECTED = "USER_REJECTED"
        const val ENGINE_FAILED = "ENGINE_FAILED"
        const val DEVICE_THROTTLED = "DEVICE_THROTTLED"
        const val PREEMPTED_BY_HOST = "PREEMPTED_BY_HOST"
    }

    /** 便捷判定：是否未获授权（引导件 [createResolutionIntent] 的消费锚）。 */
    val isUnauthorized: Boolean get() = code == Codes.UNAUTHORIZED

    /** 便捷判定：升阶分流建议——本失败转异步形态可能成功（冷态/资源类）。 */
    val shouldEscalateToAsync: Boolean
        get() = code == Codes.MODEL_COLD || code == Codes.RESOURCE_BUSY ||
            code == Codes.BUDGET_EXCEEDED
}

/**
 * 授权引导闭环（ai-capability-export §5）：`UNAUTHORIZED` 失败 → 本函数按
 * 公开常量本地构造授权页跳转意图（宿主侧以 `getCallingUid()` 实证身份，
 * intent 参数仅预填）。未安装宿主 = 返回 null（调用方按 [GoodHubAi.isAvailable]
 * 条件渲染兜底）。
 */
fun AiFailure.createResolutionIntent(context: Context): Intent? {
    if (!isUnauthorized) return null
    val pkg = GoodHubAi.hostPackageName
    val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return null
    launch.putExtra("route", "settings/capability/authorize")
    return launch
}
