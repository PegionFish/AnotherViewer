package com.hippo.anotherviewer.web.processing.ep

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okhttp3.Response
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import com.hippo.anotherviewer.web.service.WebProxyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import okio.source

/**
 * [EntryPointClient] 的 OkHttp 实现——唯一知道 EntryPoint 端点形状、鉴权与
 * 错误信封细节的类（执行手册 §2「供应商细节隔离在 processing/ep/」）。
 *
 * 实测契约（手册 §1，2026-09-08 冒烟，**以实测为准**）：
 * - 鉴权 `Authorization: Bearer <token>`（token 空 = 直通，不带该头）；
 * - 能力发现 GET /api/v1/capabilities（扁平 module×capability 列表）；
 * - 上传 POST /api/files（multipart 单字段 `file`）→ `{"path": 引用}`；
 * - 提交 POST /api/v1/inference/{module}/{capability}（JSON `{"input_path","params"}`）
 *   → 202 `{"task_id","queue_position"?}`；
 * - 轮询 GET /api/v1/inference/result/{task_id}（部署版无 /api/v1/tasks，D3）；
 * - 产物 GET {base}{outputs[].url}，**302 → 文件流**，必须跟随重定向；
 * - 取消 POST /api/v1/tasks/{task_id}/cancel——best-effort，任何失败一律 WARN 吞掉（永不抛）。
 *
 * 错误信封 `{"error":{"code","message"}}` → [EpException]（机读 code）；
 * JSON 但非信封形状（如部署版 404 兜底体 `{"error":"接口不存在"}`）→ code=UNKNOWN；
 * 非 JSON 体 → code=HTTP_<status>。机判只看 code，禁止解析 message（D12）。
 *
 * HTTP 客户端：懒构建共享 client，照 `EhAvailabilityService.probeClient()` 模板挂
 * [WebProxyManager] 的 selector/authenticator（与站点流量一致的代理链），
 * connect 15s / read 120s / write 120s，显式保留 followRedirects(true)（产物 302）。
 * 请求级 callTimeout：轮询/取消 10s，其余（上传/提交/能力/产物下载）180s——
 * 提交放宽到 180s 覆盖模块冷启动（手册 §8 R2）。429/5xx 仅对上传与提交做
 * 指数退避重试（起 1s，×2，至多 3 次重试），重试耗尽抛 [EpException]。
 */
