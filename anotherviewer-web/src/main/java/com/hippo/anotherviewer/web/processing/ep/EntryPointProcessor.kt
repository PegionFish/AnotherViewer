package com.hippo.anotherviewer.web.processing.ep

import com.hippo.anotherviewer.web.processing.ImageProcessor
import com.hippo.anotherviewer.web.processing.ProcessingOptions
import com.hippo.anotherviewer.web.processing.ProcessingType
import com.hippo.anotherviewer.web.service.ServerConfigService
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream

/**
 * 产物下载 seam：把 `GET {base}{outputs[].url}`（302 跟随）抽象为可注入函数。
 * 生产为 null——此时经 [OkHttpEntryPointClient.downloadToFile]（Spring 注入的
 * client 就是它）；单测注入 fake 免网络。
 */
fun interface ArtifactDownloader {
    suspend fun download(creds: EntryPointCreds, url: String, target: Path)
}

/**
 * [ImageProcessor] 的第一个真实实现——把单页处理委托给 EntryPoint（141:9800
 * 本地推理平台）。一次 [process] = upload → submit → poll → 下载产物到临时文件。
 *
 * 可用性语义（保持便宜）：[isAvailable] **只检查 entrypointUrl 配置非空，不做
 * 网络探测**。EntryPoint 不可达不在装配期报 DOWN，而由逐页处理抛
 * `EpException("EP_UNREACHABLE", ...)` 如实呈现——编排层按页失败记账即可。
 *
 * capabilities = [CapabilityMapper.mappedTypes]（type_mapping 配置 ∪ D5 默认表，
 * 乐观缓存值）：无映射类型对本处理器不可见（D5），编排层不得路由。
 *
 * 临时文件生命周期：[process] 返回的 Path（以及重编码前的原始产物文件——已在
 * 成功重编码后删除）**由调用方负责**——编排域（W2）把产物搬进
 * `{cache}/enhanced/{gid}/` 后必须清理该临时文件（D18 消费契约不变）。
 *
 * 业务超时（D15）：`anotherviewer.processing.task-timeout-minutes`（默认 30min）；
 * 到点先 best-effort cancel（D3，永不抛）再抛 `EP_TIMEOUT`。
 */
