package com.hippo.anotherviewer.web.processing.ep

import com.hippo.anotherviewer.web.processing.ProcessingOptions
import com.hippo.anotherviewer.web.processing.ProcessingType
import com.hippo.anotherviewer.web.service.ServerConfigService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * EntryPointProcessor 全链单测（fake client 注入，免网络）：
 * upload → submit → 轮询归一化 → 产物下载/重编码 → 临时文件路径；
 * 超时先 cancel 再抛 EP_TIMEOUT（D15）、终态失败 EP_FAILED、
 * CapabilityMapper 的 D5 默认表 / type_mapping 叠加 / 目录缓存与 NOT_FOUND 刷新。
 */
class EntryPointProcessorTest {

    @TempDir
    lateinit var tempDir: Path

    private val fake = FakeEntryPointClient()
    private val downloadedUrls = mutableListOf<String>()
    private var artifactBytes: ByteArray = pngBytes()
    private val createdOutputs = mutableListOf<Path>()

    private val downloader = ArtifactDownloader { _, url, target ->
        downloadedUrls.add(url)
        Files.write(target, artifactBytes)
    }

    @BeforeEach
    fun setUp() {
        downloadedUrls.clear()
        artifactBytes = pngBytes()
    }

    @AfterEach
    fun tearDown() {
        createdOutputs.forEach { Files.deleteIfExists(it) }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private fun configService(url: String = "http://ep.local:9800", mapping: String = ""): ServerConfigService {
        val config = mock(ServerConfigService::class.java)
        `when`(config.get(anyString(), anyString())).thenReturn("")
        `when`(config.get(ServerConfigService.KEY_ENTRYPOINT_URL, "")).thenReturn(url)
        `when`(config.get(ServerConfigService.KEY_ENTRYPOINT_TOKEN, "")).thenReturn("tok")
        `when`(config.get(ServerConfigService.KEY_TYPE_MAPPING, "")).thenReturn(mapping)
        return config
    }

    private fun processor(
        config: ServerConfigService = configService(),
        client: EntryPointClient = fake,
        taskTimeoutMinutes: Long = 30,
        pollInitialMs: Long = 1_000,
    ): EntryPointProcessor = EntryPointProcessor(
        client = client,
        mapper = CapabilityMapper(config, client),
        serverConfigService = config,
        taskTimeoutMinutes = taskTimeoutMinutes,
        pollInitialMs = pollInitialMs,
        pollMaxMs = 5_000,
        artifactDownloader = downloader,
    )

    private fun input(): Path = Files.write(tempDir.resolve("0001.jpg"), byteArrayOf(1, 2, 3, 4))

    private fun track(path: Path): Path {
        createdOutputs.add(path)
        return path
    }

    private fun pngBytes(alpha: Boolean = false): ByteArray {
        val type = if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val image = BufferedImage(4, 4, type)
        val graphics = image.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, 4, 4)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", out)) { "png writer missing" }
        return out.toByteArray()
    }

    private fun imageBytes(format: String, alpha: Boolean = false): ByteArray {
        val type = if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val image = BufferedImage(4, 4, type)
        val graphics = image.createGraphics()
        graphics.color = Color.BLUE
        graphics.fillRect(0, 0, 4, 4)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        check(ImageIO.write(image, format, out)) { "ImageIO writer missing for $format" }
        return out.toByteArray()
    }

    private fun formatOf(file: Path): String? =
        ImageIO.createImageInputStream(file.toFile())?.use { stream ->
            ImageIO.getImageReaders(stream).asSequence().firstOrNull()?.formatName
        }

    // ------------------------------------------------------------------
    // 可用性与 capabilities
    // ------------------------------------------------------------------

    @Test
    fun `isAvailable reflects configured url without probing`() {
        assertFalse(processor(config = configService(url = "")).isAvailable())
        assertTrue(processor(config = configService(url = "http://141:9800")).isAvailable())
    }

