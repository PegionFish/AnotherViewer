package com.hippo.anotherviewer.web.processing.ep

/**
 * EntryPoint（141:9800 本地推理平台）中立五操作契约的类型面。
 *
 * 契约来源：`/home/bob/EntryPoint/docs/API_INTEGRATION_GUIDE.md`（供应商中立模型）
 * + `INFERENCE_API.md`（字段级），**部署版实测差异以执行手册
 * docs/dev-plan-2026-09-08-image-processing-execution-handoff.md §1 为准**：
 * 部署版无 `/api/v1/tasks`（列表/单查），轮询必须走 `GET /api/v1/inference/result/{id}`；
 * 产物下载是 302 跳转；cancel 路径未验证——一律按「已终结」降级处理。
 *
 * 本包外的代码（编排/持久化/REST）只允许依赖这里的归一化词表，
 * 不得出现 EntryPoint 状态原文（queued/running/...）——可移植性清单 §9。
 */

/** 提交时的连接凭据；由消费方（processor）每次从 ServerConfigService 解析后传入。 */
data class EntryPointCreds(
    val baseUrl: String,
    val token: String,
)

/**
 * 归一化任务状态（EntryPoint 原文 → 本词表）：
 * queued→QUEUED、running→RUNNING、completed→COMPLETED、failed→FAILED、
 * cancelled→CANCELLED；未知原文→UNKNOWN（编排层按运行中处理并 WARN）。
 */
enum class EpTaskState {
    QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, UNKNOWN
}

/** capabilities 目录条目（部署版为扁平 module×capability 列表）。 */
data class EpCapability(
    val moduleId: String,
    val capability: String,
    val inputType: String,
    val outputType: String,
    val maxFileSizeMb: Long?,
    /** 原始参数 schema（name → {type,default,min,max,...}），机判只看 type/default。 */
    val params: Map<String, Any?> = emptyMap(),
)

/** 提交响应：202 + task_id（queue_position 仅排队时出现）。 */
data class EpSubmitResult(
    val taskId: String,
    val queuePosition: Int? = null,
)

/** 轮询响应：outputs 为相对下载 URL（不透明句柄，禁止解析）。 */
data class EpTaskResult(
    val taskId: String,
    val state: EpTaskState,
    val outputs: List<EpArtifact> = emptyList(),
    val error: String? = null,
) {
    /** 终态判定只依赖归一化词表。 */
    val isTerminal: Boolean
        get() = this.state == EpTaskState.COMPLETED ||
            this.state == EpTaskState.FAILED ||
            this.state == EpTaskState.CANCELLED
}

data class EpArtifact(
    val nodeId: String,
    val url: String,
)

/**
 * 错误信封 `{"error":{"code","message"}}` 的机读码异常。
 * 客户端逻辑只允许 switch [code]，禁止解析 message 文案（可能本地化）。
 */
class EpException(
    val code: String,
    val httpStatus: Int,
    message: String,
) : RuntimeException("$code (HTTP $httpStatus): $message")