@Component
class EntryPointProcessor(
    private val client: EntryPointClient,
    private val mapper: CapabilityMapper,
    private val serverConfigService: ServerConfigService,
    @Value("\${anotherviewer.processing.task-timeout-minutes:30}")
    private val taskTimeoutMinutes: Long = 30,
    @Value("\${anotherviewer.processing.poll-initial-ms:1000}")
    private val pollInitialMs: Long = 1_000,
    @Value("\${anotherviewer.processing.poll-max-ms:5000}")
    private val pollMaxMs: Long = 5_000,
    private val artifactDownloader: ArtifactDownloader? = null,
) : ImageProcessor {

    private val logger = LoggerFactory.getLogger(EntryPointProcessor::class.java)

    override val id: String = PROCESSOR_ID

    /**
     * 配置非空即视为可用——**不做网络探测**（手册 C1：保持便宜；可达性由
     * 逐页处理报 EP_UNREACHABLE）。凭据每次从 serverConfig 现读：token
     * 服务端加密落盘，不进内存缓存（D1/D20）。
     */
    override fun isAvailable(): Boolean =
        serverConfigService.get(ServerConfigService.KEY_ENTRYPOINT_URL).isNotBlank()

    /** 乐观缓存值（60s TTL，见 [CapabilityMapper.mappedTypes]）。 */
    override val capabilities: Set<ProcessingType>
        get() = mapper.mappedTypes()

    /**
     * 处理单页：upload → submit → 轮询（1s 起 ×1.5，5s 封顶）→ 下载产物到
     * 临时文件（必要时 ImageIO 重编码）→ 返回路径。
     *
     * @return 处理产物临时文件路径——**生命周期归调用方**（见类 KDoc）。
     * @throws EpException 机读码：EP_UNMAPPED（类型未映射）、EP_UNREACHABLE
     *   （URL 未配置/连不上）、EntryPoint 信封 code、EP_TIMEOUT、EP_FAILED
     *   （终态失败/取消、无产物）。message 不做机判（D12）。
     */
    override suspend fun process(input: Path, options: ProcessingOptions): Path {
        val creds = EntryPointCreds(
            baseUrl = serverConfigService.get(ServerConfigService.KEY_ENTRYPOINT_URL).trim(),
            token = serverConfigService.get(ServerConfigService.KEY_ENTRYPOINT_TOKEN).trim(),
        )
        if (creds.baseUrl.isBlank()) {
            throw EpException(CODE_UNREACHABLE, 0, "entrypoint url is not configured")
        }
        val mapping = mapper.resolve(options.type)
            ?: throw EpException(
                CODE_UNMAPPED, 0,
                "no EntryPoint mapping for ${options.type}; configure ${ServerConfigService.KEY_TYPE_MAPPING}",
            )

        val reference = client.upload(creds, input)
        val submitted = client.submit(creds, mapping.moduleId, mapping.capability, reference, mapping.params)
        logger.info(
            "EntryPoint task {} submitted ({}_{}, input={})",
            submitted.taskId, mapping.moduleId, mapping.capability, input,
        )
        val result = pollUntilTerminal(creds, submitted.taskId)
        return when {
            result.state == EpTaskState.COMPLETED -> downloadArtifact(creds, result, options)
            // FAILED/CANCELLED：终态失败——信封 code 无法经 EpTaskResult 透传
            // （冻结契约 error:String），统一 EP_FAILED，error 文案进 message（不机判）。
            else -> throw EpException(
                CODE_FAILED, 0,
                result.error ?: "EntryPoint task ${submitted.taskId} ${result.state.name.lowercase(Locale.ROOT)}",
            )
        }
    }

    // ------------------------------------------------------------------
    // 轮询
    // ------------------------------------------------------------------

    private suspend fun pollUntilTerminal(creds: EntryPointCreds, taskId: String): EpTaskResult {
        val deadline = System.currentTimeMillis() + taskTimeoutMinutes * 60_000
        var intervalMs = pollInitialMs
        while (true) {
            val result = client.result(creds, taskId)
            if (result.isTerminal) return result
            if (result.state == EpTaskState.UNKNOWN) {
                // D4：未知状态按运行中处理并 WARN（词表外新状态不做硬失败）。
                logger.warn("EntryPoint task {} reported unknown state; treating as running (D4)", taskId)
            }
            if (System.currentTimeMillis() >= deadline) {
                // D15：到点先 best-effort cancel（D3 永不抛）再失败。
                runCatching { client.cancel(creds, taskId) }
                throw EpException(
                    CODE_TIMEOUT, 0,
                    "EntryPoint task $taskId exceeded ${taskTimeoutMinutes}min (cancelled best-effort)",
                )
            }
            delay(intervalMs)
            intervalMs = ((intervalMs * 3) / 2 + 1).coerceAtMost(pollMaxMs)
        }
    }

    // ------------------------------------------------------------------
    // 产物下载与重编码
    // ------------------------------------------------------------------

    private suspend fun downloadArtifact(creds: EntryPointCreds, result: EpTaskResult, options: ProcessingOptions): Path {
        // node_id 取 "output"，缺省取第一个（产物 URL 是不透明句柄，禁止解析）。
        val artifact = result.outputs.firstOrNull { it.nodeId == OUTPUT_NODE_ID }
            ?: result.outputs.firstOrNull()
            ?: throw EpException(CODE_FAILED, 0, "completed EntryPoint task ${result.taskId} has no output artifact")

        val target = Files.createTempFile(TEMP_PREFIX, "." + outputFormat(options))
        try {
            artifactDownloader
                ?.download(creds, artifact.url, target)
                ?: (client as? OkHttpEntryPointClient)?.downloadToFile(creds, artifact.url, target)
                ?: throw EpException(CODE_UNREACHABLE, 0, "no ArtifactDownloader wired for EntryPointProcessor")
        } catch (e: Exception) {
            Files.deleteIfExists(target)
            throw e
        }
        return recodeIfNeeded(target, outputFormat(options))
    }

    /**
     * 产物格式与 [options.outputFormat] 不同 → ImageIO 重编码；任何失败
     * （解码不出/编码器缺失/IO）退回原样产物。成功重编码后删除原始产物文件。
     */
    private fun recodeIfNeeded(artifact: Path, targetFormat: String): Path {
        val detected = detectImageFormat(artifact)?.let { normalizeFormat(it) }
        if (detected == null || detected == targetFormat) return artifact
        return try {
            val image = ImageIO.read(artifact.toFile()) ?: return artifact
            val encoded = encode(image, targetFormat)
                ?: run {
                    logger.warn(
                        "EntryPoint artifact is {} but no ImageIO writer for '{}'; keeping original",
                        detected, targetFormat,
                    )
                    return artifact
                }
            val out = Files.createTempFile(TEMP_PREFIX, "." + targetFormat)
            Files.write(out, encoded)
            Files.deleteIfExists(artifact)
            logger.info("EntryPoint artifact re-encoded {} → {}", detected, targetFormat)
            out
        } catch (e: Exception) {
            logger.warn("EntryPoint artifact re-encode to {} failed; keeping original: {}", targetFormat, e.message)
            artifact
        }
    }

    /**
     * ImageIO 编码到目标格式；带透明通道的图写 JPEG 时先铺白底
     * （JPEG 无 alpha，否则写码器产出劣化/异常）。返回编码后字节，null = 无编码器。
     */
    private fun encode(image: BufferedImage, format: String): ByteArray? {
        val needsFlatten = image.colorModel.hasAlpha() && (format == "jpg" || format == "jpeg")
        val source = if (needsFlatten) {
            val flattened = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
            val g = flattened.createGraphics()
            g.drawImage(image, 0, 0, Color.WHITE, null)
            g.dispose()
            flattened
        } else {
            image
        }
        val buffer = ByteArrayOutputStream()
        if (!ImageIO.write(source, normalizeFormat(format), buffer)) return null
        return buffer.toByteArray()
    }

    /** 经 ImageIO 探测文件实际图像格式（png/jpeg/...）；非 ImageIO 图像返回 null。 */
    private fun detectImageFormat(file: Path): String? = try {
        val stream: ImageInputStream = ImageIO.createImageInputStream(file.toFile()) ?: return null
        stream.use {
            val readers = ImageIO.getImageReaders(it)
            if (readers.hasNext()) readers.next().formatName else null
        }
    } catch (e: Exception) {
        logger.warn("EntryPoint artifact format probe failed: {}", e.message)
        null
    }

    private fun normalizeFormat(name: String): String = when (name.lowercase(Locale.ROOT)) {
        "jpeg" -> "jpg"
        else -> name.lowercase(Locale.ROOT)
    }

    /**
     * 输出格式白名单与 [com.hippo.anotherviewer.web.processing.NoopProcessor] 一致
     * （会进入临时文件扩展名）。
     */
    private fun outputFormat(options: ProcessingOptions): String {
        val format = options.outputFormat.lowercase(Locale.ROOT)
        require(format in SUPPORTED_FORMATS) { "unsupported output format: ${options.outputFormat}" }
        return format
    }

    companion object {
        const val PROCESSOR_ID = "entrypoint"
        const val OUTPUT_NODE_ID = "output"

        /** 本地错误码族（error_code 列语义：EntryPoint code 或 EP_*）。 */
        const val CODE_UNREACHABLE = "EP_UNREACHABLE"
        const val CODE_UNMAPPED = "EP_UNMAPPED"
        const val CODE_TIMEOUT = "EP_TIMEOUT"
        const val CODE_FAILED = "EP_FAILED"

        private const val TEMP_PREFIX = "ep-out-"
        val SUPPORTED_FORMATS = setOf("png", "jpg", "jpeg", "webp")
    }
}