    @Test
    fun `capabilities expose only mapped types per D5 defaults`() {
        assertEquals(
            setOf(ProcessingType.REMOVE_BG, ProcessingType.UPSCALE_2X, ProcessingType.UPSCALE_4X),
            processor().capabilities,
        )
    }

    // ------------------------------------------------------------------
    // CapabilityMapper
    // ------------------------------------------------------------------

    @Test
    fun `mapper defaults map remove_bg and upscales leaving denoise unmapped`() {
        val mapper = CapabilityMapper(configService(), fake)

        assertEquals(CapabilityMapper.Mapping("rembg", "remove_bg"), mapper.resolve(ProcessingType.REMOVE_BG))
        assertEquals(
            CapabilityMapper.Mapping("realesr", "upscale", mapOf("scale_factor" to 2)),
            mapper.resolve(ProcessingType.UPSCALE_2X),
        )
        assertEquals(
            CapabilityMapper.Mapping("realesr", "upscale", mapOf("scale_factor" to 4)),
            mapper.resolve(ProcessingType.UPSCALE_4X),
        )
        assertNull(mapper.resolve(ProcessingType.DENOISE))
        assertNull(mapper.resolve(ProcessingType.DENOISE_UPSCALE))
    }

    @Test
    fun `mapper overlays type_mapping config over defaults`() {
        val mapping = """
            {"REMOVE_BG":{"module":"birefnet","capability":"matte","params":{"model":"isnet"}},
             "DENOISE":{"module":"scunet","capability":"denoise","params":{}}}
        """.trimIndent()
        val mapper = CapabilityMapper(configService(mapping = mapping), fake)

        assertEquals(
            CapabilityMapper.Mapping("birefnet", "matte", mapOf("model" to "isnet")),
            mapper.resolve(ProcessingType.REMOVE_BG),
        )
        // UPSCALE_2X 未在配置中出现 → 保持 D5 默认。
        assertEquals(
            CapabilityMapper.Mapping("realesr", "upscale", mapOf("scale_factor" to 2)),
            mapper.resolve(ProcessingType.UPSCALE_2X),
        )
        // 配置显式映射后 DENOISE 对处理器可见。
        assertTrue(ProcessingType.DENOISE in mapper.mappedTypes())
        // 未知键忽略不致命。
        assertEquals(
            setOf(ProcessingType.REMOVE_BG, ProcessingType.UPSCALE_2X, ProcessingType.UPSCALE_4X, ProcessingType.DENOISE),
            mapper.mappedTypes(),
        )
    }

    @Test
    fun `mapper falls back to defaults on invalid type_mapping json`() {
        val mapper = CapabilityMapper(configService(mapping = "{not json"), fake)

        assertEquals(CapabilityMapper.Mapping("rembg", "remove_bg"), mapper.resolve(ProcessingType.REMOVE_BG))
        assertNull(mapper.resolve(ProcessingType.DENOISE))
    }

    @Test
    fun `mapper capabilities caches catalog and refreshes once on MODULE_NOT_FOUND`() = runBlocking {
        val mapper = CapabilityMapper(configService(), fake)
        fake.capabilityErrors.add(EpException("MODULE_NOT_FOUND", 404, "no module"))
        fake.catalog = listOf(EpCapability("rembg", "remove_bg", "image", "image", 50))
        val creds = EntryPointCreds("http://ep.local:9800", "tok")

        val caps = mapper.capabilities(creds)

        assertEquals(1, caps.size)
        assertEquals(2, fake.capabilitiesCalls.get()) // 首次失败 + 强刷重试一次
        mapper.capabilities(creds) // TTL 内命中缓存
        assertEquals(2, fake.capabilitiesCalls.get())
    }

    // ------------------------------------------------------------------
    // process 全链
    // ------------------------------------------------------------------

