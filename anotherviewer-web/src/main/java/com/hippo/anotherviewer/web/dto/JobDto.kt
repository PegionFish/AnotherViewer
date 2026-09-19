package com.hippo.anotherviewer.web.dto

import com.hippo.anotherviewer.web.service.Job

/**
 * 统一后台任务契约（方案 plan-2026-08-06 附录 A1/A2）。
 *
 * [JobType] 枚举同时是 GET /api/v1/jobs/active 的 type 参数取值；
 * [JobSubmitResponse] 是所有异步提交端点的 202 响应体。
 */
enum class JobType {
    IMPORT, EXPORT, RESTORE, CACHE_CLEAR, REVERIFY
}
// REVERIFY：文件完整性 W0 契约（contracts/openapi.yaml JobType 枚举）已声明、
// Wave 3 落地的整本复验任务——POST /api/v1/integrity/reverify/{gid} 提交
// （S6），worker 调 DownloadService.reverifyGallery（S7），终态 result 为
// ReverifyStats。

enum class JobState {
    PENDING, RUNNING, COMPLETED, FAILED
}

/** 202 提交响应：异步端点立即返回 jobId + 初始状态。 */
data class JobSubmitResponse(
    val jobId: String,
    val state: JobState,
)

/** Job 的 JSON 视图（percent 由 processed/total 计算，total<=0 时为 0）。 */
data class JobDto(
    val jobId: String,
    val type: JobType,
    val state: JobState,
    val stage: String?,
    val percent: Double,
    val processed: Long,
    val total: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val error: String?,
    val result: Any?,
)

fun Job.toDto(): JobDto = JobDto(
    jobId = jobId,
    type = type,
    state = state,
    stage = stage,
    percent = if (total <= 0) 0.0 else (processed.toDouble() / total * 100).coerceIn(0.0, 100.0),
    processed = processed,
    total = total,
    startedAt = startedAt,
    completedAt = completedAt,
    error = error,
    result = result,
)
