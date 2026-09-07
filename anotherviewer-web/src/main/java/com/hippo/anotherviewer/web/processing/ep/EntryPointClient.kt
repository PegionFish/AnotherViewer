package com.hippo.anotherviewer.web.processing.ep

import java.nio.file.Path

/**
 * EntryPoint 供应商适配的「五操作」中立接口（对接指南 §2）。
 *
 * 实现只有一个类知道供应商细节（OkHttp + 端点形状 + 错误信封），
 * 换供应商 = 换实现。全部方法挂 [EntryPointCreds]——凭据由调用方
 * 每次从 ServerConfigService 解析（token 服务端加密落盘，不进内存缓存）。
 *
 * 语义约定：
 * - upload 返回「输入引用」（语义引用，禁止解析/持久化其字符串内容，指南 §10.3）；
 * - result 的 outputs[].url 是相对下载 URL，唯一合法操作是 base+url GET（302 跟随）；
 * - cancel 是 best-effort：404/409/部署版未实现（中文兜底体）一律吞掉不抛——
 *   调用方把它当「已终结」处理（D3 降级语义）；
 * - 429/5xx 由实现内部指数退避重试，只在重试耗尽后抛 [EpException]。
 */
interface EntryPointClient {
    /** 能力发现（纯只读）。MODULE/CAPABILITY_NOT_FOUND 时调用方刷新重试。 */
    suspend fun capabilities(creds: EntryPointCreds): List<EpCapability>

    /** 大文件先上传换引用（multipart → workspace/uploads 引用）。 */
    suspend fun upload(creds: EntryPointCreds, file: Path): String

    /** 提交推理（input_path 形态；引用必须来自 [upload]）。 */
    suspend fun submit(
        creds: EntryPointCreds,
        moduleId: String,
        capability: String,
        inputPath: String,
        params: Map<String, Any?> = emptyMap(),
    ): EpSubmitResult

    /** 轮询状态与产物（状态已归一化为 [EpTaskState]）。 */
    suspend fun result(creds: EntryPointCreds, taskId: String): EpTaskResult

    /** 取消（尽力而为，永不抛——见 D3）。 */
    fun cancel(creds: EntryPointCreds, taskId: String)
}