    @Test
    fun `process uploads submits polls and downloads output node artifact`() = runBlocking {
        fake.results.addLast(EpTaskResult("task-1", EpTaskState.QUEUED))
        fake.results.addLast(EpTaskResult("task-1", EpTaskState.RUNNING))
        fake.results.addLast(
            EpTaskResult(
                "task-1", EpTaskState.COMPLETED,
                outputs = listOf(
                    EpArtifact("input", "/tasks/t1/input"),
                    EpArtifact("run", "/tasks/t1/run"),
                    EpArtifact("output", "/tasks/t1/output"),
                ),
            ),
        )
        val bytes = pngBytes()
        artifactBytes = bytes

        val output = track(processor().process(input(), ProcessingOptions(ProcessingType.REMOVE_BG)))

        assertEquals("rembg", fake.submitted?.first)
        assertEquals("remove_bg", fake.submitted?.second)
        assertEquals("ws/uploads/in-0001.png", fake.submitted?.third)
        assertEquals(emptyMap<String, Any?>(), fake.submitParams)
        assertEquals("/tasks/t1/output", downloadedUrls.single())
        assertTrue(Files.readAllBytes(output).contentEquals(bytes)) // 同格式 png → 原样返回
        assertTrue(output.fileName.toString().startsWith("ep-out-"))
        assertTrue(output.fileName.toString().endsWith(".png"))
        assertTrue(fake.cancelCalls.isEmpty())
    }

    @Test
    fun `process passes default mapping params for upscales`() = runBlocking {
        fake.results.addLast(EpTaskResult("task-9", EpTaskState.COMPLETED, outputs = listOf(EpArtifact("output", "/o"))))

        track(processor().process(input(), ProcessingOptions(ProcessingType.UPSCALE_2X)))

        assertEquals("realesr", fake.submitted?.first)
        assertEquals("upscale", fake.submitted?.second)
        assertEquals(mapOf<String, Any?>("scale_factor" to 2), fake.submitParams)
    }

    @Test
    fun `process falls back to first artifact when no output node`() = runBlocking {
        fake.results.addLast(
            EpTaskResult(
                "task-1", EpTaskState.COMPLETED,
                outputs = listOf(EpArtifact("run", "/tasks/t1/run-only")),
            ),
        )

        track(processor().process(input(), ProcessingOptions(ProcessingType.REMOVE_BG)))

        assertEquals("/tasks/t1/run-only", downloadedUrls.single())
    }

    @Test
    fun `failed result raises EP_FAILED with error text`() {
        fake.results.addLast(EpTaskResult("task-1", EpTaskState.FAILED, error = "model crashed"))

        val ex = assertThrows(EpException::class.java) {
            runBlocking { processor().process(input(), ProcessingOptions(ProcessingType.REMOVE_BG)) }
        }
        assertEquals("EP_FAILED", ex.code)
        assertEquals(0, ex.httpStatus)
        assertTrue(ex.message!!.contains("model crashed"))
    }

    @Test
    fun `cancelled result raises EP_FAILED too`() {
        fake.results.addLast(EpTaskResult("task-1", EpTaskState.CANCELLED))

        val ex = assertThrows(EpException::class.java) {
            runBlocking { processor().process(input(), ProcessingOptions(ProcessingType.REMOVE_BG)) }
        }
        assertEquals("EP_FAILED", ex.code)
        assertTrue(ex.message!!.contains("cancelled"))
    }

    @Test
    fun `unmapped processing type raises EP_UNMAPPED`() {
        val ex = assertThrows(EpException::class.java) {
            runBlocking { processor().process(input(), ProcessingOptions(ProcessingType.DENOISE)) }
        }
        assertEquals("EP_UNMAPPED", ex.code)
        assertNull(fake.submitted) // 未发起任何远程操作
    }

    @Test
    fun `business timeout cancels remote task then throws EP_TIMEOUT`() {
        fake.results.addLast(EpTaskResult("task-1", EpTaskState.RUNNING))

        val ex = assertThrows(EpException::class.java) {
            runBlocking {
                processor(taskTimeoutMinutes = 0, pollInitialMs = 1)
                    .process(input(), ProcessingOptions(ProcessingType.REMOVE_BG))
            }
        }
        assertEquals("EP_TIMEOUT", ex.code)
        assertEquals(listOf("task-1"), fake.cancelCalls) // 到点先 best-effort cancel（D15/D3）
    }