@Component
class OkHttpEntryPointClient(
    private val webProxyManager: WebProxyManager,
    /** 429/5xx 退避首延时（毫秒）；生产默认 1000（×2 递增）。测试注入小值加速。 */
    private val retryBaseDelayMs: Long = 1_000,
    /** 429/5xx 至多重试次数（不含首次请求）。 */
    private val maxRetries: Int = 3,
) : EntryPointClient {

    private val logger = LoggerFactory.getLogger(OkHttpEntryPointClient::class.java)
    private val json: ObjectMapper = jacksonObjectMapper()

    /** 懒构建共享 client（纯单测/未触发网络的调用方不付建连成本）。 */
    @Volatile
    private var httpClient: OkHttpClient? = null

    private fun client(): OkHttpClient =
        httpClient ?: synchronized(this) {
            httpClient ?: OkHttpClient.Builder()
                .proxySelector(webProxyManager.selector())
                .proxyAuthenticator(webProxyManager.authenticator())
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                // 产物下载依赖 302 跟随（手册 §1）；OkHttp 默认开启，此处显式声明防回归。
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
                .also { httpClient = it }
        }

    // ------------------------------------------------------------------
    // 五操作
    // ------------------------------------------------------------------

    override suspend fun capabilities(creds: EntryPointCreds): List<EpCapability> =
        exec(OP_CAPABILITIES, CALL_TIMEOUT_DEFAULT_MS, retryOnThrottle = false, { requestBuilder(creds, "api/v1/capabilities").get().build() }) { resp ->
            if (!resp.isSuccessful) throw errorException(resp)
            val root = readJson(resp) ?: throw EpException("UNKNOWN", resp.code, "malformed capabilities response")
            val items = root["capabilities"]?.takeIf { it.isArray } ?: return@exec emptyList()
            items.map { entry ->
                EpCapability(
                    moduleId = entry["module_id"]?.asText().orEmpty(),
                    capability = entry["capability"]?.asText().orEmpty(),
                    inputType = entry["input_type"]?.asText().orEmpty(),
                    outputType = entry["output_type"]?.asText().orEmpty(),
                    maxFileSizeMb = entry["max_file_size_mb"]?.takeIf { it.isNumber }?.asLong(),
                    params = entry["params"]?.takeIf { it.isObject }
                        ?.let { json.convertValue(it, object : TypeReference<Map<String, Any?>>() {}) }
                        ?: emptyMap(),
                )
            }
        }

    override suspend fun upload(creds: EntryPointCreds, file: Path): String =
        exec(OP_UPLOAD, CALL_TIMEOUT_DEFAULT_MS, retryOnThrottle = true, {
            // Multipart 逐次重建：文件流（Files.newInputStream）只可消费一次，
            // 重试时 requestFactory 会重新打开流（大文件流式上传，不进内存）。
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.fileName.toString(), StreamingFileBody(file))
                .build()
            requestBuilder(creds, "api/files").post(multipart).build()
        }) { resp ->
            if (!resp.isSuccessful) throw errorException(resp)
            val root = readJson(resp) ?: throw EpException("UNKNOWN", resp.code, "malformed upload response")
            root["path"]?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
                ?: throw EpException("UNKNOWN", resp.code, "upload response missing path reference")
        }

    override suspend fun submit(
        creds: EntryPointCreds,
        moduleId: String,
        capability: String,
        inputPath: String,
        params: Map<String, Any?>,
    ): EpSubmitResult {
        val payload = json.writeValueAsBytes(mapOf("input_path" to inputPath, "params" to params))
        val jsonBody = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        return exec(OP_SUBMIT, CALL_TIMEOUT_DEFAULT_MS, retryOnThrottle = true, {
            requestBuilder(creds, "api/v1/inference/$moduleId/$capability").post(jsonBody).build()
        }) { resp ->
            if (!resp.isSuccessful) throw errorException(resp)
            val root = readJson(resp) ?: throw EpException("UNKNOWN", resp.code, "malformed submit response")
            val taskId = root["task_id"]?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
                ?: throw EpException("UNKNOWN", resp.code, "submit response missing task_id")
            EpSubmitResult(
                taskId = taskId,
                queuePosition = root["queue_position"]?.takeIf { it.isNumber }?.asInt(),
            )
        }
    }

    override suspend fun result(creds: EntryPointCreds, taskId: String): EpTaskResult =
        exec(OP_RESULT, CALL_TIMEOUT_POLL_MS, retryOnThrottle = false, {
            requestBuilder(creds, "api/v1/inference/result/$taskId").get().build()
        }) { resp ->
            if (!resp.isSuccessful) throw errorException(resp)
            val root = readJson(resp) ?: throw EpException("UNKNOWN", resp.code, "malformed result response")
            EpTaskResult(
                taskId = root["task_id"]?.asText()?.takeIf { it.isNotBlank() } ?: taskId,
                state = normalizeState(root["status"]?.asText()),
                outputs = root["outputs"]?.takeIf { it.isArray }
                    ?.mapNotNull { output ->
                        val url = output["url"]?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        EpArtifact(nodeId = output["node_id"]?.asText().orEmpty(), url = url)
                    }
                    ?: emptyList(),
                error = root["error"]?.let { node ->
                    when {
                        node.isTextual -> node.asText()
                        node.isObject -> node["message"]?.asText() ?: node.toString()
                        else -> node.asText()
                    }?.takeIf { it.isNotBlank() }
                },
            )
        }

    /**
     * 取消（D3 降级语义）：部署版该路径可能整体不存在（404 / 中文兜底体 /
     * 409 / IO 异常）——任何失败一律 WARN 记录后返回，**永不抛**，
     * 调用方把它当「已终结」处理。
     */
    override fun cancel(creds: EntryPointCreds, taskId: String) {
        try {
            val request = requestBuilder(creds, "api/v1/tasks/$taskId/cancel")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            val call = client().newCall(request)
            // 取消走在超时/失败路径上，best-effort——10s 封顶，不拖慢失败汇报。
            call.timeout().timeout(CALL_TIMEOUT_CANCEL_MS, TimeUnit.MILLISECONDS)
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    logger.warn(
                        "EntryPoint cancel {} returned HTTP {} (treated as terminal, D3)",
                        taskId, resp.code,
                    )
                } else {
                    logger.info("EntryPoint cancel {} accepted", taskId)
                }
            }
        } catch (e: Exception) {
            logger.warn("EntryPoint cancel {} failed (treated as terminal, D3): {}", taskId, e.message)
        }
    }

    /**
     * 产物下载（五操作之外的工具操作；产物 URL 是不透明句柄，唯一合法动作是
     * base+url GET——**302 跳转必须跟随**，落盘到 [target]）。
     * 不在 [EntryPointClient] 冻结接口内：处理器经注入的下载器 seam 调用。
     */
    suspend fun downloadToFile(creds: EntryPointCreds, url: String, target: Path) {
        val absolute = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            creds.baseUrl.trimEnd('/') + (if (url.startsWith("/")) url else "/$url")
        }
        val httpUrl = absolute.toHttpUrlOrNull()
            ?: throw EpException(EP_UNREACHABLE, 0, "invalid artifact url: $url")
        exec(OP_DOWNLOAD, CALL_TIMEOUT_DEFAULT_MS, retryOnThrottle = false, {
            applyAuth(Request.Builder().url(httpUrl), creds).get().build()
        }) { resp ->
            if (!resp.isSuccessful) throw errorException(resp)
            val body = resp.body ?: throw EpException("UNKNOWN", resp.code, "empty artifact body")
            Files.copy(body.byteStream(), target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // ------------------------------------------------------------------
    // 内部：请求构建 / 重试 / 解析
    // ------------------------------------------------------------------

    private fun requestBuilder(creds: EntryPointCreds, pathSegments: String): Request.Builder =
        applyAuth(Request.Builder().url(entryUrl(creds, pathSegments)), creds)

    private fun entryUrl(creds: EntryPointCreds, pathSegments: String): okhttp3.HttpUrl {
        val base = creds.baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw EpException(EP_UNREACHABLE, 0, "invalid entrypoint baseUrl: '${creds.baseUrl}'")
        return base.newBuilder().addPathSegments(pathSegments).build()
    }

    /** token 可能为空（直通部署）——此时不带头。 */
    private fun applyAuth(builder: Request.Builder, creds: EntryPointCreds): Request.Builder {
        if (creds.token.isNotBlank()) builder.header("Authorization", "Bearer ${creds.token}")
        return builder
    }

    /**
     * 统一执行：请求级 callTimeout、阻塞 IO 挪到 Dispatchers.IO、
     * 429/5xx（仅 [retryOnThrottle]=true 的操作）指数退避重试（1s 起 ×2，至多
     * [maxRetries] 次），连接类 IO 异常归一化为 EP_UNREACHABLE。
     */
    private suspend fun <T> exec(
        op: String,
        callTimeoutMs: Long,
        retryOnThrottle: Boolean,
        requestFactory: () -> Request,
        handle: (Response) -> T,
    ): T {
        var attempt = 0
        var backoffMs = retryBaseDelayMs
        while (true) {
            val response: Response = withContext(Dispatchers.IO) {
                val call = client().newCall(requestFactory())
                call.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)
                try {
                    call.execute()
                } catch (e: IOException) {
                    throw EpException(EP_UNREACHABLE, 0, e.message ?: e.javaClass.simpleName)
                }
            }
            val shouldRetry = retryOnThrottle &&
                (response.code == 429 || response.code >= 500) &&
                attempt < maxRetries
            if (shouldRetry) {
                response.close()
                attempt++
                logger.warn(
                    "EntryPoint {} got HTTP {}; retry {}/{} in {} ms",
                    op, response.code, attempt, maxRetries, backoffMs,
                )
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(RETRY_DELAY_CAP_MS)
                continue
            }
            return withContext(Dispatchers.IO) { response.use(handle) }
        }
    }

    /** 读取并解析 2xx JSON 体；空体返回 null（交由调用方按 UNKNOWN 处理）。 */
    private fun readJson(resp: Response): com.fasterxml.jackson.databind.JsonNode? {
        val text = resp.body?.string()?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { json.readTree(text) }.getOrNull()
    }

    /**
     * 错误信封解析阶梯（手册 §1）：
     * 1. `{"error":{"code","message"}}` → EpException(code, status, message)；
     * 2. JSON 但非信封（含 `{"error":"接口不存在"}` 中文兜底）→ EpException("UNKNOWN")；
     * 3. 非 JSON 体 → EpException("HTTP_<status>")。
     */
    private fun errorException(resp: Response): EpException {
        val status = resp.code
        val body = resp.body?.string()?.trim().orEmpty()
        if (body.startsWith("{") || body.startsWith("[")) {
            val node = runCatching { json.readTree(body) }.getOrNull()
            if (node != null && node.isObject) {
                val error = node["error"]
                if (error != null && error.isObject) {
                    val code = error["code"]?.asText()?.takeIf { it.isNotBlank() } ?: "UNKNOWN"
                    val message = error["message"]?.asText().orEmpty()
                    return EpException(code, status, message)
                }
                if (error != null && error.isTextual) {
                    return EpException("UNKNOWN", status, error.asText())
                }
                return EpException("UNKNOWN", status, body)
            }
        }
        return EpException("HTTP_$status", status, body.take(ERROR_BODY_SNIPPET))
    }

    /** 状态归一化（D4）：五态原名映射；未知原文 → UNKNOWN（上层按运行中处理并 WARN）。 */
    private fun normalizeState(raw: String?): EpTaskState = when (raw?.lowercase()) {
        "queued" -> EpTaskState.QUEUED
        "running" -> EpTaskState.RUNNING
        "completed" -> EpTaskState.COMPLETED
        "failed" -> EpTaskState.FAILED
        "cancelled" -> EpTaskState.CANCELLED
        else -> EpTaskState.UNKNOWN
    }

    /**
     * 大文件流式上传 body：每次 [writeTo] 重新打开 [Files.newInputStream]，
     * 内容不进内存；contentLength 取文件大小（multipart 边界由外层补齐）。
     */
    private class StreamingFileBody(private val file: Path) : RequestBody() {
        override fun contentType() = mediaTypeFor(file.fileName.toString())
        override fun contentLength(): Long = Files.size(file)
        override fun writeTo(sink: BufferedSink) {
            Files.newInputStream(file).use { input ->
                sink.writeAll(input.source())
            }
        }

        private fun mediaTypeFor(name: String): okhttp3.MediaType {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".gif") -> "image/gif"
                else -> "application/octet-stream"
            }.toMediaType()
        }
    }

    companion object {
        const val EP_UNREACHABLE = "EP_UNREACHABLE"

        /** 请求级 callTimeout：轮询/取消 10s（手册 C1 卡），其余 180s（覆盖冷启动 R2）。 */
        const val CALL_TIMEOUT_POLL_MS = 10_000L
        const val CALL_TIMEOUT_CANCEL_MS = 10_000L
        const val CALL_TIMEOUT_DEFAULT_MS = 180_000L

        /** 退避上限，防无限翻倍。 */
        const val RETRY_DELAY_CAP_MS = 60_000L
        private const val ERROR_BODY_SNIPPET = 512

        private const val OP_CAPABILITIES = "capabilities"
        private const val OP_UPLOAD = "upload"
        private const val OP_SUBMIT = "submit"
        private const val OP_RESULT = "result"
        private const val OP_DOWNLOAD = "download"
    }
}
