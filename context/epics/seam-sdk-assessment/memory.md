---
dev-loop: memory
format: v1
epic: seam-sdk-assessment
kind: temp
total-merged: 1
last-merge: 2026-11-09
---

# seam-sdk-assessment（临时）

- [评估文档] docs/architecture/seam-sdk-assessment.md（定位定性 + 六维评价 + 能力短板与处置优先级；临时 epic 不写 [挂载] 行，避免被判正式 epic）

## 结论（归并 2026-11-09）

- 定位：体系内接缝执行件；工具面与算力面并列主交付。补强方向是闭合与机械保真，不是加扩展点或抽传输抽象。
- 评估文档已成型：双路径图、六维评价、算力主交付阻断、单原语两条消费路径能力不对等表、声明与实现不一致、P0~P3 处置表。
- 评审裁决：采纳重心补正与事实级修正；否决 `ai/abort`（已有 `ai/cancel`）、流式 chunking（v1 禁 delta）、HostCapabilities 遮罩、`callingUid` 透传、SDK 指数退避、新错误码、Robolectric（纯 JVM 快照即可）。
- C 项补「超时不等于取消」：`orTimeout` 只异常完成 future，须同时移除 `pendingReverse` 条目并以原 id 发 `ai/cancel`。
- P0 入口可见性已落地：真机与 APK 双向取证确认宿主包零 service 零 `MCP_SERVER`；`isAvailable` 改查宿主包启动 Activity；queries 归位 runtime 库 Manifest，client 保持纯 Kotlin 零 Android 资源；门禁 R5 与 `GoodHubAiVisibilityTest` 钉死双源一致。
- 验证基线：R3 五模块 assemble + test 全绿（57 例 0 failures）；`aapt2` 实证 queries 合并进 sample APK；docs-lint 通过。
- 待办：宿主侧 `ai/complete` 与 `host/onDeviceTaskStatus` 接线状态（跨仓只读）；装有宿主的真机复验 `isAvailable == true`。

## 断点
- [断点] 下一步：等待用户提供宿主侧 ai/complete 与 host/onDeviceTaskStatus 接线状态（P0 主干，跨仓只读）；真机复验 isAvailable 需先安装 com.zzh.goodhub