    @Test
    fun `unknown state is treated as running and polling continues`() = runBlocking {
        fake.results.addLast(EpTaskResult("task-1", EpTaskState.UNKNOWN))
        fake.results.addLast(
            EpTaskResult("task-1", EpTaskState.COMPLETED, outputs = listOf(EpArtifact("output", "/o"))),
        )

        val output = track(processor(pollInitialMs = 1).process(input(), ProcessingOptions(ProcessingType.REMOVE_BG)))

        assertTrue(Files.exists(output))
    }

    // ------------------------------------------------------------------
    // 重编码分支
    // ------------------------------------------------------------------

    @Test
    fun `artifact in different format is re-encoded to requested format`() = runBlocking {
        fake.results.addLast(EpTaskResult("task-1", EpTaskState.COMPLETED, outputs = listOf(EpArtifact("output", "/o"))))
        artifactBytes = imageBytes("jpg") // JPEG 产物 → 请求 png

        val output = track(processor().process(input(), ProcessingOptions(ProcessingType.REMOVE_BG, outputFormat = "png")))

        assertTrue(output.fileName.toString().endsWith(".png"))
        assertEquals("png", formatOf(output))
    }

    @Test
    fun `alpha artifact re-encoded to jpg gets white background`() = runBlocking {
        fake.results.addLast(EpTaskResult("task-1", EpTaskState.COMPLETED, outputs = listOf(EpArtifact("output", "/o"))))
        artifactBytes = pngBytes(alpha = true) // RGBA PNG（rembg 产物形态）

        val output = track(processor().process(input(), ProcessingOptions(ProcessingType.REMOVE_BG, outputFormat = "jpg")))

        assertEquals("JPEG", formatOf(output))
    }

    @Test
    fun `undecodable artifact falls back to original bytes`() = runBlocking {
        fake.results.addLast(EpTaskResult("task-1", EpTaskState.COMPLETED, outputs = listOf(EpArtifact("output", "/o"))))
        artifactBytes = byteArrayOf(0, 1, 2, 3, -1, -2) // 非图像字节

        val output = track(processor().process(input(), ProcessingOptions(ProcessingType.REMOVE_BG, outputFormat = "png")))

        assertArrayEquals(artifactBytes, Files.readAllBytes(output))
    }

    // ------------------------------------------------------------------
    // fake client
    // ------------------------------------------------------------------

    private class FakeEntryPointClient : EntryPointClient {
        val capabilitiesCalls = java.util.concurrent.atomic.AtomicInteger()
        val capabilityErrors = ArrayDeque<EpException>()
        var catalog: List<EpCapability> = emptyList()
        val uploaded = mutableListOf<Path>()
        var uploadReference = "ws/uploads/in-0001.png"
        var submitted: Triple<String, String, String>? = null
        var submitParams: Map<String, Any?> = emptyMap()
        val results = ArrayDeque<EpTaskResult>()
        private var lastResult: EpTaskResult? = null
        val cancelCalls = mutableListOf<String>()

        override suspend fun capabilities(creds: EntryPointCreds): List<EpCapability> {
            capabilitiesCalls.incrementAndGet()
            capabilityErrors.removeFirstOrNull()?.let { throw it }
            return catalog
        }

        override suspend fun upload(creds: EntryPointCreds, file: Path): String {
            uploaded.add(file)
            return uploadReference
        }

        override suspend fun submit(
            creds: EntryPointCreds,
            moduleId: String,
            capability: String,
            inputPath: String,
            params: Map<String, Any?>,
        ): EpSubmitResult {
            submitted = Triple(moduleId, capability, inputPath)
            submitParams = params
            return EpSubmitResult("task-1", 1)
        }

        override suspend fun result(creds: EntryPointCreds, taskId: String): EpTaskResult {
            val next = results.removeFirstOrNull() ?: lastResult ?: error("no scripted result")
            lastResult = next
            return next
        }

        override fun cancel(creds: EntryPointCreds, taskId: String) {
            cancelCalls.add(taskId)
        }
    }
}
